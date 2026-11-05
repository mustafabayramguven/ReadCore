# ReadCore Build Recovery — Design

## Intent

Make the latest `origin/main` (`8194591`) buildable and verified: fix the Rust
server compile blocker, prove the server runs, get the Docker/Android CI green,
and produce a fresh installable APK — without disturbing the old local snapshot
or publishing anything before approval.

## Safety first

- Create `backup/local-main-10f3d9c` so the stale checkout is preserved.
- `git fetch origin`, then work on `fix/server-build` based on `origin/main`.
- No force-push, no merge, no `latest` image publish until explicit approval.
- Old local `main` stays untouched until the new branch is verified.

## The fix (minimal)

- In `server/src/v1.rs`, rename the local `type Result<T>` to `ApiResult<T>`.
- Update the handler return types and the two `collect::<Result<...>>` calls.
- Keep `migrate` returning `std::result::Result<(), sqlx::Error>`.
- Silence the existing `unused_must_use` test warning.
- No API or behavior changes.

## Verification

1. `cargo test --release` (expect 6/6), `cargo build --release`.
2. Fetch pinned `epub.js 0.3.93` and `JSZip 3.10.1` into `server/static/vendor/`
   for local web-reader testing (not committed; Docker fetches them in CI).
3. Run the release binary on `127.0.0.1:3435` with a throwaway DB, library, and
   test token.
4. Smoke-test: health, 401 without token, Bearer/Basic auth, scan, books,
   download, `Range: bytes=N-` → 206, progress PUT/GET, OPDS, web assets.
5. Confirm the seeded EPUB is indexed and readable.

## CI and delivery

- Commit the fix, push `fix/server-build`, open a PR.
- Watch both workflows: server Docker build (must go green) and Android build +
  unit tests.
- Download the fresh `ReadCore-Android-debug` artifact to `~/ReadCoreMain/dist/`,
  verify the APK zip and package metadata.
- Report: PR URL, test outputs, binary/APK paths, exact run commands.
- Stop. Merge, tag, and GHCR publish are separate decisions.

## Non-goals

- No Android feature/UI work, no server refactor, no live deployment, no local
  Android SDK/Docker install.
- APK runtime testing is excluded because no device is connected; it is noted as
  the remaining gap.

## Acceptance criteria

- `cargo test --release` passes 6/6.
- Local smoke tests all pass.
- Server workflow and Android workflow both green on the PR.
- Fresh APK downloaded and structurally verified.
- Working tree contains only the intended fix and design/plan docs.
