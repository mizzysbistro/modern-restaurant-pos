# Development status — Mizzy's Bistro POS

Last reviewed: 2026-10-16

## Milestone 0 — Inspect and protect

- [x] Confirm repository and Android application ID `com.mizzysbistro.pos`.
- [x] Confirm current app uses a Java Android Activity with a bundled HTML/CSS/JavaScript WebView UI.
- [x] Confirm Android `PosStorage` bridge persists a JSON snapshot in SQLite and imports legacy browser localStorage.
- [x] Confirm GitHub Actions runs frontend data-safety checks, JVM tests, Android test compilation, APK build, and APK signer fingerprint verification.
- [ ] Establish baseline workflow result and inspect logs for failures.
- [ ] Verify the signing certificate on the APK currently installed on the tablet. This cannot be inferred from repository source alone.
- [ ] Confirm backups and restore with real database data before live use.

## Current architecture and risks

- Package ID: `com.mizzysbistro.pos` (keep stable).
- Frontend: `app/src/main/assets/index.html`; root `index.html` is the web preview.
- Native shell: `app/src/main/java/com/mizzysbistro/pos/MainActivity.java`.
- Persistence: SQLite JSON snapshot through the native JavaScript bridge. This is durable compared with localStorage alone, but it is not yet a normalized transaction database.
- Signing: workflow caches a persistent debug keystore. GitHub Actions cache is not durable backup; loss of the key prevents in-place updates signed with that certificate.
- Current POS logic includes menu, cart, local sales, inventory quantities, basic kitchen tickets, table occupancy, CSV export, and browser receipt printing. This does not yet constitute a production-verified POS.

## Roadmap checklist

### Milestone 1 — Durable data foundation
- [ ] Inspect `PosStorage` implementation and existing frontend bridge usage.
- [ ] Add robust snapshot validation and safe failure handling; never replace valid saved data with empty/default data after parse or storage failure.
- [ ] Add explicit backup/export and restore with validation.
- [ ] Add persistence and legacy-migration tests.
- [ ] Plan a versioned migration from JSON snapshot to normalized SQLite transaction tables without destructive resets.

### Milestone 2 — Core checkout
- [ ] Validate order totals and payment state in one transaction.
- [ ] Prevent duplicate checkout and duplicate sale IDs.
- [ ] Persist immutable sale-line snapshots and audit records.

### Milestone 3 — Offline operation
- [ ] Verify essential checkout and reports with internet disabled.
- [ ] Add explicit connectivity/sync state before implementing cloud sync.

### Milestone 4 — Restaurant operations
- [ ] Improve inventory adjustment audit and availability handling.
- [ ] Implement open table orders and safe table release.
- [ ] Add kitchen ticket state transitions and durable ticket updates.

### Milestone 5 — Receipts and customer display
- [ ] Add native printer integration after confirming target printer protocol/model.
- [ ] Add customer-facing display mode.

### Milestone 6 — Reporting
- [ ] Calculate date-range and payment breakdown reports from persisted sales.
- [ ] Verify totals with known test transactions.

### Milestone 7 — Security and production hardening
- [ ] Audit input validation, bridge exposure, and error recovery.
- [ ] Test database backup/restore and interrupted writes.
- [ ] Add staff access control and void/refund audit flows.

### Milestone 8 — Release engineering
- [ ] Preserve stable signing certificate outside ephemeral cache in a secure durable backup.
- [ ] Verify build signer and installed APK signer match before in-place upgrade.
- [ ] Verify upgrade retains existing sales on a test device.

### Milestone 9 — Acceptance
- [ ] Complete checkout-to-kitchen-to-receipt workflow tests.
- [ ] Test real compatible printer hardware.
- [ ] Document all limitations and operational recovery steps.

## Verification policy

A checklist item is complete only when supported by a repository diff, automated test output, build artifact, or documented physical test. Hardware and installed-app checks unavailable to CI must remain explicitly unverified.
