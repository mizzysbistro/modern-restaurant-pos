# Mizzy’s Bistro POS — Target-Device Acceptance Checklist

This checklist is for the release maintainer to complete on actual hardware. CI success is necessary but does not complete any of these device-only checks.

## Rules before starting

- [ ] Use a separate test tablet or test device with non-production test data. Do not use the only live register for upgrade experiments.
- [ ] Confirm the device is not processing live orders and an authorized person has approved this test.
- [ ] Record the Git commit SHA, APK artifact URL, device model, Android version, and test date.
- [ ] Export a full POS backup before changing app state. Save it outside the app and verify that the file exists and is readable. Treat it as sensitive business data.
- [ ] Do not uninstall the app, clear app data, delete database files, or overwrite the only copy of saved data to work around an error.

## 1. Signing and safe in-place upgrade

- [ ] Download the APK and the `signing-certificate.sha256` artifact from the same successful build run.
- [ ] On the test device, inspect the installed package path with `adb shell pm path com.mizzysbistro.pos`, pull the installed base APK, and inspect it with `apksigner verify --print-certs installed-pos.apk`.
- [ ] Compare the installed APK signer SHA-256 fingerprint with the fingerprint generated for the candidate APK. Record both values. Do not publish keystores, signing passwords, or private signing material.
- [ ] If the fingerprints or application ID differ, **STOP**. Do not attempt an in-place upgrade. Ask the maintainer to establish signing compatibility or install to a separate clean test device without removing the existing app.
- [ ] If and only if signer and application ID are compatible, install the candidate as an in-place update on the test device.
- [ ] Launch the updated app and verify historical sales, menu, stock, table occupancy, open table orders, kitchen tickets, and saved cart context remain as expected.
- [ ] Record the update result. Any lost or changed historical sale is a release blocker.

## 2. Backup and restore using representative test data

- [ ] Record a baseline: sale count, representative sale IDs and totals, inventory counts, occupied tables, open orders, ticket states, and active draft context.
- [ ] Export a fresh full JSON backup; preserve the original file unchanged.
- [ ] Use a separate test copy or test device to restore the backup. Never test replacement restore on the only copy of the data.
- [ ] Confirm the app accepts the valid backup and restores the expected menu, sales, inventory/audit history, open orders, tickets, table occupancy, and cart context.
- [ ] Verify expected counts and totals against the baseline.
- [ ] On a test copy only, attempt a deliberately invalid backup (for example, a malformed timestamp or mismatched sale total) and verify it is rejected without replacing the previously saved state.
- [ ] Record file identity/location securely and the before/after comparison. Do not upload backups or customer data to a public issue or pull request.

## 3. Offline checkout and restart recovery

- [ ] Enable airplane mode or otherwise confirm the device has no internet connection.
- [ ] Add test menu items to a cart, change quantities, choose dine-in or takeaway, and confirm the saved draft recovers after a controlled app restart.
- [ ] Save an open table order, leave the POS screen, reopen the same table order, and verify it has not duplicated.
- [ ] Update a kitchen ticket's ready state, restart the app, and confirm the state persists.
- [ ] Settle a test order with a cash-recorded payment method; verify the app records the sale locally, deducts the correct stock, and creates or updates the expected kitchen/table state.
- [ ] Confirm date-range and payment-method reports show expected values while offline. Do not interpret a selected Card/GCash method as evidence of external payment processing.
- [ ] Only in the test environment, simulate an interrupted or failed write when an approved, safe test procedure exists. Verify the app reports failure and does not silently discard previously committed sales.

## 4. Reports, adjustments, and end-to-end operation

- [ ] Reconcile test sales for the start date, end date, and day boundary using the device's configured time zone.
- [ ] Verify payment totals and net totals after a partial refund and a void in test data; confirm audit reason and staff-entered fields are recorded.
- [ ] Confirm a fully refunded or voided sale cannot be adjusted again and a refund cannot exceed the remaining amount.
- [ ] Complete the test workflow: menu → cart → saved table order (if dine-in) → kitchen ticket → checkout → receipt → report.
- [ ] Verify quantities, prices, total, stock deduction, order ID, ticket ID, and receipt agree.
- [ ] Confirm a fresh order never reuses an existing sale, open-order, or kitchen-ticket ID.

## 5. Physical printer and customer display

- [ ] Record printer brand/model, connection type, Android print service, and paper width.
- [ ] Print a test receipt and verify legibility, amounts, item names, clipping, and paper formatting.
- [ ] Connect the actual customer-facing display and verify the customer view opens, updates from test orders, handles display disconnection/reconnection, and returns safely to the cashier view.
- [ ] If no compatible printer or external display is required or available, record that explicitly rather than marking an unperformed test as passed.

## Results and evidence

Fill this section only after the corresponding tests have been performed.

| Field | Evidence |
|---|---|
| Commit SHA / APK build URL | |
| Device model / Android version | |
| Test date / tester | |
| Installed signer SHA-256 | |
| Candidate APK signer SHA-256 | |
| Backup identifier and secure location | |
| Baseline vs restored sales count/totals | |
| In-place upgrade result | |
| Offline checkout/report result | |
| Printer model / result | |
| Customer display / result | |
| Defects found and follow-up issue/commit | |

Use only these final statuses for each section: **PASS** (performed and evidence recorded), **FAIL** (unexpected behavior; release blocker until fixed/retested), or **NOT RUN / BLOCKED** (not performed or unavailable). Do not mark this checklist complete based solely on CI.

## Release decision

- [ ] All required acceptance sections are PASS with recorded evidence.
- [ ] All failures have been fixed and the relevant regression and CI checks rerun on the final candidate SHA.
- [ ] The signing key has a secure, durable backup outside GitHub Actions cache; no secret key material is committed to the repository.
- [ ] The maintainer explicitly reviewed known limitations and approved the tested APK for the intended environment.

Until these requirements are met, the application remains **awaiting target-device acceptance** and must not be described as fully production-ready.
