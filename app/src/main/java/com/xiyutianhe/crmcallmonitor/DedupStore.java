package com.xiyutianhe.crmcallmonitor;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 持久化去重存储（DedupStore）
 *
 * 职责：记录"已成功推送"或"已在重试队列"的号码，10 秒内同一号码不重复推送。
 * 使用 SharedPreferences 持久化，进程重启后仍生效（AGENTS §8.3）。
 *
 * 存储格式（SharedPreferences key="dedup_map"）：
 *   JSON 字符串：{"13800138000": 1719400000123, "13900139000": 1719400005123}
 *   value 为毫秒时间戳
 *
 * 去重范围（v0.6 二更正，与 PRD §4.3 一致）：
 *   ✅ "已成功推送 200"的号码 → 记入去重
 *   ✅ "已在重试队列"的号码 → 记入去重
 *   ❌ "从未入队"的号码 → 不记入
 *
 * 入参/出参/副作用：
 *   - isDuplicate(caller)：检查号码是否在 10 秒内已推送/已入队
 *   - markPushed(caller)：标记号码为"已推送"
 *   - remove(caller)：从去重集合中移除（重试队列取出时调用）
 *   - clearExpired()：清理超过 10 秒的过期记录
 *   - clearAll()：清空所有记录（解绑时调用）
 */
public class DedupStore {

    private static final String TAG = "crmcallmonitor";
    private static final String DEDUP_KEY = "dedup_map";
    private static final long DEDUP_WINDOW_MS = 10_000L; // 10 秒

    private final PrefsStore prefsStore;

    public DedupStore(PrefsStore prefsStore) {
        this.prefsStore = prefsStore;
    }

    /**
     * 检查号码是否在 10 秒内已推送或已在重试队列
     *
     * @param caller 标准化后的来电号码
     * @return true 如果该号码在 10 秒内已处理过
     */
    public boolean isDuplicate(String caller) {
        if (caller == null || caller.isEmpty()) return false;

        String json = prefsStore.getString(DEDUP_KEY, "{}");
        try {
            JSONObject map = new JSONObject(json);
            if (map.has(caller)) {
                long timestamp = map.getLong(caller);
                long now = System.currentTimeMillis();
                if (now - timestamp < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "Dedup: " + caller + " already pushed within 10s");
                    return true;
                }
                // 过期了，清除它
                map.remove(caller);
                prefsStore.putString(DEDUP_KEY, map.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "DedupStore isDuplicate parse error", e);
        }
        return false;
    }

    /**
     * 标记号码为"已推送"或"已在重试队列"
     */
    public void markPushed(String caller) {
        if (caller == null || caller.isEmpty()) return;

        String json = prefsStore.getString(DEDUP_KEY, "{}");
        try {
            JSONObject map = new JSONObject(json);
            map.put(caller, System.currentTimeMillis());
            prefsStore.putString(DEDUP_KEY, map.toString());
            Log.d(TAG, "Dedup mark: " + caller);
        } catch (Exception e) {
            Log.e(TAG, "DedupStore markPushed error", e);
        }
    }

    /**
     * 从去重集合中移除号码（重试队列取出该条时调用）
     */
    public void remove(String caller) {
        if (caller == null || caller.isEmpty()) return;

        String json = prefsStore.getString(DEDUP_KEY, "{}");
        try {
            JSONObject map = new JSONObject(json);
            map.remove(caller);
            prefsStore.putString(DEDUP_KEY, map.toString());
            Log.d(TAG, "Dedup remove: " + caller);
        } catch (Exception e) {
            Log.e(TAG, "DedupStore remove error", e);
        }
    }

    /**
     * 清理所有超过 10 秒的过期记录（定期调用，非必须）
     */
    public void clearExpired() {
        String json = prefsStore.getString(DEDUP_KEY, "{}");
        try {
            JSONObject map = new JSONObject(json);
            long now = System.currentTimeMillis();
            boolean changed = false;
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long ts = map.getLong(key);
                if (now - ts >= DEDUP_WINDOW_MS) {
                    keys.remove();
                    changed = true;
                }
            }
            if (changed) {
                prefsStore.putString(DEDUP_KEY, map.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "DedupStore clearExpired error", e);
        }
    }

    /** 清空所有去重记录 */
    public void clearAll() {
        prefsStore.putString(DEDUP_KEY, "{}");
    }
}
