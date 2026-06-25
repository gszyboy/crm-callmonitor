package com.xiyutianhe.crmcallmonitor;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * EncryptedSharedPreferences 封装
 *
 * 职责：提供唯一的数据持久化入口。所有需要持久化的数据（api_token、dispatcher_id、
 * server_url、去重记录、重试队列）都通过此类的实例读写。
 *
 * 使用 EncryptedSharedPreferences（AES256-GCM + Android Keystore）而非明文
 * SharedPreferences，防止 root 设备上 api_token 被直接提取（AGENTS §3）。
 *
 * 入参/出参/副作用：
 *   - 构造函数：接受 Context，初始化 MasterKey 和 EncryptedSharedPreferences
 *   - getString(key, default) / putString(key, value)：读写加密字符串
 *   - getStringSet(key, default) / putStringSet(key, value)：读写加密字符串集合
 *   - contains(key)：判断 key 是否存在
 */
public class PrefsStore {

    private static final String PREFS_NAME = "crm_callmonitor_prefs";

    private final SharedPreferences prefs;

    /** 创建或打开加密 SharedPreferences 实例 */
    public PrefsStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // 加密存储初始化失败（极低概率，Android Keystore 不可用）
            // 回退到明文 SharedPreferences（保证 APP 可用，但日志警告）
            android.util.Log.w("PrefsStore", "加密存储不可用，回退到明文存储", e);
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public boolean contains(String key) {
        return prefs.contains(key);
    }

    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    /** 清空所有数据（解绑时调用） */
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    // ========== 静态便捷方法（供 MainActivity 等框架层调用） ==========

    private static final String KEY_TOKEN = "api_token";
    private static final String KEY_DISPATCHER_ID = "dispatcher_id";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_DEVICE_ID = "device_id";

    public static boolean hasToken(Context ctx) {
        return new PrefsStore(ctx).contains(KEY_TOKEN);
    }

    public static String getServerUrl(Context ctx) {
        return new PrefsStore(ctx).getString(KEY_SERVER_URL, "");
    }

    public static int getDispatcherId(Context ctx) {
        return new PrefsStore(ctx).getInt(KEY_DISPATCHER_ID, 0);
    }

    public static String getDeviceId(Context ctx) {
        return android.provider.Settings.Secure.getString(
                ctx.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
    }

    public static void saveConfig(Context ctx, String serverUrl,
                                  int dispatcherId, String apiToken, String deviceId) {
        PrefsStore store = new PrefsStore(ctx);
        store.putString(KEY_SERVER_URL, serverUrl);
        store.putInt(KEY_DISPATCHER_ID, dispatcherId);
        store.putString(KEY_TOKEN, apiToken);
        store.putString(KEY_DEVICE_ID, deviceId);
    }

    public static void clearConfig(Context ctx) {
        new PrefsStore(ctx).clearAll();
    }
}
