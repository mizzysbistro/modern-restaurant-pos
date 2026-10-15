# POS operations and recovery runbook

This runbook describes the repository's current local POS behavior. It is not a substitute for testing the target tablet, printer, external display, and a real backup before a live shift.

## What the app currently does

- Sales, menu/stock state, open table orders, kitchen tickets, cart drafts, and checkout selections are stored locally on the tablet.
- The app does not synchronize with a cloud server or another POS device. The internet indicator reports connectivity, not cloud backup or sync status.
- Payment choices (Cash, Card, GCash, and Other) are recorded with the sale. There is no integrated card terminal or GCash payment gateway in this build; the selected method is not evidence that an external payment has completed.
- Receipt printing opens Android's system print dialog. Printer compatibility and paper settings still need a physical test.
- Customer display mode uses a connected Android presentation display where available. Without one, it switches the same tablet to a full-screen customer view; the cashier must return to POS to interact with the register.

## Start-of-day checks

1. Confirm the app opens without a local database warning.
2. Open Reports and confirm saved sales and totals appear as expected.
3. Confirm available stock and any open table orders match the dining room.
4. Export a fresh full POS backup before making a menu, inventory, or application change.
5. If using a printer or external customer display, verify that the expected Android service/display is connected and test it before live service.

## Export a full POS backup

1. In the app, open **Reports** and choose **Back up all POS data**.
2. Use Android's save dialog to choose a file location. Wait for the save confirmation.
3. Keep the backup file in a secure location separate from the tablet. Keep a second copy if the data is business-critical.
4. Preserve the original file name and timestamp. Do not edit the JSON manually; the app validates the schema and totals on restore.
5. Backups can include customer names, sales, menu, inventory counts, stock adjustments, open orders, kitchen tickets, table occupancy, and the in-progress cart. Treat the file as sensitive business data.

A CSV sales export is useful for analysis but is **not** a substitute for the full JSON backup because it does not restore the menu, stock, open orders, kitchen tickets, or app state.

## Restore a backup

**Restoring replaces the POS state currently on the tablet.** The current app asks for confirmation, but you should still make a backup first.

1. Export the current POS state to a separate file.
2. Open **Reports** and choose **Restore backup**.
3. Select the full JSON backup using Android's file picker.
4. Review the replacement confirmation and proceed only if the selected file and timestamp are correct.
5. After the success message, check the sales count/totals, menu and stock, open table orders, kitchen tickets, and table occupancy.

The importer validates the backup before replacing the state and rolls back the in-memory changes if the local save fails. Legacy backups without the newer cart/open-order fields may still be accepted; missing newer fields are treated as absent state, so verify the result carefully.

If restore shows an error, do not repeatedly retry with different files or clear app storage. Keep both the pre-restore backup and the file you attempted to restore, take note of the error, and stop using the affected state for new sales until it is investigated.

## When a local save/read error appears

1. Stop creating, changing, or charging orders. A UI change that could not be saved must not be treated as committed.
2. Do **not** uninstall the app, clear app data, delete database files, or use an older backup as an experiment on the only tablet.
3. Keep the tablet powered and preserve any error text. If the app can still produce a backup without a storage-read error, export one; do not assume a backup taken after a failed database read is complete.
4. If the database cannot be read, leave the data intact and get help from the maintainer before attempting recovery. Do not create replacement/default state over an unreadable database.

The app is offline-first and local-only, but offline operation is not a substitute for backups. A device failure can still make local-only data unavailable.

## Table orders and checkout

- Use **Tables → Start order** to begin a table order, then use **Save order to Table** to persist it and send/update its kitchen ticket.
- Use **Continue order** to resume a saved open table order. Saving updates that same order rather than creating a second open order.
- Use **Cancel order** only when the order should be discarded; cancellation is confirmed and removes its open table order and kitchen ticket.
- Charge the open order to finalize the same order ID as the completed sale. Stock is consumed at checkout, not when the open order is first saved.
- A completed sale can leave the table marked occupied. Release it from **Tables** only after the guests have left and the table is ready for a new party.

Do not assume a payment is processed just because a method is selected or an order is locally recorded. Confirm external payments through the applicable payment provider/terminal workflow.

## App updates and signing safety

1. Export a full POS backup before updating.
2. Do not uninstall the existing app or clear its data to work around an update error.
3. Android can update the installed app in place only when the application ID and signing certificate are compatible. The application ID is `com.mizzysbistro.pos`.
4. The current workflow reuses a debug signing keystore through a GitHub Actions cache. Cache storage is not a durable signing-key backup; losing or changing the key may prevent an in-place update.
5. The certificate on the physical tablet has not been verified against the current build's signer. Do not assume an update is safe until the installed signer is checked.
6. Test the update on a separate test device/database first. Confirm that old sales remain, a backup restores, and the test device can be updated in place before updating the live tablet.

Never put the signing keystore or passwords in the source repository, logs, or a public artifact. Arrange a secure, durable signing-key backup outside the Actions cache before relying on this build for long-term distribution.

## What CI does not prove

A green workflow means the verifier, automated backup/report/display-state tests, SQLite JVM tests, and Android/instrumentation compilation passed for that commit. CI does not prove that:

- the installed tablet's signing certificate matches the update;
- real business data has been backed up and restored successfully;
- offline checkout has been accepted on the target device with its actual settings;
- a particular printer, paper width, or Android print service works;
- a connected external customer display renders correctly;
- cash/card/GCash transactions are integrated with a real payment processor.

Before live use, perform an end-to-end test on the target hardware with test data, verify a full backup and restore on a test copy, check totals against known transactions, and verify the signing certificate and in-place upgrade.


## Controlled release acceptance checklist

Use the [target-device acceptance checklist](ACCEPTANCE_CHECKLIST.md) to record actual evidence for signer matching, safe in-place updates, representative backup/restore, offline checkout, reporting, kitchen tickets, receipt printing, and customer display behavior. Use a separate test device and non-production test data; do not experiment on the only live register.
