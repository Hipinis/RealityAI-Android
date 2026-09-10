package com.reality.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALARM_CHECK = "com.reality.ai.ALARM_NOTIFY_CHECK";
    private static final long INTERVAL_MILLIS = 15 * 60 * 1000; // 15分钟自愈精准心跳

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // 穿透唤醒，立即触发一次后台轻量轮询
            OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(NotificationWorker.class).build();
            WorkManager.getInstance(context).enqueue(workRequest);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 重新设定下一次精准定时闹钟 (自愈循环)
        scheduleNextAlarm(context);
    }

    public static void scheduleNextAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                Intent i = new Intent(context, AlarmReceiver.class);
                i.setAction(ACTION_ALARM_CHECK);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pi = PendingIntent.getBroadcast(context, 10086, i, flags);

                long triggerAt = System.currentTimeMillis() + INTERVAL_MILLIS;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
