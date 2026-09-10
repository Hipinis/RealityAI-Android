package com.reality.ai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    // 👑 动态内存十六进制混淆解密的多中继容灾矩阵 (彻底杜绝明文字符串)
    private static final String[] GATEWAY_CHANNELS = new String[]{
            decodeHex("68747470733a2f2f6170702e686970696e69732e6470646e732e6f72672f"), // 主通道: https://app.hipinis.dpdns.org/
            decodeHex("68747470733a2f2f6170702e73756e6f66662e6470646e732e6f72672f"), // 备用1: https://app.sunoff.dpdns.org/
            decodeHex("68747470733a2f2f6170702e7a65726f6e652e75732e6b672f")           // 备用2: https://app.zerone.us.kg/
    };

    private static final String NOTIFICATION_CHANNEL_ID = "reality_ai_notify_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "Reality AI 即时通知";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 1002;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ValueCallback<Uri[]> uploadMessage;
    private boolean doubleBackToExitPressedOnce = false;

    private int currentChannelIndex = 0;
    private boolean hasLoadedSuccessfully = false;
    private final Handler failoverHandler = new Handler(Looper.getMainLooper());
    private Runnable failoverRunnable;

    public static String decodeHex(String hex) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.length() - 1; i += 2) {
                String output = hex.substring(i, i + 2);
                int decimal = Integer.parseInt(output, 16);
                sb.append((char) decimal);
            }
            return sb.toString();
        } catch (Exception e) {
            return "https://app.hipinis.dpdns.org/";
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupImmersiveDarkMode();
        setContentView(R.layout.activity_main);

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#f59e0b"), Color.parseColor("#3b82f6"));
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Color.parseColor("#18181b"));

        webView = findViewById(R.id.main_webview);
        webView.setBackgroundColor(Color.parseColor("#0a0a0c"));

        // 严格防误触设计：只有页面处于绝对置顶状态 (scrollY == 0) 时，才允许下拉刷新手势生效！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                swipeRefreshLayout.setEnabled(scrollY == 0);
            });
        }

        swipeRefreshLayout.setOnRefreshListener(() -> {
            webView.reload();
        });

        createNotificationChannel();
        showOfficialSystemPermissionGuide();
        setupWebSettings();

        webView.addJavascriptInterface(new NativeBridge(), "RealityNativeApp");

        setupLongClickImageHandler();
        setupDownloadListener();
        setupCustomClients();

        handleNotificationIntent(getIntent());

        // 注册系统级后台离线通知心跳 (WorkManager + AlarmManager 穿透双引擎)
        setupBackgroundNotificationWorker();
        AlarmReceiver.scheduleNextAlarm(this);

        // 异步检查版本与缓存更新
        checkAppUpdateAsync();

        // 启动三通道容灾加载
        loadCurrentChannel();
    }

    private void loadCurrentChannel() {
        if (currentChannelIndex >= GATEWAY_CHANNELS.length) {
            showOfflineErrorPage();
            return;
        }
        String url = GATEWAY_CHANNELS[currentChannelIndex];

        if (failoverRunnable != null) {
            failoverHandler.removeCallbacks(failoverRunnable);
        }
        // 2.5 秒极速超时熔断探测：若当前通道无响应且未加载成功，自动无缝切入下一备用通道
        failoverRunnable = () -> {
            if (!hasLoadedSuccessfully && currentChannelIndex < GATEWAY_CHANNELS.length - 1) {
                currentChannelIndex++;
                loadCurrentChannel();
            }
        };
        failoverHandler.postDelayed(failoverRunnable, 2500);

        webView.loadUrl(url);
    }

    private void switchToNextChannel() {
        if (failoverRunnable != null) {
            failoverHandler.removeCallbacks(failoverRunnable);
        }
        if (currentChannelIndex < GATEWAY_CHANNELS.length - 1) {
            currentChannelIndex++;
            loadCurrentChannel();
        } else {
            showOfflineErrorPage();
        }
    }

    private void showOfflineErrorPage() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
        String errorHtml = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>" +
                "body{margin:0;background:#0a0a0c;color:#e4e4e7;font-family:-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:20px;box-sizing:border-box;}" +
                ".box{background:rgba(24,24,27,0.9);border:1px solid rgba(245,158,11,0.35);border-radius:20px;padding:36px 24px;max-width:340px;box-shadow:0 12px 35px rgba(0,0,0,0.85);}" +
                ".icon{font-size:52px;margin-bottom:16px;filter:drop-shadow(0 0 16px rgba(245,158,11,0.6));}" +
                ".title{font-size:20px;font-weight:bold;color:#fbbf24;margin-bottom:8px;letter-spacing:1px;}" +
                ".desc{font-size:13px;color:#a1a1aa;line-height:1.6;margin-bottom:26px;}" +
                ".btn{display:inline-block;background:linear-gradient(135deg,#f59e0b,#d97706);color:#000;font-weight:bold;padding:12px 36px;border-radius:24px;text-decoration:none;font-size:15px;box-shadow:0 4px 18px rgba(245,158,11,0.45);cursor:pointer;border:none;outline:none;transition:transform 0.2s;}" +
                ".btn:active{transform:scale(0.96);}" +
                "</style></head><body>" +
                "<div class='box'>" +
                "<div class='icon'>⚡</div>" +
                "<div class='title'>网络连接微弱</div>" +
                "<div class='desc'>暂时无法连接中继服务器，请检查网络设置或稍后点击重试</div>" +
                "<button class='btn' onclick='if(window.RealityNativeApp){RealityNativeApp.retryConnect();}else{location.reload();}'>重新连接</button>" +
                "</div></body></html>";
        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "utf-8", null);
    }

    private void setupBackgroundNotificationWorker() {
        try {
            PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                    NotificationWorker.class,
                    15, TimeUnit.MINUTES
            ).setConstraints(
                    new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
            ).build();

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "RealityAIBackgroundSync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkAppUpdateAsync() {
        new Thread(() -> {
            try {
                String endpoint = GATEWAY_CHANNELS[0] + "api/notify_check";
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    r.close();
                    JSONObject json = new JSONObject(sb.toString());
                    boolean updateEnabled = json.optBoolean("update_enabled", false);
                    boolean updateForced = json.optBoolean("update_forced", false);
                    String latestVer = json.optString("latest_version", "1.0.3");
                    String apkUrl = json.optString("apk_download_url", "");
                    String updateNotes = json.optString("update_notes", "全新生图引擎升级，支持离线缓存加速与多通道容灾");
                    String cacheVer = json.optString("cache_version", "1.0.0");

                    CacheManager.getInstance(getApplicationContext()).syncCacheVersion(cacheVer);

                    // 若开启了更新推送且线上版本高于当前 1.0.3
                    if (updateEnabled && !"1.0.3".equals(latestVer) && apkUrl.startsWith("http")) {
                        if (updateForced) {
                            // 强制升级模式：每次必须弹出，不可跳过
                            new Handler(Looper.getMainLooper()).post(() -> showUpdateDialog(latestVer, updateNotes, apkUrl, true));
                        } else {
                            // 软更新模式：检查当天是否已跳过防骚扰
                            SharedPreferences sp = getSharedPreferences("AppUpdatePrefs", MODE_PRIVATE);
                            String todayStr = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
                            if (!todayStr.equals(sp.getString("skipped_date_" + latestVer, ""))) {
                                new Handler(Looper.getMainLooper()).post(() -> showUpdateDialog(latestVer, updateNotes, apkUrl, false));
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    // 👑 暗黑轻奢毛玻璃版本更新弹窗 (双模式：支持软更新跳过记忆 / 强更新不可取消)
    private void showUpdateDialog(String version, String notes, String downloadUrl, boolean isForced) {
        try {
            android.app.Dialog dialog = new android.app.Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            android.widget.LinearLayout root = new android.widget.LinearLayout(this);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setPadding(48, 48, 48, 48);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#f012121c"));
            bg.setCornerRadius(36f);
            bg.setStroke(3, Color.parseColor(isForced ? "#ef4444" : "#50f59e0b"));
            root.setBackground(bg);

            // 头部标题
            android.widget.TextView titleTv = new android.widget.TextView(this);
            titleTv.setText(isForced ? "⚠️ 系统服务重要升级 v" + version : "🚀 发现新版本 v" + version);
            titleTv.setTextSize(18f);
            titleTv.setTextColor(Color.parseColor(isForced ? "#f87171" : "#fbbf24"));
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setGravity(android.view.Gravity.CENTER);
            root.addView(titleTv);

            // 更新日志卡片
            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 260);
            scrollLp.setMargins(0, 32, 0, 36);
            scrollView.setLayoutParams(scrollLp);

            android.widget.TextView notesTv = new android.widget.TextView(this);
            notesTv.setText(notes != null && !notes.isEmpty() ? notes : "检测到重要性能升级，推荐立即更新体验！");
            notesTv.setTextColor(Color.parseColor("#cbd5e1"));
            notesTv.setTextSize(13f);
            notesTv.setLineSpacing(8f, 1.2f);
            notesTv.setPadding(28, 24, 28, 24);

            android.graphics.drawable.GradientDrawable notesBg = new android.graphics.drawable.GradientDrawable();
            notesBg.setColor(Color.parseColor("#8009090f"));
            notesBg.setCornerRadius(20f);
            notesBg.setStroke(2, Color.parseColor("#30ffffff"));
            notesTv.setBackground(notesBg);
            scrollView.addView(notesTv);
            root.addView(scrollView);

            // 操作按钮行
            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

            if (!isForced) {
                // 软更新：提供稍后再说按钮，并记录当天跳过防骚扰
                android.widget.Button btnCancel = new android.widget.Button(this);
                btnCancel.setText("稍后再说");
                btnCancel.setTextColor(Color.parseColor("#94a3b8"));
                btnCancel.setTextSize(14f);
                btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
                android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(
                        0, 120, 1f);
                cancelLp.setMargins(0, 0, 16, 0);
                btnCancel.setLayoutParams(cancelLp);
                android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
                cancelBg.setColor(Color.parseColor("#20ffffff"));
                cancelBg.setCornerRadius(24f);
                cancelBg.setStroke(2, Color.parseColor("#40ffffff"));
                btnCancel.setBackground(cancelBg);
                btnCancel.setOnClickListener(v -> {
                    SharedPreferences sp = getSharedPreferences("AppUpdatePrefs", MODE_PRIVATE);
                    String todayStr = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
                    sp.edit().putString("skipped_date_" + version, todayStr).apply();
                    dialog.dismiss();
                });
                btnRow.addView(btnCancel);
            }

            android.widget.Button btnConfirm = new android.widget.Button(this);
            btnConfirm.setText(isForced ? "立即升级并继续使用" : "立即极速更新");
            btnConfirm.setTextColor(Color.parseColor("#000000"));
            btnConfirm.setTextSize(14f);
            btnConfirm.setTypeface(null, android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams confirmLp = new android.widget.LinearLayout.LayoutParams(
                    0, 120, isForced ? 1f : 1.4f);
            btnConfirm.setLayoutParams(confirmLp);
            android.graphics.drawable.GradientDrawable confirmBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    isForced ? new int[]{Color.parseColor("#ef4444"), Color.parseColor("#f97316")} :
                               new int[]{Color.parseColor("#f59e0b"), Color.parseColor("#d946ef")}
            );
            confirmBg.setCornerRadius(24f);
            btnConfirm.setBackground(confirmBg);
            btnConfirm.setOnClickListener(v -> {
                if (!isForced) dialog.dismiss();
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
                    req.setTitle("Reality AI 正在更新...");
                    req.setDescription("新版本安装包高速下载中");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RealityAI_v" + version + ".apk");
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                    showDarkToast("🚀 开始在后台下载新版本...");
                } catch (Exception e) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    startActivity(i);
                }
            });
            btnRow.addView(btnConfirm);
            root.addView(btnRow);

            dialog.setContentView(root);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            if (isForced) {
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
            } else {
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);
            }
            dialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 👑 首次启动官方系统级权威规范引导弹窗 (杜绝任何营销福利感)
    private void showOfficialSystemPermissionGuide() {
        SharedPreferences sp = getSharedPreferences("SystemPermissionPrefs", MODE_PRIVATE);
        if (sp.getBoolean("system_perm_guided", false)) {
            checkAppPermissions();
            return;
        }

        runOnUiThread(() -> {
            try {
                android.app.Dialog dialog = new android.app.Dialog(this);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                android.widget.LinearLayout root = new android.widget.LinearLayout(this);
                root.setOrientation(android.widget.LinearLayout.VERTICAL);
                root.setPadding(48, 44, 48, 44);

                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.parseColor("#f8101018"));
                bg.setCornerRadius(30f);
                bg.setStroke(2, Color.parseColor("#4038bdf8"));
                root.setBackground(bg);

                android.widget.TextView titleTv = new android.widget.TextView(this);
                titleTv.setText("⚙️ 系统状态与服务同步规范");
                titleTv.setTextSize(16.5f);
                titleTv.setTextColor(Color.parseColor("#f1f5f9"));
                titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
                root.addView(titleTv);

                android.widget.TextView msgTv = new android.widget.TextView(this);
                msgTv.setText("依据 Android 系统通信与后台任务调度规范，本客户端需申请基础系统状态通知权限：\n\n" +
                              "1. 系统服务状态同步：用于实时接收云端渲染完成状态、服务队列调度与紧急安全维护广播；\n" +
                              "2. 后台自愈与连接保持：保障在多任务切换时维持任务会话连接，避免渲染意外中断。\n\n" +
                              "本服务严格遵守设备数据安全规范，不收集任何非必要个人隐私。");
                msgTv.setTextSize(13f);
                msgTv.setTextColor(Color.parseColor("#94a3b8"));
                msgTv.setLineSpacing(6f, 1.25f);
                msgTv.setPadding(0, 24, 0, 32);
                root.addView(msgTv);

                android.widget.Button btnGrant = new android.widget.Button(this);
                btnGrant.setText("开启系统状态同步");
                btnGrant.setTextColor(Color.parseColor("#000000"));
                btnGrant.setTextSize(14f);
                btnGrant.setTypeface(null, android.graphics.Typeface.BOLD);
                android.graphics.drawable.GradientDrawable grantBg = new android.graphics.drawable.GradientDrawable();
                grantBg.setColor(Color.parseColor("#38bdf8"));
                grantBg.setCornerRadius(20f);
                btnGrant.setBackground(grantBg);
                btnGrant.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 115));
                
                btnGrant.setOnClickListener(v -> {
                    dialog.dismiss();
                    sp.edit().putBoolean("system_perm_guided", true).apply();
                    checkAppPermissions();
                });
                root.addView(btnGrant);

                dialog.setContentView(root);
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                dialog.show();
            } catch (Exception e) {
                checkAppPermissions();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.hasExtra("jump_url")) {
            String jumpUrl = intent.getStringExtra("jump_url");
            if (jumpUrl != null && (jumpUrl.startsWith("http://") || jumpUrl.startsWith("https://"))) {
                if (webView != null) {
                    webView.loadUrl(jumpUrl);
                }
            }
        }
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
        s.setUserAgentString(defaultUa + " RealityAIBrowser/1.1 (Android Native Container; Failover; WarmCache)");
    }

    private void checkAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("用于接收系统广播、生图完成通知及专属福利");
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#f59e0b"));
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 150, 250});

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
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

    // 👑 暗黑轻奢毛玻璃长按存图弹窗 (彻底废除丑陋原生 AlertDialog)
    private void showSaveImageDialog(String imageUrl) {
        try {
            android.app.Dialog dialog = new android.app.Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            android.widget.LinearLayout root = new android.widget.LinearLayout(this);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setPadding(44, 40, 44, 40);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#f012121c"));
            bg.setCornerRadius(32f);
            bg.setStroke(3, Color.parseColor("#50f59e0b"));
            root.setBackground(bg);

            android.widget.TextView titleTv = new android.widget.TextView(this);
            titleTv.setText("🔥 Reality AI 原生画廊");
            titleTv.setTextSize(17f);
            titleTv.setTextColor(Color.parseColor("#fbbf24"));
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setGravity(android.view.Gravity.CENTER);
            root.addView(titleTv);

            android.widget.TextView descTv = new android.widget.TextView(this);
            descTv.setText("是否将当前精选高清大图保存至手机相册？");
            descTv.setTextSize(13.5f);
            descTv.setTextColor(Color.parseColor("#cbd5e1"));
            descTv.setGravity(android.view.Gravity.CENTER);
            descTv.setPadding(0, 24, 0, 36);
            root.addView(descTv);

            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

            android.widget.Button btnCancel = new android.widget.Button(this);
            btnCancel.setText("取消");
            btnCancel.setTextColor(Color.parseColor("#94a3b8"));
            btnCancel.setTextSize(14f);
            btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(0, 115, 1f);
            cancelLp.setMargins(0, 0, 14, 0);
            btnCancel.setLayoutParams(cancelLp);
            android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
            cancelBg.setColor(Color.parseColor("#20ffffff"));
            cancelBg.setCornerRadius(22f);
            cancelBg.setStroke(2, Color.parseColor("#40ffffff"));
            btnCancel.setBackground(cancelBg);
            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnRow.addView(btnCancel);

            android.widget.Button btnSave = new android.widget.Button(this);
            btnSave.setText("保存到相册");
            btnSave.setTextColor(Color.parseColor("#000000"));
            btnSave.setTextSize(14f);
            btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams saveLp = new android.widget.LinearLayout.LayoutParams(0, 115, 1.3f);
            btnSave.setLayoutParams(saveLp);
            android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.parseColor("#f59e0b"), Color.parseColor("#d946ef")}
            );
            saveBg.setCornerRadius(22f);
            btnSave.setBackground(saveBg);
            btnSave.setOnClickListener(v -> {
                dialog.dismiss();
                saveImageToGallery(imageUrl);
            });
            btnRow.addView(btnSave);

            root.addView(btnRow);

            dialog.setContentView(root);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.84),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.show();
        } catch (Exception e) {
            saveImageToGallery(imageUrl);
        }
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
                    showDarkToast("开始下载高清图片...");
                }
            } catch (Exception e) {
                showDarkToast("下载启动失败，请检查网络");
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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                hasLoadedSuccessfully = true;
                if (failoverRunnable != null) {
                    failoverHandler.removeCallbacks(failoverRunnable);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    switchToNextChannel();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                switchToNextChannel();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse resp = CacheManager.getInstance(getApplicationContext()).intercept(request.getUrl());
                if (resp != null) {
                    return resp;
                }
                return super.shouldInterceptRequest(view, request);
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

        @JavascriptInterface
        public void postNotification(String title, String message, String jumpUrl) {
            sendSystemNotification(title, message, jumpUrl);
        }

        @JavascriptInterface
        public void retryConnect() {
            runOnUiThread(() -> {
                currentChannelIndex = 0;
                hasLoadedSuccessfully = false;
                loadCurrentChannel();
            });
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
                    showDarkToast("✨ 已成功保存高清大图至相册！")
                );
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                    showDarkToast("⚠️ 保存图片失败")
                );
            }
        }).start();
    }

    // 👑 专属暗黑轻奢微光悬浮胶囊 Toast
    public void showDarkToast(String message) {
        runOnUiThread(() -> {
            try {
                Toast toast = new Toast(getApplicationContext());
                toast.setDuration(Toast.LENGTH_SHORT);
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(message);
                tv.setTextColor(Color.parseColor("#f1f5f9"));
                tv.setTextSize(13.5f);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(46, 24, 46, 24);

                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(Color.parseColor("#ea12121c"));
                gd.setCornerRadius(36f);
                gd.setStroke(3, Color.parseColor("#70f59e0b"));
                tv.setBackground(gd);

                toast.setView(tv);
                toast.show();
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendSystemNotification(String title, String message, String jumpUrl) {
        try {
            Intent clickIntent = new Intent(this, MainActivity.class);
            clickIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (jumpUrl != null && !jumpUrl.isEmpty()) {
                clickIntent.putExtra("jump_url", jumpUrl);
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    (int) System.currentTimeMillis(),
                    clickIntent,
                    flags
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title != null && !title.isEmpty() ? title : "🔥 Reality AI 系统通知")
                    .setContentText(message != null && !message.isEmpty() ? message : "您有一条新的服务动态")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                int notifyId = (int) (System.currentTimeMillis() % 100000);
                manager.notify(notifyId, builder.build());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isGatewayDomain(String url) {
        if (url == null) return false;
        return url.contains("hipinis.dpdns.org") || url.contains("sunoff.dpdns.org") || url.contains("zerone.us.kg");
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            triggerExitLogic();
            return;
        }

        if (webView.canGoBack()) {
            WebBackForwardList history = webView.copyBackForwardList();
            int currentIndex = history.getCurrentIndex();

            if (currentIndex > 0) {
                WebHistoryItem prevItem = history.getItemAtIndex(currentIndex - 1);
                if (prevItem != null) {
                    String prevUrl = prevItem.getUrl();
                    if (isGatewayDomain(prevUrl)) {
                        triggerExitLogic();
                        return;
                    }
                }
            }

            webView.goBack();
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
        showDarkToast("再按一次退出应用");
        new Handler(Looper.getMainLooper()).postDelayed(() -> doubleBackToExitPressedOnce = false, 2000);
    }

    @Override
    protected void onDestroy() {
        if (failoverRunnable != null) {
            failoverHandler.removeCallbacks(failoverRunnable);
        }
        if (webView != null) {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            webView.clearHistory();
            webView.destroy();
        }
        super.onDestroy();
    }
}

