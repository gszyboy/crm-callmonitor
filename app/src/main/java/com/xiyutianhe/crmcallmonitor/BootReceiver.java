package com.xiyutianhe.crmcallmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（BootReceiver）
 *
 * 职责：手机开机后自动启动 CallMonitorService 前台服务，保证
 * APP 不需要用户手动打开也能正常工作（AGENTS §5.1 / PRD §9.5 TC-34）。
 *
 * 入参/出参/副作用：
 *   - onReceive()：收到 BOOT_COMPLETED/QUICKBOOT_POWERON 广播后启动前台服务
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "crmcallmonitor";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "app_killed action=" + action);

        // 处理开机完成（包括华为/小米的快启广播）
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // 检查是否已有配置（有 token 表示曾经绑定过）
            PrefsStore prefsStore = new PrefsStore(context);
            String token = prefsStore.getString("api_token", null);
            int dispatcherId = prefsStore.getInt("dispatcher_id", 0);

            if (token != null && dispatcherId > 0) {
                // 启动前台服务
                Intent serviceIntent = new Intent(context, CallMonitorService.class);
                serviceIntent.putExtra("start_source", "boot");
                if (BuildUtils.hasO()) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.i(TAG, "BootReceiver: service started (dispatcher_id=" + dispatcherId + ")");
            } else {
                Log.i(TAG, "BootReceiver: no saved config, skip service start");
            }
        }
    }
}
