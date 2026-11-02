# ReadCore HTTP API

The server defaults to `0.0.0.0:3435`, configurable with `READCORE_LISTEN`. All `/api` endpoints except `/api/v1/health` require `Authorization: Bearer <token>`; OPDS also accepts HTTP Basic (any username, token as password). The token is shared across users: `user_id` separates progress but is not an authorization boundary. Use TLS and restrict access to trusted users.

## Version 1

- `GET /api/v1/health`: public JSON health status.
- `GET /api/v1/books?user_id=default&sort=recent`: recursive library with `id`, `title`, `author`, `series`, `language`, `format`, `size`, `last_read_at`, and `progress`. Sort is `recent` (default), `title`, or `author`. Time values are UTC Unix milliseconds. Missing metadata falls back to the filename and parent directory.
- `GET /api/v1/books/{id}/download`: original file. Supports `Range: bytes=<offset>-` with `If-Range: <ETag>`; the server returns `206` and `Content-Range` for a valid range, `200` when the validator changed, or `416` for an unsatisfiable range.
- `GET /api/v1/books/{id}/cover`: cached JPEG thumbnail for EPUB, PDF, CBZ, and supported CBR covers, or `404` when unavailable.
- `POST /api/v1/scan`: invalidate the library cache and scan immediately; returns a JSON `count`. A recursive file watcher also invalidates the cache. If watching is unavailable, the normal cache expires after 10 seconds.
- `GET /api/v1/sync?user_id=default`: progress records for that user.
- `GET /api/v1/sync/{user_id}/{book_id}`: one progress record, or `404`.
- `PUT /api/v1/sync`: upsert a progress record using the request body below; returns the winning record.
- `PUT /api/v1/sync/{user_id}/{book_id}`: same operation, requiring path and body IDs to match.
- `GET /opds/v1.2/catalog`: authenticated OPDS acquisition feed.

```json
{"book_id":"<64-character-id>","user_id":"default","position":"epubcfi(/6/2!/4)","percent":12.5,"device_id":"phone-a","updated_at":1793600000000}
```

`position` is an EPUB CFI or `pdf:<zero-based-page>`, `cbz:<zero-based-page>`, or `mobi:<zero-based-section>` for the current Android reader. The newest `updated_at` wins; equal timestamps leave the existing value unchanged. Device clocks must be reasonably synchronized. Timestamps are supplied by clients, so this is not safe against malicious clients sharing a token.

## Existing endpoints

The original routes remain available: `GET /api/books`, `GET /api/books/{id}/file`, `GET /api/books/{id}/cover`, `GET /api/progress/{id}`, `PUT /api/progress/{id}`, and `GET /opds`. The legacy progress API uses `{ "chapter": 0, "offset": 0.0 }` and its original shared progress table; it does not automatically migrate or synchronize with `/api/v1/sync`. The web UI currently uses this legacy progress API. Static web files at `/`, `/app.js`, `/styles.css`, and `/vendor/*` are public.

Invalid input returns `400`, missing resources `404`, invalid ranges `416`, missing/invalid credentials `401`, and internal errors `500`.
