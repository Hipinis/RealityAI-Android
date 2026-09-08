package com.reality.ai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_URL = "https://realityai.hipinis.dpdns.org/lockpage/";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private boolean doubleBackToExitPressedOnce = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupImmersiveDarkMode();
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.main_webview);
        webView.setBackgroundColor(Color.parseColor("#0a0a0c"));

        checkAppPermissions();
        setupWebSettings();

        webView.addJavascriptInterface(new NativeBridge(), "RealityNativeApp");

        setupLongClickImageHandler();
        setupDownloadListener();
        setupCustomClients();

        webView.loadUrl(TARGET_URL);
    }

    private void setupImmersiveDarkMode() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#0a0a0c"));
        window.setNavigationBarColor(Color.parseColor("#0a0a0c"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebSettings() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        String defaultUa = s.getUserAgentString();
        s.setUserAgentString(defaultUa + " RealityAIBrowser/1.0 (Android Native Container)");
    }

    private void checkAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void setupLongClickImageHandler() {
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = ((WebView) v).getHitTestResult();
            int type = result.getType();
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                String imgUrl = result.getExtra();
                if (imgUrl != null) {
                    showSaveImageDialog(imgUrl);
                    return true;
                }
            }
            return true;
        });
    }

    private void showSaveImageDialog(String imageUrl) {
        new AlertDialog.Builder(this)
                .setTitle("🔥 Reality AI 原生画廊")
                .setMessage("是否将当前高清图片保存至手机相册？")
                .setPositiveButton("保存到相册", (dialog, which) -> {
                    saveImageToGallery(imageUrl);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setupDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("data:image/")) {
                saveBase64Image(url);
                return;
            }
            if (url.startsWith("blob:")) {
                webView.evaluateJavascript(
                    "fetch('" + url + "').then(r => r.blob()).then(b => { " +
                    "  var reader = new FileReader(); " +
                    "  reader.onloadend = () => RealityNativeApp.saveBase64(reader.result); " +
                    "  reader.readAsDataURL(b); " +
                    "});", null
                );
                return;
            }

            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Reality AI 图片资源下载中...");
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "RealityAI/" + fileName);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "开始下载高清图片...", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), "下载启动失败，请检查网络", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupCustomClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) { }
                return true;
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "选择生图参考图"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (uploadMessage != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    Uri dataUri = data.getData();
                    if (dataUri != null) {
                        results = new Uri[]{dataUri};
                    }
                }
                uploadMessage.onReceiveValue(results);
                uploadMessage = null;
            }
        }
    }

    public class NativeBridge {
        @JavascriptInterface
        public void saveBase64(String base64Data) {
            saveBase64Image(base64Data);
        }
    }

    private void saveImageToGallery(String imageUrl) {
        if (imageUrl.startsWith("data:image/")) {
            saveBase64Image(imageUrl);
        } else {
            webView.loadUrl(imageUrl);
        }
    }

    private void saveBase64Image(String base64Str) {
        new Thread(() -> {
            try {
                String pureBase64 = base64Str.substring(base64Str.indexOf(",") + 1);
                byte[] decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT);
                String fileName = "RealityAI_" + System.currentTimeMillis() + ".png";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RealityAI");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(decodedBytes);
                        }
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RealityAI");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(decodedBytes);
                    }
                }

                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(MainActivity.this, "已成功保存高清大图至相册！", Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(MainActivity.this, "保存图片失败", Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            WebBackForwardList history = webView.copyBackForwardList();
            if (history.getCurrentIndex() <= 1) {
                triggerExitLogic();
            } else {
                webView.goBack();
            }
        } else {
            triggerExitLogic();
        }
    }

    private void triggerExitLogic() {
        if (doubleBackToExitPressedOnce) {
            finish();
            return;
        }
        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> doubleBackToExitPressedOnce = false, 2000);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            webView.clearHistory();
            webView.destroy();
        }
        super.onDestroy();
    }
}
