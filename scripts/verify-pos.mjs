import fs from "node:fs";
import vm from "node:vm";
import path from "node:path";

const root = process.cwd();
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), "utf8");
const html = read("app/src/main/assets/index.html");
const backupSchema = read("app/src/main/assets/backup-schema.js");
const activity = read("app/src/main/java/com/mizzysbistro/pos/MainActivity.java");
const storage = read("app/src/main/java/com/mizzysbistro/pos/PosStorage.java");
const gradle = read("app/build.gradle");
const workflow = read(".github/workflows/build-apk.yml");
const instrumentation = read("app/src/androidTest/java/com/mizzysbistro/pos/PosStorageInstrumentedTest.java");
const unitTests = read("app/src/test/java/com/mizzysbistro/pos/PosStorageUnitTest.java");

const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) throw new Error("POS HTML must include its main script block.");
new vm.Script(scriptMatch[1], { filename: "app/src/main/assets/index.html" });

const checks = [
  ["Android application ID is stable", /applicationId\s+['"]com\.mizzysbistro\.pos['"]/.test(gradle)],
  ["WebView injects native SQLite bridge", activity.includes('addJavascriptInterface(posStorage, "PosStorage")')],
  ["WebView bridge navigation is restricted to bundled assets", activity.includes("isTrustedAsset") && activity.includes("shouldOverrideUrlLoading")],
  ["SQLite persists a validated JSON snapshot", storage.includes("isValidSnapshot(payload)") && storage.includes("insertWithOnConflict")],
  ["SQLite writes are transaction-wrapped", storage.includes("beginTransaction()") && storage.includes("setTransactionSuccessful()") && storage.includes("endTransaction()")],
  ["SQLite schema stores completed sales in a separate indexed table", storage.includes("DATABASE_VERSION = 2") && storage.includes("TABLE_SALES = \"pos_sales\"") && storage.includes("idx_pos_sales_sold_at") && storage.includes("idx_pos_sales_payment")],
  ["SQLite v1 migration preserves existing sales before clearing snapshot collection", storage.includes("migrateV1SalesIntoTable(db)") && storage.includes('root.put("sales", new JSONArray())') && storage.includes("Could not migrate an existing POS sale")],
  ["Routine saves preserve prior sales; explicit restore replaces them", storage.includes("persistState(payload, false)") && storage.includes("persistState(payload, true)") && scriptMatch[1].includes("save({replaceSales:true})")],
  ["Robolectric tests cover v1 migration and history preservation", unitTests.includes("migratesV1SnapshotWithoutLosingSales") && unitTests.includes("normalSaveDoesNotDeleteHistoricalSales") && unitTests.includes("explicitRestoreReplacesSales")],
  ["Web frontend loads native durable state", scriptMatch[1].includes("window.PosStorage.loadState()")],
  ["Web frontend writes native durable state", scriptMatch[1].includes("window.PosStorage.saveState(payload)") && scriptMatch[1].includes("window.PosStorage.restoreState(payload)")],
  ["Legacy WebView state migrates to native storage", scriptMatch[1].includes("migrate the existing")],
  ["Checkout retains cart on persistence failure", scriptMatch[1].includes("if(!save()){sales=previous.sales")],
  ["Checkout rejects quantities beyond available stock", scriptMatch[1].includes("Insufficient stock for")],
  ["APK workflow increments versionCode", workflow.includes("versionCode $GITHUB_RUN_NUMBER")],
  ["APK uses an explicit stable signing keystore", gradle.includes('storeFile rootProject.file(".signing/debug.keystore")')],
  ["Signing workflow refuses to generate a replacement after key initialization", workflow.includes(".signing/KEY_INITIALIZED")],
  ["APK signature is verified against the keystore", workflow.includes("APK signer does not match the persistent signing key.")],
  ["Workflow runs SQLite JVM tests", workflow.includes("testDebugUnitTest")],
  ["Workflow compiles Android instrumentation tests", workflow.includes("assembleDebugAndroidTest")],
  ["SQLite instrumentation tests cover durable persistence", instrumentation.includes("savesAndReloadsSnapshotAfterDatabaseReopen")],
  ["SQLite instrumentation tests reject invalid writes", instrumentation.includes("invalidReplacementDoesNotOverwriteLastCommittedSnapshot")],
  ["Native SQLite read failures are distinct from first-launch state", storage.includes("LOAD_ERROR") && storage.includes("hasStoredSales(db)") && storage.includes("return LOAD_ERROR;")],
  ["Frontend blocks writes after native storage read failure", scriptMatch[1].includes("_posStorageError") && scriptMatch[1].includes("nativeStorageUnavailable") && scriptMatch[1].includes("Changes are disabled to protect saved sales")],
  ["JVM tests cover corrupt snapshots and orphaned sale history", unitTests.includes("loadStateReturnsExplicitErrorWhenStoredPayloadIsCorrupt") && unitTests.includes("missingSnapshotWithHistoricalSalesIsNotTreatedAsFirstLaunch")],
  ["Instrumentation tests cover orphaned sale history", instrumentation.includes("loadFailureIsExplicitAndDoesNotLookLikeFirstLaunch")],
  ["Normal POS saves preserve immutable historical sale content", storage.includes("saleMatchesExisting") && storage.includes("jsonValuesEqual(existing, sale)")],
  ["Native state validation prevents sale ID reuse", storage.includes("id >= nextIdLong")],
  ["JVM and instrumentation tests cover duplicate order IDs", unitTests.includes("normalSaveCannotRewriteAnExistingSaleId") && unitTests.includes("rejectsNextIdThatWouldReuseAnExistingSaleId") && instrumentation.includes("normalSaveCannotRewriteAnExistingSaleId") && instrumentation.includes("rejectsNextIdThatWouldReuseAnExistingSaleId")],
  ["Frontend repairs stale next-order counters from saved sales", html.includes("maxSavedSaleId+1") && html.includes("nativeState&&nextId!==nativeState.nextId")],
  ["APK workflow uses the persistent signing cache", workflow.includes("mizzy-pos-persistent-debug-signing-v1")],
  ["POS reports expose full-data backup and restore controls", scriptMatch[1].includes('id="exportBackup"') && scriptMatch[1].includes('id="importBackup"')],
  ["Backup export includes format/version and all POS state", scriptMatch[1].includes('format:"mizzy-bistro-pos-backup"') && scriptMatch[1].includes("schemaVersion:1") && scriptMatch[1].includes("state:{products,sales,stock,tickets,tables:occupied,nextId}")],
  ["Backup import validates contents before replacement", scriptMatch[1].includes("window.MizzyBackup.validateBackupDocument(doc)") && backupSchema.includes("The backup contains an invalid sale record.")],
  ["Backup restore warns before replacing current data", scriptMatch[1].includes("It will replace the POS menu, sales, stock, kitchen tickets and table status")],
  ["Backup restore rolls back in-memory state after save failure", scriptMatch[1].includes("Existing data was kept.")],
  ["Native backup export validates the document", activity.includes("isValidBackupDocument") && activity.includes("mizzy-bistro-pos-backup")],
  ["Native backup export uses Android document picker", activity.includes("Intent.ACTION_CREATE_DOCUMENT") && activity.includes("REQUEST_CREATE_BACKUP")],
  ["WebView supports picking restore file", activity.includes("onShowFileChooser") && activity.includes("REQUEST_FILE_CHOOSER")],
  ["Backup import limits file size", scriptMatch[1].includes("const MAX_BACKUP_BYTES=10*1024*1024") && scriptMatch[1].includes("file.size>MAX_BACKUP_BYTES")],
  ["User-controlled menu content is HTML-escaped", scriptMatch[1].includes("const esc=value=>") && scriptMatch[1].includes("esc(p.name)") && scriptMatch[1].includes("esc(p.cat)")],
  ["Kitchen ticket and receipt text is HTML-escaped", scriptMatch[1].includes("esc(x.name)") && scriptMatch[1].includes("esc(s.payment)")],
  ["CSV export uses native Android file picker when available", scriptMatch[1].includes('exportTextFile("mizzy-bistro-sales.csv","text/csv",csv)')],
  ["Backup schema validates table numbers", backupSchema.includes("sale.table > 12") && backupSchema.includes("ticket.table > 12")],
  ["Backup schema validates explicit kitchen ticket status", backupSchema.includes('typeof ticket.done !== "boolean"')],
  ["POS displays honest offline and local-only storage status", scriptMatch[1].includes("Offline mode · local save only") && scriptMatch[1].includes("cloud synchronization is not configured")],
  ["Table occupancy counts derive from saved table state", scriptMatch[1].includes("occupiedCount") && scriptMatch[1].includes("data-release-table")],
  ["Receipt printing uses Android PrintManager in the native app", activity.includes("PrintManager") && activity.includes("createPrintDocumentAdapter") && scriptMatch[1].includes("window.PosFiles.printReceipt(JSON.stringify(s))")],
  ["Receipt HTML escapes names and payment details", activity.includes("escapeHtml(name)") && activity.includes("escapeHtml(payment)")]
];

const failed = checks.filter(([, passed]) => !passed);
for (const [label, passed] of checks) {
  process.stdout.write((passed ? "PASS " : "FAIL ") + label + "\n");
}
if (failed.length) {
  process.stderr.write(`\n${failed.length} POS verification check(s) failed.\n`);
  process.exitCode = 1;
} else {
  process.stdout.write(`\nAll ${checks.length} POS verification checks passed; JavaScript syntax parsed successfully.\n`);
}
