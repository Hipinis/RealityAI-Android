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
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class FCMService extends FirebaseMessagingService {

    private static final String PREF_NAME = "RealityAINotifyPrefs";
    private static final String KEY_LAST_NOTIFY_ID = "last_notify_id";
    private static final String NOTIFICATION_CHANNEL_ID = "reality_ai_notify_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "Reality AI 即时通知";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // 自动加入全员广播主题，方便控制台一键群发
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("all_users");
        } catch (Exception ignored) {}
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        String title = "🔥 Reality AI 系统通知";
        String body = "";
        String jumpUrl = "";
        String mode = "once";
        int notifyId = (int) (System.currentTimeMillis() % 100000);

        if (data != null && !data.isEmpty()) {
            if (data.containsKey("title")) title = data.get("title");
            if (data.containsKey("body")) body = data.get("body");
            if (data.containsKey("notify_url")) jumpUrl = data.get("notify_url");
            if (data.containsKey("notify_mode")) mode = data.get("notify_mode");
            if (data.containsKey("notify_id")) {
                try {
                    notifyId = Integer.parseInt(data.get("notify_id"));
                } catch (Exception ignored) {}
            }
        }

        // 如果包含 notification payload，作为兜底
        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (remoteMessage.getNotification().getBody() != null) {
                body = remoteMessage.getNotification().getBody();
            }
        }

        if (body == null || body.trim().isEmpty()) return;

        // 👑 核心原子防重：如果是“单次即时”模式，检查本地是否已经展示过
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if ("once".equalsIgnoreCase(mode)) {
            if (prefs.getBoolean("read_once_id_" + notifyId, false)) {
                return; // FCM 后到或自建通道已弹过，静默丢弃！
            }
            prefs.edit().putBoolean("read_once_id_" + notifyId, true).apply();
        } else {
            int lastNotifyId = prefs.getInt(KEY_LAST_NOTIFY_ID, -1);
            if (lastNotifyId == notifyId) {
                return; // 常驻模式同ID已弹过
            }
            prefs.edit().putInt(KEY_LAST_NOTIFY_ID, notifyId).apply();
        }

        // 弹出高优先级系统通知
        showSystemNotification(title, body, jumpUrl, notifyId);
    }

    private void showSystemNotification(String title, String message, String jumpUrl, int notifyId) {
        try {
            ensureNotificationChannel(this);

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
                    notifyId,
                    clickIntent,
                    flags
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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
