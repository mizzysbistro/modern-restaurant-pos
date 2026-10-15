# Project Status and Acceptance Checklist

Last updated: 2026-10-15

## Architecture baseline

- [x] Confirmed repository and current app identity: `com.mizzysbistro.pos`.
- [x] Confirmed current UI runs as bundled HTML/JavaScript inside an Android WebView.
- [x] Confirmed Android build workflow Run #6 succeeded before the SQLite bridge was added.
- [x] Confirmed Run #6 published a `modern-restaurant-pos-debug-apk` artifact.
- [ ] Convert temporary snapshot persistence into a normalized, versioned transaction database.
- [ ] Add instrumented Android database migration, durability, and recovery tests.
- [ ] Verify the currently installed tablet APK's signing certificate before attempting an in-place upgrade.

## Completed in current development pass (awaiting CI verification)

- [x] Added `PosStorage.java`, a native SQLite helper using a transactional single-row state snapshot.
- [x] Added snapshot validation for the expected POS state keys and a maximum payload size.
- [x] Connected the WebView to the native storage bridge.
- [x] Restricted top-level WebView navigation and JavaScript prompts to bundled app assets.
- [x] Added automatic first-run migration from existing WebView localStorage to SQLite.
- [x] Made checkout roll back in-memory transaction changes and keep the cart if durable save fails.
- [x] Added a checkout guard that prevents stock from becoming negative due to an oversized cart quantity.
- [x] Added `scripts/verify-pos.mjs` to parse the POS script and enforce critical repository checks.
- [x] Added the verification script to the APK CI workflow.
- [x] Synced the repository-root HTML preview to the bundled HTML.
- [x] Documented the current architecture and known limitations in README.

## Verification results

- Baseline Android build: **PASS** — GitHub Actions Run #6 at commit `99a35f1f15fc93e3d4a17801a2128d922767ccca`. This predates the SQLite bridge.
- Current JS verification + Android build: **PENDING** — the next CI run must pass before this change is called verified.
- SQLite upgrade/migration on a physical Android tablet: **NOT YET VERIFIED**.
- Offline checkout persistence after closing/reopening the app: **NOT YET VERIFIED**.
- Database backup and restore: **NOT IMPLEMENTED**.
- Bluetooth/USB/network printer hardware: **NOT YET VERIFIED**.
- Live card/GCash processing: **NOT IMPLEMENTED**.
- Multi-device/cloud synchronization: **NOT IMPLEMENTED**.

## Release-signing warning

The Actions workflow currently uses a cached Android debug keystore. The cache has been seeded by a successful run, but GitHub Actions caches are not a guaranteed long-term signing-key vault. Before production use, configure a durable signing key in encrypted GitHub Actions secrets or another secure signing arrangement.

Signing fingerprints for historical artifacts were inspected:
- Run #3 fingerprint: `0D:A3:7F:E5:E4:7D:49:14:A5:8B:02:BF:91:88:47:EB:50:77:AF:CA:5D:C2:6F:A3:E2:D3:F7:F9:01:58:9A:69`
- Run #6 fingerprint: `5E:3A:01:79:F0:CB:EE:2A:AF:AC:07:13:EF:9C:68:A8:FB:6A:9C:66:60:EC:E0:5F:4E:2B:2B:44:7F:78:56:28`

These fingerprints **do not match**. Because the tablet's currently installed build has not been identified, do not assume the latest APK can update it in place. A mismatched signing certificate causes Android to reject an APK update. Do not uninstall or clear app data just to work around this.

## Next tasks

1. Wait for the current workflow to validate the JavaScript checks and compile the app.
2. Fix any CI failure before continuing.
3. Add automated tests for the native SQLite snapshot and legacy localStorage migration.
4. Replace the snapshot with normalized sales/order tables and explicit schema migrations before large-scale real-world use.
5. Implement an export/restore workflow and test data recovery.
6. Establish durable release signing and compare the installed app's certificate before an update.
