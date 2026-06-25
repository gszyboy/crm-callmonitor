package com.xiyutianhe.crmcallmonitor;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 重试队列（RetryQueue）
 *
 * 职责：网络错误入 RetryQueue，网络恢复/新来电前/Activity Resume 时触发消费。
 * 最多重试 3 次，超过则丢弃（记日志 push_giveup）。
 *
 * 存储方式：SharedPreferences（key="pending_calls"），JSON 数组格式。
 * 触发消费时机（AGENTS §8.2）：
 *   1. EventExecutor 处理新来电前
 *   2. NetworkWatcher 检测到网络恢复时
 *   3. MainActivity.onResume 时
 *
 * 入参/出参/副作用：
 *   - enqueue(caller, timestamp)：将来电加入重试队列
 *   - drain()：消费整个队列（逐条重试）
 *   - size()：队列当前大小
 *   - hasPending()：是否有未完成的重试
 */
public class RetryQueue {

    private static final String TAG = "crmcallmonitor";
    private static final String QUEUE_KEY = "pending_calls";
    private static final int MAX_RETRY = 3;

    private final Context context;
    private final PrefsStore prefsStore;
    private final EventExecutor eventExecutor;
    private final DedupStore dedupStore;

    public RetryQueue(Context context, PrefsStore prefsStore,
                      EventExecutor eventExecutor, DedupStore dedupStore) {
        this.context = context;
        this.prefsStore = prefsStore;
        this.eventExecutor = eventExecutor;
        this.dedupStore = dedupStore;
    }

    /**
     * 将来电加入重试队列
     *
     * @param caller    标准化后的来电号码
     * @param timestamp 来电时间戳
     */
    public void enqueue(String caller, long timestamp) {
        List<RetryItem> items = loadAll();
        // 检查是否已存在同一号码且未超重试次数的条目
        for (RetryItem item : items) {
            if (item.caller.equals(caller) && item.retryCount < MAX_RETRY) {
                // 已存在，不重复入队（去重已在 DedupStore 层面完成）
                Log.d(TAG, "RetryQueue: " + caller + " already in queue, skip enqueue");
                return;
            }
        }
        items.add(new RetryItem(caller, timestamp, 0));
        saveAll(items);
        Log.i(TAG, "RetryQueue enqueue: " + caller + " (queue size=" + items.size() + ")");
    }

    /**
     * 消费整个队列：逐条重试，成功删除，失败计数+1，超过 MAX_RETRY 丢弃
     * 由 EventExecutor 串行执行（不并发，避免后端过载 — PRD §9.8 TC-52）
     */
    public void drain() {
        List<RetryItem> items = loadAll();
        if (items.isEmpty()) return;

        Log.i(TAG, "RetryQueue drain start, size=" + items.size());

        List<RetryItem> remaining = new ArrayList<>();
        for (RetryItem item : items) {
            if (item.retryCount >= MAX_RETRY) {
                // 超过最大重试次数，丢弃
                Log.w(TAG, "push_giveup caller=" + item.caller
                        + " total_retry_count=" + item.retryCount);
                // 从去重中移除，允许后续新来电重新入队
                dedupStore.remove(item.caller);
                continue;
            }

            // 执行重试（在 EventExecutor 线程中，已经串行）
            boolean success = eventExecutor.retrySingleCall(item);
            if (success) {
                // 重试成功，从去重中移除（已在 EventExecutor 中处理）
                Log.i(TAG, "push_retry_success caller=" + item.caller
                        + " total_retry_count=" + (item.retryCount + 1));
            } else {
                // 重试失败，保留并增加计数
                remaining.add(new RetryItem(item.caller, item.timestamp, item.retryCount + 1));
            }
        }

        saveAll(remaining);
        Log.i(TAG, "RetryQueue drain end, remaining=" + remaining.size());
        if (remaining.isEmpty()) {
            Log.i(TAG, "queue_drained");
        }
    }

    /** 队列中待重试的数量 */
    public int size() {
        return loadAll().size();
    }

    /** 是否有未完成的重试 */
    public boolean hasPending() {
        return size() > 0;
    }

    // ==================== 内部存储 ====================

    private List<RetryItem> loadAll() {
        String json = prefsStore.getString(QUEUE_KEY, "[]");
        List<RetryItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                items.add(new RetryItem(
                        obj.getString("caller"),
                        obj.getLong("ts"),
                        obj.optInt("retry_count", 0)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "RetryQueue loadAll error", e);
        }
        return items;
    }

    private void saveAll(List<RetryItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (RetryItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("caller", item.caller);
                obj.put("ts", item.timestamp);
                obj.put("retry_count", item.retryCount);
                arr.put(obj);
            }
            prefsStore.putString(QUEUE_KEY, arr.toString());
        } catch (Exception e) {
            Log.e(TAG, "RetryQueue saveAll error", e);
        }
    }

    /** 重试队列条目 POJO */
    public static class RetryItem {
        public final String caller;
        public final long timestamp;
        public final int retryCount;

        public RetryItem(String caller, long timestamp, int retryCount) {
            this.caller = caller;
            this.timestamp = timestamp;
            this.retryCount = retryCount;
        }
    }
}
