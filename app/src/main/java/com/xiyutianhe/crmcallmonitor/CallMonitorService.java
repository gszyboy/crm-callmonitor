package com.xiyutianhe.crmcallmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务（CallMonitorService）
 *
 * 职责：提供前台服务保持进程存活。状态栏常驻通知"📞 来电推送助手 运行中"。
 * 同时持有 EventExecutor、RetryQueue、DedupStore、NetworkWatcher 等核心组件实例。
 *
 * 这是 APP 的核心"后台进程"，从 MainActivity 启动。
 * 进程被系统杀掉后，BootReceiver 会重新启动（PRD §6.5 / TC-33）。
 *
 * 入参/出参/副作用：
 *   - onCreate()：初始化核心组件
 *   - onStartCommand()：启动前台通知
 *   - onDestroy()：释放资源
 *   - getEventExecutor()：静态方法，供 CallReceiver 获取实例
 */
public class CallMonitorService extends Service {

    private static final String TAG = "crmcallmonitor";
    private static final int NOTIFICATION_ID = 1001;

    // 核心组件实例
    private static EventExecutor eventExecutor;
    private static RetryQueue retryQueue;
    private static DedupStore dedupStore;
    private static NetworkWatcher networkWatcher;
    private static PrefsStore prefsStore;

    private static boolean running = false;

    /** 供 CallReceiver 获取 EventExecutor 实例提交来电事件 */
    public static EventExecutor getEventExecutor() {
        return eventExecutor;
    }

    /** 服务是否在运行 */
    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CallMonitorService onCreate");

        // 初始化各组件（注意依赖顺序：prefs → dedup → eventExecutor → retryQueue → networkWatcher）
        prefsStore = new PrefsStore(this);
        dedupStore = new DedupStore(prefsStore);
        eventExecutor = new EventExecutor(dedupStore, dedupStore);
        retryQueue = new RetryQueue(this, prefsStore, eventExecutor, dedupStore);
        networkWatcher = new NetworkWatcher(this, retryQueue);
        // 设置 retryQueue 到 eventExecutor
        eventExecutor.setRetryQueue(retryQueue);

        // 设置回调（解绑/账号异常通知 MainActivity）
        eventExecutor.setCallback(new EventExecutor.Callback() {
            @Override
            public void onUnboundDetected() {
                Log.w(TAG, "unbound_detected");
                handleUnbound();
            }

            @Override
            public void onAccountError() {
                Log.w(TAG, "account_error");
                handleAccountError();
            }
        });

        // 加载配置
        loadConfig();

        // 注册网络监听
        networkWatcher.register();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "CallMonitorService onStartCommand");

        // 创建通知渠道（Android 8+ 必须）
        createNotificationChannel();

        // 构建常驻通知
        Notification notification = buildNotification();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);
        running = true;

        // 如果服务被系统杀掉，重新创建（PRD §6.5）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "app_killed");
        running = false;

        // 释放资源
        if (networkWatcher != null) {
            networkWatcher.unregister();
        }
        if (eventExecutor != null) {
            eventExecutor.destroy();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 不提供绑定
    }

    // ==================== 内部方法 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    getString(R.string.channel_id_callmonitor),
                    getString(R.string.channel_name_callmonitor),
                    NotificationManager.IMPORTANCE_LOW // 低优先级，不弹出
            );
            channel.setDescription("来电推送服务运行通知");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        // 点击通知打开 MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, getString(R.string.channel_id_callmonitor))
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_running))
                .setSmallIcon(android.R.drawable.ic_menu_call) // 使用系统图标，避免自定义图标问题
                .setContentIntent(pendingIntent)
                .setOngoing(true) // 不可划掉
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** 从 PrefsStore 加载配置到 EventExecutor */
    private void loadConfig() {
        String serverUrl = prefsStore.getString("server_url", null);
        int dispatcherId = prefsStore.getInt("dispatcher_id", 0);
        String deviceId = prefsStore.getString("device_id", null);
        String token = prefsStore.getString("api_token", null);

        if (serverUrl != null && token != null && deviceId != null && dispatcherId > 0) {
            eventExecutor.setConfig(serverUrl, dispatcherId, deviceId, token);
            Log.i(TAG, "Config loaded: dispatcher_id=" + dispatcherId);
        } else {
            Log.w(TAG, "Config not fully available");
        }
    }

    /** 处理解绑（清空本地数据） */
    private void handleUnbound() {
        prefsStore.clearAll();
        // 发送本地广播通知 MainActivity（如果正在前台）
        Intent intent = new Intent("com.xiyutianhe.crmcallmonitor.UNBOUND");
        sendBroadcast(intent);
        // 停止服务
        stopSelf();
    }

    /** 处理账号异常 */
    private void handleAccountError() {
        // 发送本地广播通知 MainActivity
        Intent intent = new Intent("com.xiyutianhe.crmcallmonitor.ACCOUNT_ERROR");
        sendBroadcast(intent);
    }
}
