package com.guodaxia.blem3mapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

/** Owns the mapping lifetime, with an always available user stop action. */
public final class MappingForegroundService extends Service {
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String EXTRA_CONFIG = "config_json";
    private static final String CHANNEL = "mapping";
    private static final int NOTIFICATION = 30;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MapperConnection connection;
    private boolean stopping;
    private boolean starting;
    private boolean started;
    private boolean destroyed;
    private boolean cleanupIssued;
    private int generation;
    private int latestStartId;
    private String lastMessage = "正在连接控制服务…";

    private final MapperConnection.Listener connectionListener = () -> {
        if (destroyed || stopping || !(starting || started)) return;
        // A lost connection can cancel a pending start before the first status poll.
        // Do not leave a foreground service stuck at "正在连接" in that case.
        if (!connection.isBusy() && !connection.isRunning()) stopNow(true, null);
    };

    private void update(int request) {
        if (destroyed || stopping || request != generation) return;
        connection.refresh(snapshot -> {
            if (destroyed || stopping || request != generation) return;
            String message = MapperConnection.statusMessage(snapshot.status);
            if (!message.equals(lastMessage)) {
                lastMessage = message;
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
            }
            if (started && !starting && !snapshot.running) {
                stopNow(true, null);
                return;
            }
            main.postDelayed(() -> update(request), 1500);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        connection = MapperConnection.get(this);
        connection.addListener(connectionListener);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "遥控器映射", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示运行状态，并可随时停止映射");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopNow(intent == null, null);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopNow(true, "后台控制请求无效，请重新开始映射");
            return START_NOT_STICKY;
        }
        stopping = false;
        starting = true;
        started = false;
        cleanupIssued = false;
        main.removeCallbacksAndMessages(null);
        int request = ++generation;
        lastMessage = "正在连接控制服务…";
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification(lastMessage), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else startForeground(NOTIFICATION, notification(lastMessage));
        } catch (RuntimeException error) {
            stopNow(true, "无法启动后台控制，请在应用前台重新开始");
            return START_NOT_STICKY;
        }
        String config = intent.getStringExtra(EXTRA_CONFIG);
        if (config == null) {
            stopNow(true, "按键配置不可用，请保存配置后重试");
            return START_NOT_STICKY;
        }
        main.postDelayed(() -> {
            if (!destroyed && !stopping && starting && request == generation)
                stopNow(true, "启动超时，请检查 Shizuku 后重试");
        }, 20000);
        connection.startMapping(config, new MapperConnection.Result() {
            @Override public void onSuccess(String message) {
                // A superseded request must never stop the newest mapping session.
                if (destroyed || stopping || request != generation) return;
                starting = false;
                started = true;
                main.removeCallbacksAndMessages(null);
                main.post(() -> update(request));
            }
            @Override public void onError(String message) {
                if (destroyed || stopping || request != generation) return;
                Toast.makeText(MappingForegroundService.this, message, Toast.LENGTH_LONG).show();
                stopNow(true, message);
            }
        });
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MappingForegroundService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_remote).setContentTitle("遥控映射")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止映射", stop).build())
                .build();
    }

    private final MapperConnection.Result noResult = new MapperConnection.Result() {
        @Override public void onSuccess(String message) { }
        @Override public void onError(String message) { }
    };

    private void stopNow(boolean preserveStatus, String failure) {
        if (destroyed) return;
        if (stopping) {
            // A second STOP can have a newer startId than the pending completion.
            // Keep the original cleanup, but finish only after its own result arrives.
            return;
        }
        stopping = true;
        starting = false;
        started = false;
        cleanupIssued = true;
        int request = ++generation;
        main.removeCallbacksAndMessages(null);
        if (failure != null) connection.reportFailure(failure);
        MapperConnection.Result stopped = new MapperConnection.Result() {
            @Override public void onSuccess(String message) { finishStop(request); }
            @Override public void onError(String message) { finishStop(request); }
        };
        if (preserveStatus) connection.stopMappingKeepingStatus(stopped);
        else connection.stopMapping(stopped);
    }

    private void finishStop(int request) {
        if (destroyed || !stopping || request != generation) return;
        // A stale STOP must not tear down a newer START queued by Android.
        if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopping = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        if (connection != null) {
            connection.removeListener(connectionListener);
            // Normal STOP and failure paths already clean up. Repeating the call
            // here could cancel a newly started request and hide the failure text.
            if (!cleanupIssued) connection.stopMappingKeepingStatus(noResult);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
