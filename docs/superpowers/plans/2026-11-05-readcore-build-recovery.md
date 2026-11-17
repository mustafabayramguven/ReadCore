# ReadCore Build Recovery — Implementation Plan

**Goal:** Fix the Rust server build blocker on latest `origin/main`, verify the
server end-to-end locally, and let CI produce a fresh APK — without force-pushing
or publishing anything before approval.

**Architecture:** One minimal source change in `server/src/v1.rs` resolves the
`Result` alias conflict. Verification runs the exact Docker build command
locally, then exercises the real HTTP API. GitHub Actions validates the Docker
image and Android build on a PR.

**Tech stack:** Rust/Axum/SQLite, GitHub Actions, Docker build (CI only),
Android/Kotlin/Compose, `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-11-05-readcore-build-recovery-design.md`

## Global Constraints

- Base work on `origin/main` = `8194591`; if it has moved, stop and ask.
- Never force-push. Never touch `main` locally or remotely without approval.
- Work only on `fix/server-build` in an isolated worktree.
- No Android source changes unless CI exposes a blocker; if so, stop and report.
- Do not commit secrets, `Cargo.lock`, `target/`, or `server/static/vendor/`.
- Exact Docker verification command: `cargo test --release && cargo build --release`.
- Stop after the PR is green and the APK is downloaded; merge/publish is a
  separate decision.

## Review Focus

- **Open-ended range resume** — Android sends `Range: bytes=N-`; verify `206` and
  `Content-Range`. A closed range (`bytes=0-99`) returns `416` by design.
- **Authentication** — verify missing/wrong token → `401`; Bearer and OPDS Basic
  both work.
- **Symlink escape** — verify a symlink inside the library is not indexed or
  downloadable.
- **Older timestamps** — existing test
  `newer_progress_wins_for_each_user_and_book` must keep passing.
- **Metadata fallback** — existing scan test must keep passing; the synthetic
  EPUB must index title/author.

## File Structure

- **Modify:** `server/src/v1.rs` — only source change.
- **Create:** `docs/superpowers/specs/2026-11-05-readcore-build-recovery-design.md`
- **Create:** `docs/superpowers/plans/2026-11-05-readcore-build-recovery.md`
- **Generated, untracked:** `server/Cargo.lock`, `server/static/vendor/`,
  `server/target/`, `dist/`.

---

### Task 1: Preserve the stale checkout and create the worktree

**Files:** none modified yet.

- [ ] **Step 1: Verify the original checkout is clean**

Run: `git -C ~/ReadCoreMain status --short`
Expected: no output.

- [ ] **Step 2: Back up the stale commit**

```bash
git -C ~/ReadCoreMain branch backup/local-main-10f3d9c
```

- [ ] **Step 3: Fetch and verify remote**

```bash
git -C ~/ReadCoreMain fetch origin
git -C ~/ReadCoreMain rev-parse origin/main
```

Expected: `81945918ccca6f5be636c0d71cae36c5f4112ced`.

- [ ] **Step 4: Create the isolated worktree**

```bash
git -C ~/ReadCoreMain worktree add ../ReadCoreMain-fix -b fix/server-build origin/main
cd ~/ReadCoreMain-fix
git status -sb
```

Expected: branch `fix/server-build`, clean tree.

- [ ] **Step 5: Write the design and plan docs**

Create the design doc and this plan under `docs/superpowers/`.

- [ ] **Step 6: Commit docs**

```bash
git add docs/superpowers
git commit -m "docs: record ReadCore build recovery design and plan"
```

---

### Task 2: Fix the compile blocker

**Files:**
- Modify: `server/src/v1.rs:18`, `:43`, all `-> Result<` handler signatures,
  `:191`, `:215`, `:356`
- Test: built-in tests in `server/src/v1.rs`

**Interfaces:**
- `migrate(&SqlitePool) -> Result<(), sqlx::Error>` (standard library `Result`).
- All v1 handlers return `ApiResult<T>` where
  `type ApiResult<T> = std::result::Result<T, Error>`.

- [ ] **Step 1: Confirm the failing baseline**

```bash
cd server
cargo test --release
```

