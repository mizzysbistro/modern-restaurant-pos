import fs from "node:fs";
import vm from "node:vm";
import path from "node:path";

const root = process.cwd();
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), "utf8");
const html = read("app/src/main/assets/index.html");
const backupSchema = read("app/src/main/assets/backup-schema.js");
const reporting = read("app/src/main/assets/reporting.js");
const reportingTests = read("tests/reporting.test.cjs");
const activity = read("app/src/main/java/com/mizzysbistro/pos/MainActivity.java");
const customerDisplayState = read("app/src/main/java/com/mizzysbistro/pos/CustomerDisplayState.java");
const customerDisplayTests = read("app/src/test/java/com/mizzysbistro/pos/CustomerDisplayStateTest.java");
const storage = read("app/src/main/java/com/mizzysbistro/pos/PosStorage.java");
const gradle = read("app/build.gradle");
const workflow = read(".github/workflows/build-apk.yml");
const instrumentation = read("app/src/androidTest/java/com/mizzysbistro/pos/PosStorageInstrumentedTest.java");
const unitTests = read("app/src/test/java/com/mizzysbistro/pos/PosStorageUnitTest.java");
const backupTests = read("tests/backup-schema.test.cjs");

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
  ["Checkout retains cart on persistence failure", scriptMatch[1].includes("if(!save()){") && scriptMatch[1].includes("sales=previous.sales") && scriptMatch[1].includes("cart=previous.cart")],
  ["Kitchen ticket is rolled back if the ready state cannot be saved", scriptMatch[1].includes("const index=Number(x.dataset.done),ticket=tickets[index]") && scriptMatch[1].includes("if(!save()){ticket.done=wasDone;renderKitchen();return}")],
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
  ["Native sale persistence validates line items against the paid total", storage.includes("itemsTotal += price * quantity") && storage.includes("if (totalCents != itemsCents) return false") && storage.includes("items.length() < 1")],
  ["Backup restore rejects totals that do not match sale line items", backupSchema.includes("The backup sale total does not match its items.") && backupSchema.includes("sale.items.length < 1")],
  ["Backup and native sale-total validation have regression tests", backupTests.includes("rejects a sale total that does not equal its line items") && unitTests.includes("rejectsSaleWhenTotalDoesNotMatchItemSubtotal") && instrumentation.includes("rejectsSaleWhenTotalDoesNotMatchItemSubtotal")],
  ["Inventory adjustment history is saved with POS state and full backups", scriptMatch[1].includes("stockAdjustments") && scriptMatch[1].includes('recordStockAdjustment(id,before,stock[id],"Sale #"+sale.id,"Sale")') && scriptMatch[1].includes('recordStockAdjustment(id,previous,n,reason,"Manual")') && scriptMatch[1].includes("Recent stock adjustments")],
  ["Native and backup validators bound inventory adjustment history", storage.includes("isValidStockAdjustments(root)") && storage.includes("adjustments.length() > 10000") && backupSchema.includes("invalid stock adjustment history")],
  ["Inventory adjustment persistence and validation have regression tests", unitTests.includes("stockAdjustmentHistoryPersistsWithState") && unitTests.includes("rejectsInvalidStockAdjustmentHistory") && instrumentation.includes("stockAdjustmentHistoryPersistsWithState") && instrumentation.includes("rejectsInvalidStockAdjustmentHistory") && backupTests.includes("rejects stock adjustment entries with inconsistent quantities")],
  ["Native table occupancy references the matching persisted dine-in sale", storage.includes("salesById.get((long) rawIdValue)") && storage.includes('!"Dine in".equals(tableSale.optString("type", ""))') && storage.includes("tableSale.optInt(\"table\", -1) != tableNumber")],
  ["Backup restore rejects table occupancy inconsistent with saved orders", backupSchema.includes("const salesById = new Map()") && backupSchema.includes('sale.type !== "Dine in"') && backupTests.includes("rejects table occupancy that points to no saved sale") && backupTests.includes("rejects table occupancy outside the configured table range")],
  ["JVM and device tests cover invalid table/order references", unitTests.includes("rejectsTableOccupancyWithoutMatchingDineInSale") && instrumentation.includes("rejectsTableOccupancyWithoutMatchingDineInSale")],
  ["Routine POS saves preserve existing inventory audit entries", storage.includes("stockAdjustmentHistoryPreserved(db, root.optJSONArray(\"stockAdjustments\"))") && storage.includes("oldLength != 10000") && unitTests.includes("normalSaveCannotEraseStockAdjustmentHistory") && instrumentation.includes("normalSaveCannotEraseStockAdjustmentHistory")],
  ["JVM tests cover menu, inventory, kitchen tickets and table occupancy across database reopen", unitTests.includes("savesAndReloadsMenuStockTicketsAndTableOccupancy")],
  ["APK workflow uses the persistent signing cache", workflow.includes("mizzy-pos-persistent-debug-signing-v1")],
  ["POS reports expose full-data backup and restore controls", scriptMatch[1].includes('id="exportBackup"') && scriptMatch[1].includes('id="importBackup"')],
  ["Backup export includes format/version, inventory audit, open orders and recoverable cart", scriptMatch[1].includes('format:"mizzy-bistro-pos-backup"') && scriptMatch[1].includes("schemaVersion:1") && scriptMatch[1].includes("state:{products,sales,stock,tickets,tables:occupied,nextId,stockAdjustments,openCart:serializeOpenCart(),openOrders,activeOpenOrderId,orderType,tableNo,payment,customer}")],
  ["Backup import validates contents before replacement", scriptMatch[1].includes("window.MizzyBackup.validateBackupDocument(doc)") && backupSchema.includes("The backup contains an invalid sale record.")],
  ["Backup restore warns before replacing saved data, open orders and current cart", scriptMatch[1].includes("It will replace the menu, sales, stock, kitchen tickets, table status, open orders and current cart on this tablet")],
  ["Backup restore rolls back in-memory state after save failure", scriptMatch[1].includes("Existing data was kept.")],
  ["Native backup export validates the document", activity.includes("isValidBackupDocument") && activity.includes("mizzy-bistro-pos-backup")],
  ["Native backup export uses Android document picker", activity.includes("Intent.ACTION_CREATE_DOCUMENT") && activity.includes("REQUEST_CREATE_BACKUP")],
  ["WebView supports picking restore file", activity.includes("onShowFileChooser") && activity.includes("REQUEST_FILE_CHOOSER")],
  ["Backup import limits file size", scriptMatch[1].includes("const MAX_BACKUP_BYTES=10*1024*1024") && scriptMatch[1].includes("file.size>MAX_BACKUP_BYTES")],
  ["User-controlled menu content is HTML-escaped", scriptMatch[1].includes("const esc=value=>") && scriptMatch[1].includes("esc(p.name)") && scriptMatch[1].includes("esc(p.cat)")],
  ["Kitchen ticket and receipt text is HTML-escaped", scriptMatch[1].includes("esc(x.name)") && scriptMatch[1].includes("esc(s.payment)")],
  ["CSV export uses native Android file picker when available", scriptMatch[1].includes('window.PosFiles.exportTextFile(filename,"text/csv",csv)')],
  ["Backup schema validates table numbers", backupSchema.includes("sale.table > 12") && backupSchema.includes("ticket.table > 12")],
  ["Backup schema validates explicit kitchen ticket status", backupSchema.includes('typeof ticket.done !== "boolean"')],
  ["POS displays honest offline and local-only storage status", scriptMatch[1].includes("Offline mode · local save only") && scriptMatch[1].includes("cloud synchronization is not configured")],
  ["Table occupancy counts derive from saved table state", scriptMatch[1].includes("occupiedCount") && scriptMatch[1].includes("data-release-table")],
  ["Receipt printing uses Android PrintManager in the native app", activity.includes("PrintManager") && activity.includes("createPrintDocumentAdapter") && scriptMatch[1].includes("window.PosFiles.printReceipt(JSON.stringify(s))")],
  ["Receipt HTML escapes names and payment details", activity.includes("escapeHtml(name)") && activity.includes("escapeHtml(payment)")],
  ["Date-range report uses local inclusive date boundaries", reporting.includes("parseLocalDateKey") && reporting.includes("endExclusive.setDate(endExclusive.getDate() + 1)") && scriptMatch[1].includes("MizzyReports.summarizeSales(sales,startDate,endDate)")],
  ["Reports expose payment-method totals and separate all-sales export", scriptMatch[1].includes("Payment breakdown") && scriptMatch[1].includes("exportSalesCsv(reportRows") && scriptMatch[1].includes("exportSalesCsv(sales")],
  ["Reporting helper regression tests cover date boundaries, totals, and invalid ranges", reportingTests.includes("includes both boundary dates") && reportingTests.includes("groups payment totals") && reportingTests.includes("rejects invalid calendar dates")],
  ["Open cart and checkout context persist in native state and backups", scriptMatch[1].includes("openCart:serializeOpenCart()") && scriptMatch[1].includes("orderType,tableNo,payment,customer") && storage.includes("isValidOpenCart(root, products)") && backupSchema.includes("The backup contains an invalid open cart.")],
  ["Checkout clears the draft before saving and restores it after a failed write", scriptMatch[1].includes("activeOpenOrderId=null;cart.clear();") && scriptMatch[1].includes("if(!save()){") && scriptMatch[1].includes("cart=previous.cart;")],
  ["Native/device tests cover draft recovery and invalid cart lines", unitTests.includes("openCartAndCheckoutContextPersistAcrossDatabaseReopen") && instrumentation.includes("openCartAndCheckoutContextPersistAcrossDatabaseReopen") && unitTests.includes("invalidOpenCartCannotReplacePreviouslySavedDraft") && instrumentation.includes("invalidOpenCartCannotReplacePreviouslySavedDraft") && backupTests.includes("rejects a saved cart line for a nonexistent menu item")],
  ["Native SQLite validates persisted open table orders and table ownership", storage.includes("openOrdersById") && storage.includes("mappedTableOrder") && storage.includes("activeOpenOrderId")],
  ["Backups validate open table orders, occupancy and active context", backupSchema.includes("invalid open table orders") && backupSchema.includes("An open table order must own its table occupancy") && backupTests.includes("accepts an open table order with matching occupancy and active context")],
  ["JVM and device tests cover open-table persistence and table ownership failures", unitTests.includes("openTableOrderPersistsAndOwnsTableOccupancy") && instrumentation.includes("openTableOrderPersistsAndOwnsTableOccupancy") && unitTests.includes("rejectsOpenTableOrderThatDoesNotOwnItsTable") && instrumentation.includes("rejectsOpenTableOrderThatDoesNotOwnItsTable")],
  ["Table overview supports starting, continuing and cancelling persistent table orders", scriptMatch[1].includes("function saveTableOrder()") && scriptMatch[1].includes("data-cancel-open-order") && scriptMatch[1].includes("Continue order")],
  ["Checkout finalizes the same open-table order ID without duplicating kitchen tickets", scriptMatch[1].includes("saleId=currentOpenOrder?Number(currentOpenOrder.id):nextId") && scriptMatch[1].includes("openOrders=openOrders.filter(order=>Number(order.id)!==Number(currentOpenOrder.id))") && scriptMatch[1].includes("const finalTicket={...sale,done:wasDone}")],
  ["Completed occupied tables cannot be overwritten by direct checkout", scriptMatch[1].includes('This table is still occupied by a completed order. Release it in Tables before starting another order.')],
  ["Native customer display validates payloads and manages Android Presentation lifecycle", activity.includes("hasCustomerDisplay") && activity.includes("setCustomerDisplayEnabled") && activity.includes("customerDisplayListener") && activity.includes("new CustomerDisplayPresentation") && customerDisplayState.includes("MAX_JSON_CHARS")],
  ["Customer display provides external-screen handoff and same-device fallback", html.includes('id="customerDisplayOverlay"') && html.includes('id="customerDisplayToggle"') && scriptMatch[1].includes("function startCustomerDisplay()") && scriptMatch[1].includes("function renderCustomerDisplay(") && scriptMatch[1].includes("window.PosFiles.updateCustomerDisplay")],
  ["Customer display parser tests cover totals, unsafe inputs and payload bounds", customerDisplayTests.includes("rejectsTotalsThatDoNotMatchLineItems") && customerDisplayTests.includes("rejectsUnsafeItemAndContextValues") && customerDisplayTests.includes("rejectsMalformedAndOversizedPayloads")],
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
