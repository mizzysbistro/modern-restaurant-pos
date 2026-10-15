package com.mizzysbistro.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

public class MainActivity extends Activity {
    private WebView webView;
    private PosStorage posStorage;

    private boolean isTrustedAsset(String url) {
        return url != null && url.startsWith("file:///android_asset/");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
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

        // The JavaScript bridge is exposed only to bundled app assets. The
        // WebViewClient below blocks navigation to untrusted URLs.
        posStorage = new PosStorage(this);
        webView.addJavascriptInterface(posStorage, "PosStorage");
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
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
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
        if (webView != null) {
            webView.removeJavascriptInterface("PosStorage");
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
