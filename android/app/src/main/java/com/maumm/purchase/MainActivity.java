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
 * 마음 살림(물품 구매 · 결재 · 도시락) 화면(assets/index.html)을 안전한 https 주소로 열어 줍니다.
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
                if (!(tel || sms || url.startsWith("mailto:") || url.startsWith("https://") || url.startsWith("http://") || url.startsWith("coupang://"))) return;
                // 전화는 바로 걸지 않고 번호가 입력된 전화 화면만 엽니다.
                try {
                    if (sms) {
                        // sms:번호?body=내용 → 문자 앱에 번호와 내용을 넣어 엽니다 (보내기는 직접 누름)
                        String num = url.substring(4), body = null;
                        int q = num.indexOf('?');
                        if (q >= 0) {
                            String qs = num.substring(q + 1); num = num.substring(0, q);
                            for (String kv : qs.split("&")) if (kv.startsWith("body=")) body = Uri.decode(kv.substring(5));
                        }
                        Intent it = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + num));
                        if (body != null) it.putExtra("sms_body", body);
                        startActivity(it);
                    } else startActivity(new Intent(tel ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, Uri.parse(url)));
                }
                catch (Exception e) { toast("열 수 있는 앱이 없어요"); }
            });
        }

        /** 식단표 사진(PNG)을 문자 · 카톡 등으로 보냅니다. number가 있으면 문자 앱에 받는 사람으로 넣어 봅니다. */
        @JavascriptInterface
        public void shareImage(String name, String b64, String text, String number) {
            runOnUiThread(() -> {
                try {
                    java.io.File dir = new java.io.File(getCacheDir(), "share");
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, (name == null || name.isEmpty() ? "식단표" : name) + ".png");
                    byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); }
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/png");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    if (text != null) { i.putExtra(Intent.EXTRA_TEXT, text); i.putExtra("sms_body", text); }
                    if (number != null && !number.isEmpty()) i.putExtra("address", number);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "식단표 보내기"));
                } catch (Exception e) { toast("사진을 보내지 못했어요"); }
            });
        }

        /** 문자 · 카카오톡 · 이메일 · 다른 앱으로 바로 보내기. kind: kakao | email | sms | any. b64가 있으면 사진(PNG)도 함께. */
        @JavascriptInterface
        public void sendTo(String kind, String name, String b64, String text, String subject, String address) {
            runOnUiThread(() -> {
                try {
                    Uri img = null;
                    if (b64 != null && !b64.isEmpty()) {
                        java.io.File dir = new java.io.File(getCacheDir(), "share"); dir.mkdirs();
                        java.io.File f = new java.io.File(dir, (name == null || name.isEmpty() ? "보내기" : name) + ".png");
                        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); }
                        img = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    }
                    Intent i;
                    if ("email".equals(kind) && img == null) {
                        i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (address == null ? "" : address)));
                    } else {
                        i = new Intent(Intent.ACTION_SEND);
                        i.setType(img != null ? "image/png" : "text/plain");
                        if (img != null) { i.putExtra(Intent.EXTRA_STREAM, img); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                        if ("email".equals(kind)) { i.setSelector(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))); if (address != null && !address.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[]{address}); }
                    }
                    if (text != null) { i.putExtra(Intent.EXTRA_TEXT, text); i.putExtra("sms_body", text); }
                    if (subject != null && !subject.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, subject);
                    if (address != null && !address.isEmpty() && "sms".equals(kind)) i.putExtra("address", address);
                    if ("kakao".equals(kind)) {
                        i.setPackage("com.kakao.talk");
                        try { startActivity(i); return; }
                        catch (android.content.ActivityNotFoundException e) { toast("카카오톡이 설치되어 있지 않아요 — 다른 앱을 골라 주세요"); i.setPackage(null); }
                    }
                    if ("email".equals(kind)) { try { startActivity(i); return; } catch (android.content.ActivityNotFoundException e) { toast("메일 앱을 찾지 못했어요"); i.setSelector(null); } }
                    startActivity(Intent.createChooser(i, "보내기"));
                } catch (Exception e) { toast("보내지 못했어요"); }
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
                cm.setPrimaryClip(ClipData.newPlainText("마음 살림", text));
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