Expected: 8 errors — `E0107` on `migrate`, five `E0277` conversions, one
`E0308`, one `v1::Error: StdError`.

- [ ] **Step 2: Rename the alias and usages**

In `server/src/v1.rs`:
- Replace `type Result<T> = std::result::Result<T, Error>;` with
  `type ApiResult<T> = std::result::Result<T, Error>;`
- Keep `migrate` as
  `pub async fn migrate(db: &SqlitePool) -> Result<(), sqlx::Error>`.
- Change every other `-> Result<...>` in the file to `-> ApiResult<...>`.
- Change `collect::<Result<Vec<_>>>()` at lines 191 and 215 to
  `collect::<ApiResult<Vec<_>>>()`.
- Change the test call at line 356 to `let _ = write_record(...)`.

- [ ] **Step 3: Run the tests**

```bash
cargo test --release
```

Expected: 6 passed; 0 failed.

- [ ] **Step 4: Build the release binary**

```bash
cargo build --release
```

Expected: `Finished release ...`; `server/target/release/readcore-server` exists.

- [ ] **Step 5: Commit**

```bash
git add server/src/v1.rs
git commit -m "fix(server): resolve v1 Result alias and migrate build errors"
```

---

### Task 3: Local end-to-end verification

**Files:** none committed; generated assets live under `/tmp/readcore-e2e` and
`server/static/vendor/`.

- [ ] **Step 1: Fetch pinned web-reader assets**

```bash
mkdir -p server/static/vendor
curl -fsSL -o server/static/vendor/epub.min.js https://cdn.jsdelivr.net/npm/epubjs@0.3.93/dist/epub.min.js
curl -fsSL -o server/static/vendor/jszip.min.js https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js
cargo build --release
```

- [ ] **Step 2: Create a synthetic EPUB fixture**

Run a Python `zipfile` script under `/tmp/readcore-e2e/library/` containing
`mimetype`, `META-INF/container.xml`, `OEBPS/content.opf`, and one
`OEBPS/ch1.xhtml` chapter. Expected: a valid `.epub` that indexes with title
`ReadCore Test Book` and author `Test Author`.

- [ ] **Step 3: Start the release server**

```bash
READCORE_TOKEN='test-token-0123456789' \
READCORE_LISTEN='127.0.0.1:3435' \
DATABASE_URL='sqlite:///tmp/readcore-e2e/data/readcore.db' \
READCORE_LIBRARY='/tmp/readcore-e2e/library' \
./server/target/release/readcore-server >/tmp/readcore-e2e/server.log 2>&1 &
```

Wait for `http://127.0.0.1:3435/api/v1/health` to return `{"status":"ok"}`.

