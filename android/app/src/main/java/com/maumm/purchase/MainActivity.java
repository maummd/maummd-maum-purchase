package com.maumm.purchase;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 물품 구매 관리 화면(assets/index.html)을 안전한 https 주소로 열어 줍니다.
 * 구매 기록은 이 휴대폰 안(WebView 저장소)에만 저장되며 서버로 보내지 않습니다.
 *
 * 마음엠디 앱과 같은 구조에 다음을 더했습니다.
 *  - 파일 고르기: 백업 파일 복원
 *  - 파일 저장: 백업 파일을 사용자가 고른 위치에 저장 (AdminAndroid.saveFile)
 *  - 문서: 구매 내역 · 인수인계서를 PDF로 저장 · 인쇄 (AdminAndroid.printHtml)
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK = 1001;
    private static final int REQ_SAVE = 1002;

    private WebView webView;
    private WebView printView;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingSaveContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#F4F5F1"));
        setContentView(webView);

        webView.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });

        // <input type="file"> 를 누르면 휴대폰의 파일 고르기 화면을 엽니다.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "파일 선택"), REQ_PICK);
                } catch (Exception e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AdminAndroid");

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl("https://" + HOST + "/assets/index.html");

        ReminderReceiver.ensureChannel(this);
        askNotiPermission();

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    /** 화면(JavaScript)에서 부르는 기능 */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String content) {
            runOnUiThread(() -> {
                pendingSaveContent = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/json" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); }
                catch (Exception e) { pendingSaveContent = null; toast("저장 화면을 열 수 없어요"); }
            });
        }

        @JavascriptInterface
        public void printHtml(String name, String html) {
            runOnUiThread(() -> {
                printView = new WebView(MainActivity.this);
                printView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        String job = (name == null || name.isEmpty()) ? "물품구매" : name;
                        PrintDocumentAdapter ad = view.createPrintDocumentAdapter(job);
                        pm.print(job, ad, new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                    }
                });
                printView.loadDataWithBaseURL("https://" + HOST + "/", html, "text/html", "UTF-8", null);
            });
        }

        @JavascriptInterface
        public void setReminders(String json) {
            ReminderReceiver.schedule(getApplicationContext(), json == null ? "[]" : json);
        }

        @JavascriptInterface
        public void requestNotiPermission() {
            runOnUiThread(() -> askNotiPermission());
        }

        @JavascriptInterface
        public void testNotification(String title, String body) {
            runOnUiThread(() -> {
                askNotiPermission();
                ReminderReceiver.show(getApplicationContext(), 7, title, body);
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> {
                if (url == null) return;
                boolean tel = url.startsWith("tel:"), sms = url.startsWith("sms:");
                if (!(tel || sms || url.startsWith("https://") || url.startsWith("http://") || url.startsWith("coupang://"))) return;
                // 전화는 바로 걸지 않고 번호가 입력된 전화 화면만 엽니다.
                try { startActivity(new Intent(tel ? Intent.ACTION_DIAL : (sms ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW), Uri.parse(url))); }
                catch (Exception e) { toast("열 수 있는 앱이 없어요"); }
            });
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, "원장님께 보내기")); }
                catch (Exception e) { toast("보낼 앱을 찾지 못했어요"); }
            });
        }

        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("물품 구매 관리", text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            if (pickCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{ data.getData() };
                pickCallback.onReceiveValue(result);
                pickCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            String content = pendingSaveContent;
            pendingSaveContent = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || content == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("no stream");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                toast("파일을 저장했어요");
            } catch (Exception e) {
                toast("저장하지 못했어요");
            }
        }
    }

    private void askNotiPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 2001);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 뒤로 가기: 열린 창을 닫고 → 첫 화면으로 → 더 갈 곳이 없으면 앱을 닫습니다. */
    private void handleBack() {
        if (webView == null) { finish(); return; }
        webView.evaluateJavascript("window.nuBack&&window.nuBack()", v -> {
            if ("true".equals(v)) return;
            if (webView.canGoBack()) webView.goBack(); else finish();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }
}
