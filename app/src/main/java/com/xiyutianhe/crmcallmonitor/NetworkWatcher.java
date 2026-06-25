package com.xiyutianhe.crmcallmonitor;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

/**
 * 网络恢复监听（NetworkWatcher）
 *
 * 职责：监听系统网络状态变化。当网络从"无连接"变为"已连接"时，
 * 触发 RetryQueue.drain() 消费重试队列（AGENTS §8.4）。
 *
 * 使用 ConnectivityManager.NetworkCallback（Android 7+ 替代旧 API），
 * 无需注册 BroadcastReceiver。
 *
 * 入参/出参/副作用：
 *   - register()：注册网络回调
 *   - unregister()：注销网络回调
 *   - isNetworkAvailable()：检查当前网络是否可用（PRD §6.5 场景）
 */
public class NetworkWatcher {

    private static final String TAG = "crmcallmonitor";

    private final Context context;
    private final RetryQueue retryQueue;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile boolean registered = false;

    public NetworkWatcher(Context context, RetryQueue retryQueue) {
        this.context = context;
        this.retryQueue = retryQueue;
        this.connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * 注册网络恢复监听
     * 当网络从无连接变为有连接时，自动触发 RetryQueue.drain()
     */
    public void register() {
        if (registered || connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "network_recovered");
                // 网络恢复，触发重试队列消费
                retryQueue.drain();
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "network_lost");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                // no-op: 只关心 onAvailable
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        connectivityManager.registerNetworkCallback(request, networkCallback);
        registered = true;
        Log.d(TAG, "NetworkWatcher registered");
    }

    /**
     * 注销网络恢复监听（释放资源）
     */
    public void unregister() {
        if (registered && connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "unregister NetworkCallback error", e);
            }
            registered = false;
            Log.d(TAG, "NetworkWatcher unregistered");
        }
    }

    /**
     * 检查当前网络是否可用
     *
     * @return true 如果网络可用（有活跃的网络连接且可访问互联网）
     */
    public boolean isNetworkAvailable() {
        if (connectivityManager == null) return false;

        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) return false;

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Log.e(TAG, "isNetworkAvailable error", e);
            return false;
        }
    }

    /** 是否已注册 */
    public boolean isRegistered() {
        return registered;
    }
}
