use axum::{
    body::Body,
    extract::{Path, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::get, Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use rust_embed::RustEmbed;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions}, Row, SqlitePool};
use std::{env, io::Read, path::{Component, Path as FsPath, PathBuf}, str::FromStr, sync::Arc, time::{Duration, Instant}};
use subtle::ConstantTimeEq;
use tokio_util::io::ReaderStream;
use tower_http::trace::TraceLayer;
use walkdir::WalkDir;

mod v1;

#[derive(RustEmbed)]
#[folder = "static/"]
struct WebAssets;

struct App { library: PathBuf, token_hash: [u8; 32], db: SqlitePool, cache: tokio::sync::Mutex<Option<(Instant, Vec<(Book, PathBuf)>)>> }
#[derive(Clone, Serialize)]
struct Book { id: String, title: String, author: Option<String>, series: Option<String>, language: Option<String>, format: String, size: u64 }
#[derive(Serialize, Deserialize)]
struct Progress { chapter: i64, offset: f64 }

type ApiResult<T> = Result<T, (StatusCode, &'static str)>;
const CREATE_PROGRESS: &str = "CREATE TABLE IF NOT EXISTS progress(book_id TEXT PRIMARY KEY, chapter INTEGER NOT NULL, offset REAL NOT NULL, updated_at INTEGER NOT NULL)";
const UPSERT_PROGRESS: &str = "INSERT INTO progress(book_id, chapter, offset, updated_at) VALUES (?, ?, ?, unixepoch()) ON CONFLICT(book_id) DO UPDATE SET chapter=excluded.chapter, offset=excluded.offset, updated_at=excluded.updated_at";

// A single shared token represents one household/library. Basic auth allows OPDS
// clients to use any username and the token as their password.
fn authorize(headers: &HeaderMap, state: &App) -> ApiResult<()> {
    let candidate = headers.get(header::AUTHORIZATION).and_then(|h| h.to_str().ok()).and_then(|value| {
        if let Some(token) = value.strip_prefix("Bearer ") { return Some(token.to_owned()); }
        let decoded = STANDARD.decode(value.strip_prefix("Basic ")?).ok()?;
        let credentials = String::from_utf8(decoded).ok()?;
        Some(credentials.split_once(':')?.1.to_owned())
    });
    let valid = candidate.map(|s| {
        let hash: [u8; 32] = Sha256::digest(s.as_bytes()).into();
        bool::from(hash.ct_eq(&state.token_hash))
    }).unwrap_or(false);
    if valid { Ok(()) } else { Err((StatusCode::UNAUTHORIZED, "Authentication required")) }
}

fn supported(path: &FsPath) -> Option<&'static str> {
    match path.extension()?.to_str()?.to_ascii_lowercase().as_str() {
        "epub" => Some("epub"), "pdf" => Some("pdf"), "mobi" => Some("mobi"),
        "cbz" => Some("cbz"), "cbr" => Some("cbr"), _ => None,
    }
}

