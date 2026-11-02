use super::{authorize, epub_cover, epub_package, scanned, zip_entry, App, Book};
use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use notify::{RecursiveMode, Watcher};
use serde::{Deserialize, Serialize};
use sqlx::{Row, SqlitePool};
use std::{collections::HashMap, io::Cursor, path::Path as FsPath, sync::Arc, time::{Duration, UNIX_EPOCH}};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;

// user_version 0 is the original server schema; its progress table is untouched.
pub async fn migrate(db: &SqlitePool) -> Result<(), sqlx::Error> {
    let mut tx = db.begin().await?;
    let version: i64 = sqlx::query_scalar("PRAGMA user_version").fetch_one(&mut *tx).await?;
    if version < 1 {
        sqlx::query("CREATE TABLE IF NOT EXISTS v1_progress (user_id TEXT NOT NULL, book_id TEXT NOT NULL, position TEXT NOT NULL, percent REAL NOT NULL, device_id TEXT NOT NULL, updated_at INTEGER NOT NULL, last_read_at INTEGER NOT NULL, PRIMARY KEY(user_id, book_id))").execute(&mut *tx).await?;
        sqlx::query("CREATE TABLE IF NOT EXISTS v1_covers (book_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, jpeg BLOB NOT NULL)").execute(&mut *tx).await?;
        sqlx::query("PRAGMA user_version = 1").execute(&mut *tx).await?;
    }
    tx.commit().await
}

#[derive(Debug)]
enum Error { Unauthorized, NotFound, Invalid(&'static str), Range, Internal }
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            Self::Unauthorized => (StatusCode::UNAUTHORIZED, "Authentication required"),
            Self::NotFound => (StatusCode::NOT_FOUND, "Not found"),
            Self::Invalid(message) => (StatusCode::BAD_REQUEST, message),
            Self::Range => (StatusCode::RANGE_NOT_SATISFIABLE, "Invalid byte range"),
            Self::Internal => (StatusCode::INTERNAL_SERVER_ERROR, "Internal error"),
        };
        (status, Json(serde_json::json!({"error": message}))).into_response()
    }
}
type Result<T> = std::result::Result<T, Error>;
fn auth(headers: &HeaderMap, state: &App) -> Result<()> { authorize(headers, state).map_err(|_| Error::Unauthorized) }
fn database_error(error: sqlx::Error) -> Error { tracing::error!(%error, "database operation failed"); Error::Internal }
fn io_error(error: std::io::Error) -> Error { tracing::error!(%error, "file operation failed"); Error::Internal }

