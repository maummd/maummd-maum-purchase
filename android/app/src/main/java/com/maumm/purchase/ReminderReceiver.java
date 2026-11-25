package com.maumm.purchase;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 구매 시기 알림.
 * 화면(JavaScript)이 앞으로 60일 동안의 알림 목록을 넘겨주면 AlarmManager에 맡기고,
 * 정해진 시각에 휴대폰 알림을 띄웁니다. 휴대폰을 다시 켜면 저장된 목록으로 다시 예약합니다.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "purchase_reminders";
    static final String PREFS = "reminders";
    static final String ACTION_FIRE = "com.maumm.purchase.REMINDER";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]");
            schedule(ctx, json);
            return;
        }
        if (ACTION_FIRE.equals(action)) {
            show(ctx, intent.getIntExtra("nid", 1), intent.getStringExtra("title"), intent.getStringExtra("body"));
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "구매 시기 알림", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("정기 구매 · 원장님 확인 · 배송 수령 알림");
            nm.createNotificationChannel(ch);
        }
    }

    static void show(Context ctx, int nid, String title, String body) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cart)
            .setColor(Color.parseColor("#2F5D50"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(nid, n); } catch (SecurityException ignored) { }
    }

    /** 예전 예약을 모두 지우고 새 목록으로 다시 예약합니다. */
    static void schedule(Context ctx, String json) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(sp.getString("codes", "[]"));
            for (int i = 0; i < old.length(); i++) {
                PendingIntent p = PendingIntent.getBroadcast(ctx, old.getInt(i), new Intent(ctx, ReminderReceiver.class).setAction(ACTION_FIRE),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
                if (p != null) { am.cancel(p); p.cancel(); }
            }
        } catch (Exception ignored) { }

        JSONArray codes = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                long at = r.getLong("at");
                if (at <= now) continue;
                int code = r.getString("id").hashCode();
                Intent it = new Intent(ctx, ReminderReceiver.class).setAction(ACTION_FIRE)
                    .putExtra("nid", code).putExtra("title", r.optString("title")).putExtra("body", r.optString("body"));
                PendingIntent p = PendingIntent.getBroadcast(ctx, code, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                codes.put(code);
            }
        } catch (Exception ignored) { }
        sp.edit().putString("list", json).putString("codes", codes.toString()).apply();
    }
}