// Only small metadata/cover entries are read; archive files are never extracted.
fn zip_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str, limit: u64) -> Option<Vec<u8>> {
    let entry = zip.by_name(name).ok()?;
    if entry.size() > limit { return None; }
    let mut bytes = Vec::new();
    entry.take(limit + 1).read_to_end(&mut bytes).ok()?;
    if bytes.len() as u64 > limit { None } else { Some(bytes) }
}
fn epub_package(zip: &mut zip::ZipArchive<std::fs::File>) -> Option<(String, String)> {
    let container = String::from_utf8(zip_entry(zip, "META-INF/container.xml", 1024 * 1024)?).ok()?;
    let doc = roxmltree::Document::parse(&container).ok()?;
    let path = doc.descendants().find(|n| n.tag_name().name() == "rootfile")?.attribute("full-path")?.to_owned();
    let package = String::from_utf8(zip_entry(zip, &path, 4 * 1024 * 1024)?).ok()?;
    Some((path, package))
}
fn epub_cover(path: &FsPath) -> Option<(Vec<u8>, &'static str)> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    let (package_path, package) = epub_package(&mut zip)?;
    let doc = roxmltree::Document::parse(&package).ok()?;
    let cover_id = doc.descendants().find(|n| n.tag_name().name() == "meta" && n.attribute("name") == Some("cover")).and_then(|n| n.attribute("content"));
    let item = doc.descendants().filter(|n| n.tag_name().name() == "item").find(|n| {
        n.attribute("properties").unwrap_or("").split_whitespace().any(|p| p == "cover-image") || (cover_id.is_some() && n.attribute("id") == cover_id)
    })?;
    let mime = match item.attribute("media-type")? { "image/jpeg" => "image/jpeg", "image/png" => "image/png", "image/webp" => "image/webp", _ => return None };
    let href = item.attribute("href")?.split('#').next()?;
    let decoded = percent_encoding::percent_decode_str(href).decode_utf8().ok()?;
    let joined = FsPath::new(&package_path).parent()?.join(decoded.as_ref());
    let mut normalized = PathBuf::new();
    for component in joined.components() {
        match component { Component::Normal(c) => normalized.push(c), Component::CurDir => {}, Component::ParentDir => { if !normalized.pop() { return None; } }, _ => return None }
    }
    let bytes = zip_entry(&mut zip, normalized.to_str()?, 8 * 1024 * 1024)?;
    Some((bytes, mime))
}
// Never follow symlinks: a read-only library must not expose paths outside it.
fn scan(library: &FsPath) -> Vec<(Book, PathBuf)> {
    let mut books = Vec::new();
    for entry in WalkDir::new(library).follow_links(false).into_iter().filter_map(Result::ok) {
        if !entry.file_type().is_file() { continue; }
        let path = entry.path();
        let Some(format) = supported(path) else { continue; };
        let Ok(relative) = path.strip_prefix(library) else { continue; };
        let Some(name) = path.file_stem().and_then(|s| s.to_str()) else { continue; };
        let Ok(metadata) = entry.metadata() else { continue; };
        // IDs stay stable while a relative path is unchanged.
        let id = hex::encode(Sha256::digest(relative.to_string_lossy().as_bytes()));
        let extracted = match format {
            "epub" => v1::epub_details(path),
            "cbz" => v1::comic_details(path),
            "cbr" => v1::rar_details(path),
            "pdf" => v1::pdf_details(path),
            _ => v1::Metadata::default(),
        };
        let fallback_author = relative.parent().and_then(|p| p.file_name()).and_then(|p| p.to_str()).filter(|s| !s.is_empty()).map(str::to_owned);
        books.push((Book { id, title: extracted.title.unwrap_or_else(|| name.to_string()), author: extracted.author.or(fallback_author).or_else(|| Some("Unknown Author".to_owned())), series: extracted.series, language: extracted.language, format: format.to_string(), size: metadata.len() }, path.to_path_buf()));
    }
    books.sort_by(|a, b| a.0.title.to_lowercase().cmp(&b.0.title.to_lowercase()));
    books
}
async fn scanned(state: &App) -> ApiResult<Vec<(Book, PathBuf)>> {
    // A short cache keeps cover requests from rescanning the whole library.
    let mut cache = state.cache.lock().await;
    if let Some((at, entries)) = &*cache {
        if at.elapsed() < Duration::from_secs(10) { return Ok(entries.clone()); }
    }
    let library = state.library.clone();
    let entries = tokio::task::spawn_blocking(move || scan(&library)).await.map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Library scan failed"))?;
    *cache = Some((Instant::now(), entries.clone()));
    Ok(entries)
}
async fn books(State(state): State<Arc<App>>, headers: HeaderMap) -> ApiResult<Json<Vec<Book>>> {
    authorize(&headers, &state)?;
    Ok(Json(scanned(&state).await?.into_iter().map(|(book, _)| book).collect()))
}
async fn file(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap) -> ApiResult<Response> {
    authorize(&headers, &state)?;
    let (book, path) = scanned(&state).await?.into_iter().find(|(book, _)| book.id == id)
        .ok_or((StatusCode::NOT_FOUND, "Book not found"))?;
    // Recheck canonical location before opening in case the library changed after scanning.
    let canonical = tokio::fs::canonicalize(path).await.map_err(|_| (StatusCode::NOT_FOUND, "Book not found"))?;
    if !canonical.starts_with(&state.library) { return Err((StatusCode::NOT_FOUND, "Book not found")); }
    let handle = tokio::fs::File::open(canonical).await.map_err(|_| (StatusCode::NOT_FOUND, "Book not found"))?;
    let content_type = match book.format.as_str() {
        "epub" => "application/epub+zip", "pdf" => "application/pdf", "mobi" => "application/x-mobipocket-ebook",
        "cbz" => "application/vnd.comicbook+zip", "cbr" => "application/vnd.comicbook-rar", _ => "application/octet-stream"
    };
    Ok(([(header::CONTENT_TYPE, content_type), (header::X_CONTENT_TYPE_OPTIONS, "nosniff")], Body::from_stream(ReaderStream::new(handle))).into_response())
}
async fn cover(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap) -> ApiResult<Response> {
    authorize(&headers, &state)?;
    let (_, path) = scanned(&state).await?.into_iter().find(|(book, _)| book.id == id && book.format == "epub")
        .ok_or((StatusCode::NOT_FOUND, "Cover not found"))?;
    let canonical = tokio::fs::canonicalize(path).await.map_err(|_| (StatusCode::NOT_FOUND, "Cover not found"))?;
    if !canonical.starts_with(&state.library) { return Err((StatusCode::NOT_FOUND, "Cover not found")); }
    let (image, mime) = tokio::task::spawn_blocking(move || epub_cover(&canonical)).await
        .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Cover read failed"))?
        .ok_or((StatusCode::NOT_FOUND, "Cover not found"))?;
    Ok(([(header::CONTENT_TYPE, mime), (header::X_CONTENT_TYPE_OPTIONS, "nosniff")], image).into_response())
}
async fn get_progress(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap) -> ApiResult<Json<Progress>> {
    authorize(&headers, &state)?;
    let row = sqlx::query("SELECT chapter, offset FROM progress WHERE book_id = ?").bind(id).fetch_optional(&state.db).await
        .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Database error"))?
        .ok_or((StatusCode::NOT_FOUND, "No progress"))?;
    Ok(Json(Progress { chapter: row.get(0), offset: row.get(1) }))
}
async fn put_progress(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap, Json(progress): Json<Progress>) -> ApiResult<StatusCode> {
    authorize(&headers, &state)?;
    if progress.chapter < 0 || !progress.offset.is_finite() || !(0.0..=1.0).contains(&progress.offset) {
        return Err((StatusCode::BAD_REQUEST, "Invalid progress"));
    }
    if !scanned(&state).await?.iter().any(|(book, _)| book.id == id) { return Err((StatusCode::NOT_FOUND, "Book not found")); }
    sqlx::query(UPSERT_PROGRESS)
        .bind(id).bind(progress.chapter).bind(progress.offset).execute(&state.db).await
        .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "Database error"))?;
    Ok(StatusCode::NO_CONTENT)
}
fn xml(value: &str) -> String {
    value.chars().filter(|&c| matches!(c, '\u{9}' | '\u{a}' | '\u{d}' | '\u{20}'..='\u{d7ff}' | '\u{e000}'..='\u{fffd}' | '\u{10000}'..='\u{10ffff}'))
        .collect::<String>().replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
}
async fn opds(State(state): State<Arc<App>>, headers: HeaderMap) -> ApiResult<Response> {
    authorize(&headers, &state)?;
    let updated = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    let mut feed = format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><id>urn:readcore:library</id><title>ReadCore</title><author><name>ReadCore</name></author><updated>{}</updated><link rel=\"self\" href=\"/opds\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\"/>", updated);
    for (book, _) in scanned(&state).await? {
        let mime = match book.format.as_str() { "epub" => "application/epub+zip", "pdf" => "application/pdf", "mobi" => "application/x-mobipocket-ebook", "cbz" => "application/vnd.comicbook+zip", _ => "application/vnd.comicbook-rar" };
        feed.push_str(&format!("<entry><id>urn:readcore:{}</id><title>{}</title><updated>{}</updated>", book.id, xml(&book.title), updated));
        if let Some(author) = &book.author { feed.push_str(&format!("<author><name>{}</name></author>", xml(author))); }
        if book.format == "epub" { feed.push_str(&format!("<link rel=\"http://opds-spec.org/image\" href=\"/api/books/{}/cover\"/>", book.id)); }
        feed.push_str(&format!("<link rel=\"http://opds-spec.org/acquisition\" href=\"/api/books/{}/file\" type=\"{}\"/></entry>", book.id, mime));
    }
    feed.push_str("</feed>");
    Ok(([(header::CONTENT_TYPE, HeaderValue::from_static("application/atom+xml;profile=opds-catalog;kind=acquisition;charset=utf-8"))], feed).into_response())
}
// Static files are public; book and progress endpoints retain their existing auth.
fn embedded_asset(path: &str) -> Response {
    let Some(asset) = WebAssets::get(path) else { return StatusCode::NOT_FOUND.into_response(); };
    let content_type = match path.rsplit('.').next() {
        Some("html") => "text/html; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("js") => "application/javascript; charset=utf-8",
        _ => "application/octet-stream",
    };
    ([(header::CONTENT_TYPE, content_type), (header::X_CONTENT_TYPE_OPTIONS, "nosniff")], asset.data.into_owned()).into_response()
}
async fn web_index() -> Response { embedded_asset("index.html") }
async fn web_script() -> Response { embedded_asset("app.js") }
async fn web_style() -> Response { embedded_asset("styles.css") }
async fn web_vendor(Path(path): Path<String>) -> Response { embedded_asset(&format!("vendor/{path}")) }

