# Mizzy's Bistro Modern Restaurant POS

An offline-first restaurant POS for Android tablets, initially focused on PHP pricing and quick touchscreen checkout.

## Current implementation

- Android package: `com.mizzysbistro.pos`
- Android shell: Java + WebView, loading the bundled `app/src/main/assets/index.html`
- POS UI: HTML/CSS/JavaScript; the repository root `index.html` mirrors the in-app preview
- Durable local state: Android SQLite database through a JavaScript bridge
- Legacy-data migration: on first launch of a SQLite-enabled build, the current WebView localStorage state is loaded and saved into SQLite without intentionally resetting records
- Build automation: GitHub Actions produces a debug APK artifact

The SQLite implementation is currently an **atomic JSON snapshot** of menu, sales, stock, kitchen tickets, table occupancy, and the order counter. It is a safer first step than browser storage alone, but it is not yet a normalized production database. High-volume transaction history, database backups/restoration, open-cart recovery, and full migration tests remain in the roadmap.

## Build an APK

1. Open [GitHub Actions](https://github.com/mizzysbistro/modern-restaurant-pos/actions).
2. Open the latest successful **Build Android APK** run.
3. Download the `modern-restaurant-pos-debug-apk` artifact and extract the ZIP.
4. Install `app-debug.apk` over the existing app only if Android accepts it as an update. Do not uninstall or clear app data as a routine workaround.

Each build increments Android `versionCode`. The workflow currently reuses the Android debug keystore through an Actions cache. That cache is not a guaranteed long-term backup; production distribution needs a securely stored, durable signing key. Also, earlier builds were signed with different ephemeral debug keys. The certificate used by the currently installed tablet app must match the update certificate before an in-place update can be guaranteed.

## Local development checks

The workflow runs `node scripts/verify-pos.mjs` to parse the POS JavaScript and check critical data-safety rules before assembling the APK.

## Important limitations

- Payment methods shown in the app are records of the selected method, not a real card or GCash gateway.
- Printer compatibility must be verified with the actual printer model.
- Cloud backup, multi-device synchronization, staff permissions, refund/void audit flows, and normalized Room/SQLite transaction tables are not yet complete.
- Do not use the current build as the sole recordkeeping system for a live restaurant until backup, restore, migration, checkout, offline, and upgrade acceptance tests have passed.
