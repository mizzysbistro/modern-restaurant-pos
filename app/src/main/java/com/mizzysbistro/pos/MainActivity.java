package com.mizzysbistro.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintJob;
import android.print.PrintManager;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_CREATE_BACKUP = 4102;
    private static final int REQUEST_FILE_CHOOSER = 4103;
    private static final int REQUEST_CREATE_EXPORT = 4104;
    private static final int MAX_BACKUP_CHARS = 10 * 1024 * 1024;

    private WebView webView;
    private PosStorage posStorage;
    private String pendingBackupJson;
    private String pendingExportText;
    private String pendingExportName;
    private String pendingExportMime;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final List<WebView> activePrintViews = new ArrayList<>();
    private boolean activityDestroyed;

    private boolean isTrustedAsset(String url) {
        return url != null && url.startsWith("file:///android_asset/");
    }

    private boolean isValidBackupDocument(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_BACKUP_CHARS) return false;
        try {
            JSONObject root = new JSONObject(json);
            if (!"mizzy-bistro-pos-backup".equals(root.optString("format")) ||
                root.optInt("schemaVersion", -1) != 1 ||
                !"com.mizzysbistro.pos".equals(root.optString("appId"))) return false;
            JSONObject state = root.optJSONObject("state");
            return state != null
                && state.optJSONArray("products") != null
                && state.optJSONArray("sales") != null
                && state.optJSONArray("tickets") != null
                && state.optJSONObject("stock") != null
                && state.optJSONObject("tables") != null
                && state.opt("nextId") instanceof Number;
        } catch (JSONException error) {
            return false;
        }
    }

    private static final class ReceiptDocument {
        final int saleId;
        final String html;

        ReceiptDocument(int saleId, String html) {
            this.saleId = saleId;
            this.html = html;
        }
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String formatMoney(double amount) {
        NumberFormat format = NumberFormat.getNumberInstance(new Locale("en", "PH"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₱" + format.format(amount);
    }

    private ReceiptDocument createReceiptDocument(String saleJson) {
        if (saleJson == null || saleJson.length() > MAX_BACKUP_CHARS) return null;
        try {
            JSONObject sale = new JSONObject(saleJson);
            int id = sale.optInt("id", -1);
            String type = sale.optString("type", "");
            String payment = sale.optString("payment", "");
            String time = sale.optString("time", "");
            int table = sale.optInt("table", -1);
            double total = sale.optDouble("total", Double.NaN);
            JSONArray items = sale.optJSONArray("items");

            if (id < 1 || time.isEmpty() || time.length() > 80 ||
                payment.isEmpty() || payment.length() > 80 ||
                !(type.equals("Dine in") || type.equals("Takeaway")) ||
                !Double.isFinite(total) || total < 0 || total > 1000000000d ||
                items == null || items.length() == 0 || items.length() > 500) {
                return null;
            }
            if (type.equals("Dine in") && (table < 1 || table > 12)) return null;
            if (type.equals("Takeaway") && !sale.isNull("table")) return null;

            StringBuilder rows = new StringBuilder();
            double calculatedTotal = 0d;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) return null;
                String name = item.optString("name", "");
                Object qtyObject = item.opt("qty");
                Object priceObject = item.opt("price");
                if (name.trim().isEmpty() || name.length() > 200 ||
                    !(qtyObject instanceof Number) || !(priceObject instanceof Number)) {
                    return null;
                }
                int quantity = ((Number) qtyObject).intValue();
                double price = ((Number) priceObject).doubleValue();
                if (quantity < 1 || quantity > 100000 ||
                    !Double.isFinite(price) || price < 0 || price > 100000000d) {
                    return null;
                }
                calculatedTotal += quantity * price;
                rows.append("<tr><td><strong>").append(escapeHtml(name))
                    .append("</strong><small>").append(quantity).append(" × ")
                    .append(formatMoney(price)).append(" each</small></td><td class=\"amount\">")
                    .append(formatMoney(quantity * price)).append("</td></tr>");
            }
            if (!Double.isFinite(calculatedTotal) || Math.abs(calculatedTotal - total) > 0.02d) {
                return null;
            }

            String customer = sale.optString("customer", "");
            if (customer.length() > 200) customer = "";
            String location = type.equals("Dine in") ? "Table " + table : "Takeaway";
            String customerLine = customer.trim().isEmpty() ? "" :
                "<p>Customer: " + escapeHtml(customer) + "</p>";
            String html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Receipt #" + id + "</title><style>" +
                "@page{margin:4mm}body{font:12px/1.4 monospace;color:#111;max-width:76mm;margin:0 auto;padding:4mm}" +
                "h2,p{text-align:center;margin:5px 0}h2{font-size:18px}small{display:block;color:#333}" +
                "hr{border:0;border-top:1px dashed #777;margin:10px 0}table{width:100%;border-collapse:collapse}" +
                "td{padding:5px 0;vertical-align:top}.amount{text-align:right;white-space:nowrap}" +
                ".grand{display:flex;justify-content:space-between;font-size:16px;font-weight:bold;margin-top:8px}" +
                "</style></head><body><h2>MIZZY’S BISTRO</h2><p>POS receipt · locally recorded</p>" +
                "<p>Receipt #" + id + "<br>" + escapeHtml(time) + "<br>" + location + "</p>" +
                customerLine + "<hr><table><tbody>" + rows + "</tbody></table><hr>" +
                "<p>VAT included in menu prices (12%)</p><p>Payment method: " + escapeHtml(payment) +
                "</p><div class=\"grand\"><span>TOTAL</span><span>" + formatMoney(total) +
                "</span></div><p style=\"margin-top:14px\">Thank you! Please come again.</p></body></html>";
            return new ReceiptDocument(id, html);
        } catch (JSONException | RuntimeException error) {
            return null;
        }
    }

    private void beginNativePrint(ReceiptDocument receipt) {
        if (receipt == null) {
            Toast.makeText(this, "Receipt data is invalid. The saved sale was not changed.",
                Toast.LENGTH_LONG).show();
            return;
        }
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(this, "Android printing is unavailable on this device.",
                Toast.LENGTH_LONG).show();
            return;
        }

        WebView receiptView = new WebView(this);
        receiptView.getSettings().setJavaScriptEnabled(false);
        receiptView.getSettings().setDomStorageEnabled(false);
        receiptView.getSettings().setAllowFileAccess(false);
        receiptView.getSettings().setAllowContentAccess(false);
        receiptView.setWebViewClient(new WebViewClient() {
            private boolean printStarted;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (printStarted || activityDestroyed) return;
                printStarted = true;
                try {
                    activePrintViews.add(receiptView);
                    PrintJob job = printManager.print(
                        "Mizzy's Bistro Receipt #" + receipt.saleId,
                        receiptView.createPrintDocumentAdapter("Receipt-" + receipt.saleId),
                        new PrintAttributes.Builder()
                            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build()
                    );
                    Toast.makeText(MainActivity.this, "Android print dialog opened.",
                        Toast.LENGTH_SHORT).show();
                    monitorPrintJob(job, receiptView);
                } catch (RuntimeException error) {
                    activePrintViews.remove(receiptView);
                    receiptView.destroy();
                    Toast.makeText(MainActivity.this, "Could not open the Android print dialog.",
                        Toast.LENGTH_LONG).show();
                }
            }
        });
        receiptView.loadDataWithBaseURL(null, receipt.html, "text/html", "UTF-8", null);
    }

    private void monitorPrintJob(PrintJob job, WebView receiptView) {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable monitor = new Runnable() {
            @Override
            public void run() {
                if (activityDestroyed || job == null ||
                    job.isCompleted() || job.isCancelled() || job.isFailed()) {
                    activePrintViews.remove(receiptView);
                    try {
                        receiptView.stopLoading();
                        receiptView.destroy();
                    } catch (RuntimeException ignored) {
                        // The view may already have been detached during Activity teardown.
                    }
                } else {
                    handler.postDelayed(this, 2000);
                }
            }
        };
        handler.postDelayed(monitor, 2000);
    }

    private String createBackupFileName() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date());
        return "mizzy-bistro-backup-" + timestamp + ".json";
    }

    private void beginBackupExport(String json) {
        pendingBackupJson = json;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, createBackupFileName());
        try {
            startActivityForResult(intent, REQUEST_CREATE_BACKUP);
        } catch (ActivityNotFoundException error) {
            pendingBackupJson = null;
            Toast.makeText(this, "No file picker is available to save a backup.", Toast.LENGTH_LONG).show();
        }
    }

    private void beginTextExport(String filename, String mimeType, String content) {
        pendingExportText = content;
        pendingExportName = filename;
        pendingExportMime = mimeType;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(intent, REQUEST_CREATE_EXPORT);
        } catch (ActivityNotFoundException error) {
            pendingExportText = null;
            pendingExportName = null;
            pendingExportMime = null;
            Toast.makeText(this, "No file picker is available to save this file.", Toast.LENGTH_LONG).show();
        }
    }

    /** Native file operations are exposed only to the bundled local POS page. */
    public final class PosFilesBridge {
        @JavascriptInterface
        public void exportBackup(String json) {
            if (!isValidBackupDocument(json)) {
                runOnUiThread(() -> Toast.makeText(
                    MainActivity.this, "Backup data is invalid or too large.", Toast.LENGTH_LONG
                ).show());
                return;
            }
            runOnUiThread(() -> beginBackupExport(json));
        }

        @JavascriptInterface
        public void printReceipt(String saleJson) {
            ReceiptDocument receipt = createReceiptDocument(saleJson);
            runOnUiThread(() -> beginNativePrint(receipt));
        }

        @JavascriptInterface
        public void exportTextFile(String filename, String mimeType, String content) {
            if (filename == null || filename.isEmpty() || filename.length() > 120 ||
                filename.contains("/") || filename.contains("\\") ||
                content == null || content.length() > MAX_BACKUP_CHARS ||
                !("text/csv".equals(mimeType) || "application/json".equals(mimeType))) {
                runOnUiThread(() -> Toast.makeText(
                    MainActivity.this, "The file could not be exported. Check the data size and format.",
                    Toast.LENGTH_LONG
                ).show());
                return;
            }
            runOnUiThread(() -> beginTextExport(filename, mimeType, content));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));

        android.webkit.WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        posStorage = new PosStorage(this);
        webView.addJavascriptInterface(posStorage, "PosStorage");
        webView.addJavascriptInterface(new PosFilesBridge(), "PosFiles");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isTrustedAsset(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isTrustedAsset(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException error) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker is available.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                if (!isTrustedAsset(url)) {
                    result.cancel();
                    return true;
                }

                EditText input = new EditText(MainActivity.this);
                input.setSingleLine(true);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(message)
                    .setView(input)
                    .setNegativeButton("Cancel", (dialog, which) -> result.cancel())
                    .setPositiveButton("OK", (dialog, which) ->
                        result.confirm(input.getText().toString()))
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (!isTrustedAsset(url)) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setNegativeButton("Cancel", (dialog, which) -> result.cancel())
                    .setPositiveButton("Continue", (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (!isTrustedAsset(url)) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("OK", (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CREATE_BACKUP) {
            String backup = pendingBackupJson;
            pendingBackupJson = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || backup == null) {
                return;
            }
            try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (output == null) throw new IOException("Could not open the selected backup file.");
                output.write(backup.getBytes(StandardCharsets.UTF_8));
                output.flush();
                Toast.makeText(this, "POS backup saved successfully.", Toast.LENGTH_LONG).show();
            } catch (IOException error) {
                Toast.makeText(this, "Could not save the POS backup. Choose another location.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_CREATE_EXPORT) {
            String text = pendingExportText;
            String filename = pendingExportName;
            pendingExportText = null;
            pendingExportName = null;
            pendingExportMime = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || text == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (output == null) throw new IOException("Could not open the selected export file.");
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
                Toast.makeText(this, "Saved " + (filename == null ? "export" : filename) + ".", Toast.LENGTH_LONG).show();
            } catch (IOException error) {
                Toast.makeText(this, "Could not save the export. Choose another location.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_FILE_CHOOSER && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        for (WebView printView : new ArrayList<>(activePrintViews)) {
            try {
                printView.stopLoading();
                printView.destroy();
            } catch (RuntimeException ignored) {
                // Continue releasing the remaining WebViews.
            }
        }
        activePrintViews.clear();
        if (webView != null) {
            webView.removeJavascriptInterface("PosStorage");
            webView.removeJavascriptInterface("PosFiles");
            webView.destroy();
            webView = null;
        }
        if (posStorage != null) {
            posStorage.close();
            posStorage = null;
        }
        super.onDestroy();
    }
}