async fn challenge(response: Response) -> Response {
    let mut response = response;
    if response.status() == StatusCode::UNAUTHORIZED {
        response.headers_mut().insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Basic realm=\"ReadCore\", charset=\"UTF-8\""));
    }
    response
}
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).init();
    let token = env::var("READCORE_TOKEN").map_err(|_| "READCORE_TOKEN is required")?;
    if token.len() < 16 { return Err("READCORE_TOKEN must be at least 16 bytes".into()); }
    let library = std::fs::canonicalize(env::var("READCORE_LIBRARY").unwrap_or_else(|_| "/books".into()))?;
    let options = SqliteConnectOptions::from_str(&env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite:///data/readcore.db".into()))?.create_if_missing(true).journal_mode(SqliteJournalMode::Wal).busy_timeout(Duration::from_secs(5));
    let db = SqlitePoolOptions::new().max_connections(5).connect_with(options).await?;
    sqlx::query(CREATE_PROGRESS).execute(&db).await?;
    v1::migrate(&db).await?;
    let state = Arc::new(App { library, token_hash: Sha256::digest(token.as_bytes()).into(), db, cache: tokio::sync::Mutex::new(None) });
    let _watcher = match v1::watch_library(state.clone()) {
        Ok(watcher) => Some(watcher),
        Err(error) => { tracing::warn!(%error, "file watcher unavailable; periodic scans remain enabled"); None }
    };
    let app = Router::new().merge(v1::router()).route("/", get(web_index)).route("/app.js", get(web_script))
        .route("/styles.css", get(web_style)).route("/vendor/*path", get(web_vendor))
        .route("/api/books", get(books)).route("/api/books/:id/file", get(file))
        .route("/api/books/:id/cover", get(cover)).route("/api/progress/:id", get(get_progress).put(put_progress)).route("/opds", get(opds))
        .layer(axum::middleware::map_response(challenge)).layer(TraceLayer::new_for_http()).with_state(state);
    let listen = env::var("READCORE_LISTEN").unwrap_or_else(|_| "0.0.0.0:3435".into());
    let listener = tokio::net::TcpListener::bind(listen.as_str()).await?;
    tracing::info!(address = %listen, "ReadCore listening");
    axum::serve(listener, app).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_text_is_safe_xml() {
        assert_eq!(xml("<A & B>\u{0}\""), "&lt;A &amp; B&gt;&quot;");
    }

    #[test]
    fn book_extensions_are_case_insensitive() {
        assert_eq!(supported(FsPath::new("one.EPUB")), Some("epub"));
        assert_eq!(supported(FsPath::new("one.txt")), None);
    }

    #[tokio::test]
    async fn accepts_bearer_and_basic_tokens_and_rejects_wrong_token() {
        let db = SqlitePoolOptions::new().max_connections(1).connect("sqlite::memory:").await.unwrap();
        let app = App { library: std::env::temp_dir(), token_hash: Sha256::digest(b"test-secret-token").into(), db, cache: tokio::sync::Mutex::new(None) };
        let mut headers = HeaderMap::new();
        assert!(authorize(&headers, &app).is_err());
        headers.insert(header::AUTHORIZATION, HeaderValue::from_static("Bearer wrong"));
        assert!(authorize(&headers, &app).is_err());
        headers.insert(header::AUTHORIZATION, HeaderValue::from_static("Bearer test-secret-token"));
        assert!(authorize(&headers, &app).is_ok());
        let basic = format!("Basic {}", STANDARD.encode("user:test-secret-token"));
        headers.insert(header::AUTHORIZATION, HeaderValue::from_str(&basic).unwrap());
        assert!(authorize(&headers, &app).is_ok());
    }

    #[tokio::test]
    async fn progress_updates_existing_book() {
        let db = SqlitePoolOptions::new().max_connections(1).connect("sqlite::memory:").await.unwrap();
        sqlx::query(CREATE_PROGRESS).execute(&db).await.unwrap();
        for chapter in [1_i64, 3] {
            sqlx::query(UPSERT_PROGRESS).bind("id").bind(chapter).bind(0.5_f64).execute(&db).await.unwrap();
        }
        let row = sqlx::query("SELECT chapter, offset FROM progress WHERE book_id = ?").bind("id").fetch_one(&db).await.unwrap();
        assert_eq!(row.get::<i64, _>(0), 3);
        assert_eq!(row.get::<f64, _>(1), 0.5);
    }
}