- [ ] **Step 4: Authentication and catalog**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3435/api/v1/books          # 401
curl -s -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/books
curl -s -X POST -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/scan
```

Expected: 401; one JSON book with `format: "epub"`; scan returns `{"count":1}`.

- [ ] **Step 5: Download and resume**

```bash
ID=$(curl -s -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/books | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
curl -s -D - -o /dev/null -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/books/$ID/download
curl -s -D - -o /dev/null -H "Authorization: Bearer test-token-0123456789" -H 'Range: bytes=100-' http://127.0.0.1:3435/api/v1/books/$ID/download
```

Expected: `200` with `ETag`/`Accept-Ranges`; then `206` with
`Content-Range: bytes 100-.../...`.

- [ ] **Step 6: Progress sync**

```bash
curl -s -X PUT -H "Authorization: Bearer test-token-0123456789" -H 'Content-Type: application/json' \
  -d "{\"book_id\":\"$ID\",\"user_id\":\"reader-1\",\"position\":\"epubcfi(/6/2!/4)\",\"percent\":12.5,\"device_id\":\"test-device\",\"updated_at\":1793600000000}" \
  http://127.0.0.1:3435/api/v1/sync
curl -s -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/sync/reader-1/$ID
```

Expected: position `epubcfi(/6/2!/4)` and percent `12.5`.

- [ ] **Step 7: OPDS, web assets, and symlink safety**

```bash
curl -s -u "user:test-token-0123456789" http://127.0.0.1:3435/opds/v1.2/catalog | grep -c '<entry>'
for p in / /app.js /styles.css /vendor/epub.min.js /vendor/jszip.min.js; do curl -s -o /dev/null -w "$p %{http_code}\n" http://127.0.0.1:3435$p; done
ln -s /tmp/outside-secret.epub /tmp/readcore-e2e/library/link.epub
curl -s -X POST -H "Authorization: Bearer test-token-0123456789" http://127.0.0.1:3435/api/v1/scan
```

Expected: all assets `200`; symlink not indexed.

- [ ] **Step 8: Stop the server and clean generated files**

```bash
kill %1
rm -rf server/static/vendor server/Cargo.lock
git status --short
```

Expected: only the committed docs and `server/src/v1.rs` modification.

---

### Task 4: Push, open the PR, and watch CI

**Files:** none.

- [ ] **Step 1: Confirm the diff**

```bash
git diff --stat origin/main...HEAD
git status -sb
```

Expected: only `server/src/v1.rs` plus docs; branch `fix/server-build`.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin fix/server-build
```

No `--force`.

- [ ] **Step 3: Open the PR**

```bash
gh pr create --base main --head fix/server-build \
  --title "fix(server): unblock v1 build and verify ReadCore 0.1" \
  --body "Fixes the v1 Result alias conflict, validates the Docker build command, and verifies server endpoints locally. Android/APK is built by CI."
```

- [ ] **Step 4: Watch checks**

```bash
gh pr checks --watch
```

Expected:
- `Server image` — success (Docker build runs
  `cargo test --release && cargo build --release`; does not push on PR).
- `Android APK` — success (unit tests + `assembleDebug`).

- [ ] **Step 5: On failure, fix only the blocker**

Run `gh run view <run-id> --log-failed`, fix the smallest cause, commit, push.
Do not merge or broaden scope.

---

### Task 5: Download and verify the fresh APK

**Files:** create `~/ReadCoreMain/dist/`.

- [ ] **Step 1: Get the Android run and download the artifact**

```bash
RUN_ID=$(gh run list --workflow android.yml --branch fix/server-build --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status
mkdir -p ~/ReadCoreMain/dist
gh run download "$RUN_ID" --name ReadCore-Android-debug --dir ~/ReadCoreMain/dist
```

- [ ] **Step 2: Verify the APK structurally**

```bash
APK=~/ReadCoreMain/dist/app-debug.apk
unzip -t "$APK"
unzip -l "$APK" | grep -E 'classes[0-9]*\.dex|AndroidManifest.xml'
unzip -p "$APK" META-INF/com/android/build/gradle/app-metadata.properties
sha256sum "$APK"
```

Expected: no zip errors, `classes.dex` present, metadata readable, SHA-256
recorded.

- [ ] **Step 3: Device check (optional)**

If `adb devices` shows a device: `adb install -r "$APK"`. Otherwise record it as
the remaining untested item.

---

### Task 6: Report and stop

- [ ] **Step 1: Collect evidence**

Record: branch, commit SHA, PR URL, CI conclusions, server test outputs, release
binary path, APK path + SHA-256.

- [ ] **Step 2: Final report**

Summarize what changed, what passed, what remains untested (Android runtime
without a device), and the exact commands to run the server and install the APK.

- [ ] **Step 3: Stop before merge/publish**

Ask for explicit approval to merge the PR, tag a release, or publish the GHCR
image. Do not merge automatically.

---

## Self-Review

- **Spec coverage:** Sync safety → Task 1; compile fix → Task 2; server/API
  verification → Task 3; Docker/Android CI → Task 4; APK delivery → Task 5; stop
  gate → Task 6.
- **Step scan:** Each step is one command or one edit with a checkable result.
- **Type consistency:** `ApiResult<T>` for handlers; `migrate` stays on
  `std::result::Result<(), sqlx::Error>`; the two `collect` calls match.
- **Review Focus:** Range, auth, symlink, stale timestamps, and metadata fallback
  each have a verification step.
- **Proportion:** One source file changes; the rest is verification and delivery.
