package com.reality.ai;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NotificationWorker extends Worker {

    private static final String PREF_NAME = "RealityAINotifyPrefs";
    private static final String KEY_LAST_NOTIFY_ID = "last_notify_id";
    private static final String NOTIFICATION_CHANNEL_ID = "reality_ai_notify_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "Reality AI 即时通知";

    // 动态混淆解密的多通道心跳接口
    private static final String[] CHECK_ENDPOINTS = new String[]{
            MainActivity.decodeHex("68747470733a2f2f6170702e686970696e69732e6470646e732e6f72672f6170692f6e6f746966795f636865636b"), // https://app.hipinis.dpdns.org/api/notify_check
            MainActivity.decodeHex("68747470733a2f2f6170702e73756e6f66662e6470646e732e6f72672f6170692f6e6f746966795f636865636b"), // https://app.sunoff.dpdns.org/api/notify_check
            MainActivity.decodeHex("68747470733a2f2f6170702e7a65726f6e652e75732e6b672f6170692f6e6f746966795f636865636b")           // https://app.zerone.us.kg/api/notify_check
    };

    public NotificationWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        for (String endpoint : CHECK_ENDPOINTS) {
            if (checkAndNotify(endpoint)) {
                return Result.success();
            }
        }
        return Result.retry();
    }

    private boolean checkAndNotify(String endpointUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "RealityAI-BackgroundSync/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                boolean notifyEnabled = json.optBoolean("notify_enabled", false);
                String notifyMode = json.optString("notify_mode", "once"); // "once" 或 "persistent"
                int notifyId = json.optInt("notify_id", 1);
                String title = json.optString("notify_title", "🔥 Reality AI 系统通知");
                String body = json.optString("notify_body", "您有一条新的服务动态");
                String jumpUrl = json.optString("notify_url", "");

                SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                int lastNotifyId = prefs.getInt(KEY_LAST_NOTIFY_ID, -1);
                boolean hasReadOnce = prefs.getBoolean("read_once_id_" + notifyId, false);

                if (notifyEnabled && body != null && !body.trim().isEmpty()) {
                    if ("once".equalsIgnoreCase(notifyMode)) {
                        // 单次爆破通知：已送达展示过则绝不再骚扰
                        if (!hasReadOnce) {
                            showSystemNotification(title, body, jumpUrl, notifyId);
                            prefs.edit().putBoolean("read_once_id_" + notifyId, true).apply();
                        }
                    } else {
                        // 常驻通知：只要 ID 变动或未在此周期展示过则下发
                        if (notifyId != lastNotifyId) {
                            showSystemNotification(title, body, jumpUrl, notifyId);
                            prefs.edit().putInt(KEY_LAST_NOTIFY_ID, notifyId).apply();
                        }
                    }
                }
                return true;
            }
        } catch (Exception e) {
            // 当前通道请求异常，进入循环尝试下一通道
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private void showSystemNotification(String title, String message, String jumpUrl, int notifyId) {
        Context context = getApplicationContext();
        try {
            ensureNotificationChannel(context);

            Intent clickIntent = new Intent(context, MainActivity.class);
            clickIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (jumpUrl != null && !jumpUrl.isEmpty()) {
                clickIntent.putExtra("jump_url", jumpUrl);
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    notifyId,
                    clickIntent,
                    flags
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(notifyId, builder.build());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ensureNotificationChannel(Context context) {
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

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
