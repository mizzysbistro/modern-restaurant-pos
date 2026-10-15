package com.mizzysbistro.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
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

    private boolean isTrustedAsset(String url) {
        return url != null && url.startsWith("file:///android_asset/");
    }

    private boolean isValidBackupDocument(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_BACKUP_CHARS) return false;
        try {
            JSONObject root = new JSONObject(json);
            if (!"mizzy-bistro-pos-backup".equals(root.optString("format"))) return false;
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
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
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