#[derive(Default)]
pub struct Metadata { pub title: Option<String>, pub author: Option<String>, pub series: Option<String>, pub language: Option<String> }
fn nonempty(value: Option<&str>) -> Option<String> { value.map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned) }
pub fn epub_details(path: &FsPath) -> Metadata {
    let read = || -> Option<Metadata> {
        let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
        let (_, package) = epub_package(&mut zip)?;
        let doc = roxmltree::Document::parse(&package).ok()?;
        let metadata = doc.descendants().find(|n| n.tag_name().name() == "metadata")?;
        let item = |name: &str| nonempty(metadata.descendants().find(|n| n.tag_name().name() == name).and_then(|n| n.text()));
        let series = metadata.descendants().find(|n| n.tag_name().name() == "meta" && (n.attribute("name") == Some("calibre:series") || n.attribute("property") == Some("belongs-to-collection"))).and_then(|n| n.attribute("content").or_else(|| n.text()));
        Some(Metadata { title: item("title"), author: item("creator"), series: nonempty(series), language: item("language") })
    };
    read().unwrap_or_default()
}
pub fn pdf_details(path: &FsPath) -> Metadata {
    let read = || -> Option<Metadata> {
        let doc = lopdf::Document::load(path).ok()?;
        let info_id = doc.trailer.get(b"Info").ok()?.as_reference().ok()?;
        let info = doc.get_object(info_id).ok()?.as_dict().ok()?;
        let field = |key: &[u8]| -> Option<String> {
            let bytes = info.get(key).ok()?.as_str().ok()?;
            let value = if bytes.starts_with(&[0xfe, 0xff]) {
                let units = bytes[2..].chunks_exact(2).map(|c| u16::from_be_bytes([c[0], c[1]])).collect::<Vec<_>>();
                String::from_utf16(&units).ok()?
            } else { String::from_utf8_lossy(bytes).into_owned() };
            nonempty(Some(&value))
        };
        Some(Metadata { title: field(b"Title"), author: field(b"Author"), ..Metadata::default() })
    };
    read().unwrap_or_default()
}
fn comic_xml(bytes: &[u8]) -> Option<Metadata> {
    let xml = std::str::from_utf8(bytes).ok()?;
    let doc = roxmltree::Document::parse(xml).ok()?;
    let item = |name: &str| nonempty(doc.descendants().find(|n| n.tag_name().name().eq_ignore_ascii_case(name)).and_then(|n| n.text()));
    Some(Metadata { title: item("Title"), author: item("Writer").or_else(|| item("Penciller")), series: item("Series"), language: item("LanguageISO") })
}
pub fn comic_details(path: &FsPath) -> Metadata {
    let read = || -> Option<Metadata> {
        let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
        let name = zip.file_names().find(|n| n.rsplit('/').next().is_some_and(|s| s.eq_ignore_ascii_case("ComicInfo.xml")))?.to_owned();
        comic_xml(&zip_entry(&mut zip, &name, 1024 * 1024)?)
    };
    read().unwrap_or_default()
}
// bsdtar reads RAR entries to stdout only: no archive paths are extracted to disk.
// Limit both output size and elapsed time for malformed or untrusted archives.
fn rar_output(path: &FsPath, member: Option<&str>, limit: u64) -> Option<Vec<u8>> {
    use std::{io::Read, process::{Command, Stdio}, sync::mpsc};
    let started = std::time::Instant::now();
    let mut command = Command::new("bsdtar");
    if let Some(name) = member { command.arg("-xOf").arg(path).arg("--").arg(name); }
    else { command.arg("-tf").arg(path); }
    let mut child = command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let stdout = child.stdout.take()?;
    let (sender, receiver) = mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let mut output = Vec::new();
        let result = stdout.take(limit + 1).read_to_end(&mut output).map(|_| output);
        let _ = sender.send(result);
    });
    let bytes = match receiver.recv_timeout(Duration::from_secs(15)) {
        Ok(Ok(bytes)) if bytes.len() as u64 <= limit => bytes,
        _ => { let _ = child.kill(); let _ = child.wait(); return None; }
    };
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return if status.success() { Some(bytes) } else { None },
            Ok(None) if started.elapsed() < Duration::from_secs(15) => std::thread::sleep(Duration::from_millis(25)),
            _ => { let _ = child.kill(); let _ = child.wait(); return None; }
        }
    }
}
fn rar_names(path: &FsPath) -> Option<Vec<String>> {
    let list = String::from_utf8(rar_output(path, None, 1024 * 1024)?).ok()?;
    Some(list.lines().map(str::to_owned).collect())
}
pub fn rar_details(path: &FsPath) -> Metadata {
    let read = || -> Option<Metadata> {
        let name = rar_names(path)?.into_iter().find(|name| name.rsplit('/').next().is_some_and(|s| s.eq_ignore_ascii_case("ComicInfo.xml")))?;
        comic_xml(&rar_output(path, Some(&name), 1024 * 1024)?)
    };
    read().unwrap_or_default()
}
fn rar_cover(path: &FsPath) -> Option<Vec<u8>> {
    let mut names: Vec<_> = rar_names(path)?.into_iter().filter(|n| {
        n.rsplit('.').next().is_some_and(|extension| matches!(extension.to_ascii_lowercase().as_str(), "jpg" | "jpeg" | "png" | "webp"))
    }).collect();
    names.sort();
    rar_output(path, Some(names.first()?), 8 * 1024 * 1024)
}
fn comic_cover(path: &FsPath) -> Option<Vec<u8>> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    let mut names: Vec<String> = zip.file_names().filter(|n| {
        n.rsplit('.').next().is_some_and(|extension| matches!(extension.to_ascii_lowercase().as_str(), "jpg" | "jpeg" | "png" | "webp"))
    }).map(str::to_owned).collect();
    names.sort();
    zip_entry(&mut zip, names.first()?, 8 * 1024 * 1024)
}

