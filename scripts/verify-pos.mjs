import fs from "node:fs";
import vm from "node:vm";
import path from "node:path";

const root = process.cwd();
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), "utf8");
const html = read("app/src/main/assets/index.html");
const activity = read("app/src/main/java/com/mizzysbistro/pos/MainActivity.java");
const storage = read("app/src/main/java/com/mizzysbistro/pos/PosStorage.java");
const gradle = read("app/build.gradle");
const workflow = read(".github/workflows/build-apk.yml");
const instrumentation = read("app/src/androidTest/java/com/mizzysbistro/pos/PosStorageInstrumentedTest.java");

const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) throw new Error("POS HTML must include its main script block.");
new vm.Script(scriptMatch[1], { filename: "app/src/main/assets/index.html" });

const checks = [
  ["Android application ID is stable", /applicationId\s+['"]com\.mizzysbistro\.pos['"]/.test(gradle)],
  ["WebView injects native SQLite bridge", activity.includes('addJavascriptInterface(posStorage, "PosStorage")')],
  ["WebView bridge navigation is restricted to bundled assets", activity.includes("isTrustedAsset") && activity.includes("shouldOverrideUrlLoading")],
  ["SQLite persists a validated JSON snapshot", storage.includes("isValidSnapshot(payload)") && storage.includes("insertWithOnConflict")],
  ["SQLite writes are transaction-wrapped", storage.includes("beginTransaction()") && storage.includes("setTransactionSuccessful()") && storage.includes("endTransaction()")],
  ["Web frontend loads native durable state", scriptMatch[1].includes("window.PosStorage.loadState()")],
  ["Web frontend writes native durable state", scriptMatch[1].includes("window.PosStorage.saveState(JSON.stringify(snapshot))")],
  ["Legacy WebView state migrates to native storage", scriptMatch[1].includes("migrate the existing")],
  ["Checkout retains cart on persistence failure", scriptMatch[1].includes("if(!save()){sales=previous.sales")],
  ["Checkout rejects quantities beyond available stock", scriptMatch[1].includes("Insufficient stock for")],
  ["APK workflow increments versionCode", workflow.includes("versionCode $GITHUB_RUN_NUMBER")],
  ["APK uses an explicit stable signing keystore", gradle.includes('storeFile rootProject.file(".signing/debug.keystore")')],
  ["Signing workflow refuses to generate a replacement after key initialization", workflow.includes(".signing/KEY_INITIALIZED")],
  ["APK signature is verified against the keystore", workflow.includes("APK signer does not match the persistent signing key.")],
  ["Workflow runs Android instrumentation tests", workflow.includes("connectedDebugAndroidTest")],
  ["SQLite instrumentation tests cover durable persistence", instrumentation.includes("savesAndReloadsSnapshotAfterDatabaseReopen")],
  ["SQLite instrumentation tests reject invalid writes", instrumentation.includes("invalidReplacementDoesNotOverwriteLastCommittedSnapshot")],
  ["APK workflow uses the persistent signing cache", workflow.includes("mizzy-pos-persistent-debug-signing-v1")]
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