pub fn watch_library(state: Arc<App>) -> notify::Result<notify::RecommendedWatcher> {
    let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel();
    let mut watcher = notify::recommended_watcher(move |event: notify::Result<notify::Event>| {
        if event.is_ok() { let _ = sender.send(()); }
    })?;
    watcher.watch(&state.library, RecursiveMode::Recursive)?;
    tokio::spawn(async move {
        while receiver.recv().await.is_some() {
            tokio::time::sleep(Duration::from_millis(300)).await;
            while receiver.try_recv().is_ok() {}
            *state.cache.lock().await = None;
            tracing::debug!("library changed; scan cache invalidated");
        }
    });
    Ok(watcher)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct SyncRecord { book_id: String, user_id: String, position: String, percent: f64, device_id: String, updated_at: i64 }
#[derive(Deserialize)]
struct UserQuery { user_id: Option<String> }
#[derive(Deserialize)]
struct BookQuery { user_id: Option<String>, sort: Option<String> }
#[derive(Serialize)]
struct BookEntry { #[serde(flatten)] book: Book, last_read_at: Option<i64>, progress: Option<SyncRecord> }
fn decode(row: &sqlx::sqlite::SqliteRow) -> Result<SyncRecord> {
    Ok(SyncRecord {
        book_id: row.try_get("book_id").map_err(database_error)?,
        user_id: row.try_get("user_id").map_err(database_error)?,
        position: row.try_get("position").map_err(database_error)?,
        percent: row.try_get("percent").map_err(database_error)?,
        device_id: row.try_get("device_id").map_err(database_error)?,
        updated_at: row.try_get("updated_at").map_err(database_error)?,
    })
}
fn user(value: Option<String>) -> String { value.filter(|s| !s.is_empty()).unwrap_or_else(|| "default".to_owned()) }
async fn health() -> Json<serde_json::Value> { Json(serde_json::json!({"status": "ok"})) }
async fn library(State(state): State<Arc<App>>, headers: HeaderMap, Query(query): Query<BookQuery>) -> Result<Json<Vec<BookEntry>>> {
    auth(&headers, &state)?;
    let user_id = user(query.user_id);
    let rows = sqlx::query("SELECT book_id, user_id, position, percent, device_id, updated_at FROM v1_progress WHERE user_id = ?")
        .bind(user_id).fetch_all(&state.db).await.map_err(database_error)?;
    let progress: HashMap<String, SyncRecord> = rows.iter().map(decode).collect::<Result<Vec<_>>>()?.into_iter().map(|record| (record.book_id.clone(), record)).collect();
    let entries = scanned(&state).await.map_err(|_| Error::Internal)?;
    let mut books: Vec<BookEntry> = entries.into_iter().map(|(book, _)| {
        let item = progress.get(&book.id).cloned();
        BookEntry { last_read_at: item.as_ref().map(|p| p.updated_at), book, progress: item }
    }).collect();
    match query.sort.as_deref().unwrap_or("recent") {
        "recent" => books.sort_by(|a, b| b.last_read_at.cmp(&a.last_read_at).then_with(|| a.book.title.to_lowercase().cmp(&b.book.title.to_lowercase()))),
        "title" => books.sort_by_key(|b| b.book.title.to_lowercase()),
        "author" => books.sort_by_key(|b| (b.book.author.as_deref().unwrap_or("").to_lowercase(), b.book.title.to_lowercase())),
        _ => return Err(Error::Invalid("sort must be recent, title, or author")),
    }
    Ok(Json(books))
}
async fn rescan(State(state): State<Arc<App>>, headers: HeaderMap) -> Result<Json<serde_json::Value>> {
    auth(&headers, &state)?;
    *state.cache.lock().await = None;
    let books = scanned(&state).await.map_err(|_| Error::Internal)?;
    Ok(Json(serde_json::json!({"count": books.len()})))
}
async fn all_sync(State(state): State<Arc<App>>, headers: HeaderMap, Query(query): Query<UserQuery>) -> Result<Json<Vec<SyncRecord>>> {
    auth(&headers, &state)?;
    let rows = sqlx::query("SELECT book_id, user_id, position, percent, device_id, updated_at FROM v1_progress WHERE user_id = ?")
        .bind(user(query.user_id)).fetch_all(&state.db).await.map_err(database_error)?;
    Ok(Json(rows.iter().map(decode).collect::<Result<Vec<_>>>()?))
}
async fn one_sync(State(state): State<Arc<App>>, Path((user_id, book_id)): Path<(String, String)>, headers: HeaderMap) -> Result<Json<SyncRecord>> {
    auth(&headers, &state)?;
    let row = sqlx::query("SELECT book_id, user_id, position, percent, device_id, updated_at FROM v1_progress WHERE user_id = ? AND book_id = ?")
        .bind(user_id).bind(book_id).fetch_optional(&state.db).await.map_err(database_error)?.ok_or(Error::NotFound)?;
    Ok(Json(decode(&row)?))
}
async fn write_record(state: &App, record: SyncRecord) -> Result<Json<SyncRecord>> {
    if record.user_id.is_empty() || record.user_id.len() > 128 || record.book_id.len() != 64 || record.position.is_empty() || record.position.len() > 2048 || record.device_id.is_empty() || record.device_id.len() > 128 || record.updated_at <= 0 || !record.percent.is_finite() || !(0.0..=100.0).contains(&record.percent) {
        return Err(Error::Invalid("Invalid progress record"));
    }
    if !scanned(state).await.map_err(|_| Error::Internal)?.iter().any(|(book, _)| book.id == record.book_id) { return Err(Error::NotFound); }
    sqlx::query("INSERT INTO v1_progress (user_id, book_id, position, percent, device_id, updated_at, last_read_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, book_id) DO UPDATE SET position=excluded.position, percent=excluded.percent, device_id=excluded.device_id, updated_at=excluded.updated_at, last_read_at=excluded.last_read_at WHERE excluded.updated_at > v1_progress.updated_at")
        .bind(&record.user_id).bind(&record.book_id).bind(&record.position).bind(record.percent).bind(&record.device_id).bind(record.updated_at).bind(record.updated_at)
        .execute(&state.db).await.map_err(database_error)?;
    let row = sqlx::query("SELECT book_id, user_id, position, percent, device_id, updated_at FROM v1_progress WHERE user_id = ? AND book_id = ?")
        .bind(&record.user_id).bind(&record.book_id).fetch_one(&state.db).await.map_err(database_error)?;
    Ok(Json(decode(&row)?))
}
async fn put_sync(State(state): State<Arc<App>>, headers: HeaderMap, Json(record): Json<SyncRecord>) -> Result<Json<SyncRecord>> {
    auth(&headers, &state)?; write_record(&state, record).await
}
async fn put_one(State(state): State<Arc<App>>, Path((user_id, book_id)): Path<(String, String)>, headers: HeaderMap, Json(record): Json<SyncRecord>) -> Result<Json<SyncRecord>> {
    auth(&headers, &state)?;
    if record.user_id != user_id || record.book_id != book_id { return Err(Error::Invalid("Path and payload do not match")); }
    write_record(&state, record).await
}
async fn book_path(state: &App, id: &str) -> Result<(Book, std::path::PathBuf)> {
    let (book, path) = scanned(state).await.map_err(|_| Error::Internal)?.into_iter().find(|(book, _)| book.id == id).ok_or(Error::NotFound)?;
    let path = tokio::fs::canonicalize(path).await.map_err(|_| Error::NotFound)?;
    if !path.starts_with(&state.library) { return Err(Error::NotFound); }
    Ok((book, path))
}
async fn download(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap) -> Result<Response> {
    auth(&headers, &state)?;
    let (book, path) = book_path(&state, &id).await?;
    let mut file = tokio::fs::File::open(path).await.map_err(io_error)?;
    let meta = file.metadata().await.map_err(io_error)?;
    let size = meta.len();
    let modified = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|t| t.as_nanos()).unwrap_or(0);
    let etag = format!("\"{}-{}-{}\"", id, size, modified);
    let range_allowed = headers.get(header::IF_RANGE).and_then(|h| h.to_str().ok()).map_or(true, |h| h == etag);
    let requested = if range_allowed { headers.get(header::RANGE).and_then(|h| h.to_str().ok()) } else { None };
    let start = match requested {
        Some(value) => {
            let (start, end) = value.strip_prefix("bytes=").and_then(|s| s.split_once('-')).ok_or(Error::Range)?;
            if !end.is_empty() || start.is_empty() { return Err(Error::Range); }
            let start: u64 = start.parse().map_err(|_| Error::Range)?;
            if start >= size { return Err(Error::Range); }
            start
        }
        None => 0,
    };
    file.seek(std::io::SeekFrom::Start(start)).await.map_err(io_error)?;
    let length = size - start;
    let mime = match book.format.as_str() { "epub" => "application/epub+zip", "pdf" => "application/pdf", "mobi" => "application/x-mobipocket-ebook", "cbz" => "application/vnd.comicbook+zip", "cbr" => "application/vnd.comicbook-rar", _ => "application/octet-stream" };
    let mut response = Response::new(Body::from_stream(ReaderStream::new(file.take(length))));
    *response.status_mut() = if requested.is_some() { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK };
    response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
    response.headers_mut().insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    response.headers_mut().insert(header::CONTENT_LENGTH, HeaderValue::from_str(&length.to_string()).map_err(|_| Error::Internal)?);
    response.headers_mut().insert(header::ETAG, HeaderValue::from_str(&etag).map_err(|_| Error::Internal)?);
    if requested.is_some() { response.headers_mut().insert(header::CONTENT_RANGE, HeaderValue::from_str(&format!("bytes {}-{}/{}", start, size - 1, size)).map_err(|_| Error::Internal)?); }
    Ok(response)
}
async fn thumbnail(State(state): State<Arc<App>>, Path(id): Path<String>, headers: HeaderMap) -> Result<Response> {
    auth(&headers, &state)?;
    let (book, path) = book_path(&state, &id).await?;
    let metadata = tokio::fs::metadata(&path).await.map_err(io_error)?;
    let modified = metadata.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|t| t.as_nanos()).unwrap_or(0);
    let fingerprint = format!("{}-{modified}", metadata.len());
    let cached = sqlx::query("SELECT jpeg FROM v1_covers WHERE book_id = ? AND fingerprint = ?").bind(&id).bind(&fingerprint).fetch_optional(&state.db).await.map_err(database_error)?;
    let bytes = if let Some(row) = cached { row.try_get::<Vec<u8>, _>(0).map_err(database_error)? } else {
        let original = if book.format == "pdf" {
            let mut command = tokio::process::Command::new("pdftoppm");
            command.args(["-f", "1", "-l", "1", "-scale-to", "500", "-singlefile", "-jpeg"]).arg(&path).kill_on_drop(true);
            let output = tokio::time::timeout(Duration::from_secs(20), command.output()).await.map_err(|_| Error::Internal)?.map_err(io_error)?;
            if !output.status.success() || output.stdout.len() > 8 * 1024 * 1024 { return Err(Error::NotFound); }
            output.stdout
        } else {
            tokio::task::spawn_blocking(move || -> Option<Vec<u8>> {
                match book.format.as_str() { "epub" => Some(epub_cover(&path)?.0), "cbz" => comic_cover(&path), "cbr" => rar_cover(&path), _ => None }
            }).await.map_err(|_| Error::Internal)?.ok_or(Error::NotFound)?
        };
        let bytes = tokio::task::spawn_blocking(move || -> Option<Vec<u8>> {
            let mut reader = image::ImageReader::new(Cursor::new(original)).with_guessed_format().ok()?;
            let mut limits = image::Limits::default();
            limits.max_image_width = Some(10000);
            limits.max_image_height = Some(10000);
            limits.max_alloc = Some(64 * 1024 * 1024);
            reader.limits(limits);
            let image = reader.decode().ok()?.thumbnail(500, 500).to_rgb8();
            let mut output = Cursor::new(Vec::new());
            image::DynamicImage::ImageRgb8(image).write_to(&mut output, image::ImageFormat::Jpeg).ok()?;
            Some(output.into_inner())
        }).await.map_err(|_| Error::Internal)?.ok_or(Error::NotFound)?;
        sqlx::query("INSERT INTO v1_covers(book_id, fingerprint, jpeg) VALUES (?, ?, ?) ON CONFLICT(book_id) DO UPDATE SET fingerprint=excluded.fingerprint, jpeg=excluded.jpeg")
            .bind(&id).bind(fingerprint).bind(&bytes).execute(&state.db).await.map_err(database_error)?;
        bytes
    };
    Ok(([(header::CONTENT_TYPE, "image/jpeg"), (header::X_CONTENT_TYPE_OPTIONS, "nosniff")], bytes).into_response())
}
pub fn router() -> Router<Arc<App>> {
    Router::new().route("/api/v1/health", get(health)).route("/api/v1/books", get(library))
        .route("/api/v1/books/:id/download", get(download)).route("/api/v1/books/:id/cover", get(thumbnail))
        .route("/api/v1/scan", post(rescan)).route("/api/v1/sync", get(all_sync).put(put_sync))
        .route("/api/v1/sync/:user_id/:book_id", get(one_sync).put(put_one))
        .route("/opds/v1.2/catalog", get(super::opds))
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    use sqlx::sqlite::SqlitePoolOptions;

    #[test]
    fn recursively_scans_and_uses_folder_and_filename_fallback() {
        let root = tempfile::tempdir().unwrap();
        let author = root.path().join("Sabahattin Ali");
        std::fs::create_dir_all(&author).unwrap();
        let file = author.join("Kuyucakli Yusuf.epub");
        std::fs::write(&file, b"not an epub").unwrap();
        let books = super::super::scan(root.path());
        assert_eq!(books.len(), 1);
        assert_eq!(books[0].0.title, "Kuyucakli Yusuf");
        assert_eq!(books[0].0.author.as_deref(), Some("Sabahattin Ali"));
        std::fs::remove_file(file).unwrap();
        assert!(super::super::scan(root.path()).is_empty());
    }

    #[tokio::test]
    async fn newer_progress_wins_for_each_user_and_book() {
        let root = tempfile::tempdir().unwrap();
        std::fs::write(root.path().join("Book.pdf"), b"not a pdf").unwrap();
        let id = super::super::scan(root.path())[0].0.id.clone();
        let db = SqlitePoolOptions::new().max_connections(1).connect("sqlite::memory:").await.unwrap();
        migrate(&db).await.unwrap();
        let state = App { library: root.path().to_path_buf(), token_hash: Sha256::digest(b"test-secret-token").into(), db, cache: tokio::sync::Mutex::new(None) };
        let record = |user: &str, time, position: &str| SyncRecord { book_id: id.clone(), user_id: user.into(), position: position.into(), percent: 10.0, device_id: "device".into(), updated_at: time };
        write_record(&state, record("alice", 200, "pdf:2")).await.unwrap();
        let older = write_record(&state, record("alice", 100, "pdf:1")).await.unwrap().0;
        assert_eq!(older.position, "pdf:2");
        let newer = write_record(&state, record("alice", 300, "pdf:3")).await.unwrap().0;
        assert_eq!(newer.position, "pdf:3");
        let other_user = write_record(&state, record("bob", 50, "pdf:1")).await.unwrap().0;
        assert_eq!(other_user.position, "pdf:1");
    }
}
