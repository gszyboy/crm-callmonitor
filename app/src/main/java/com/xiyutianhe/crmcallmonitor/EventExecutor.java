package com.xiyutianhe.crmcallmonitor;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 串行事件队列（EventExecutor）
 *
 * 职责：提供串行化的事件处理。所有来电事件都提交到此队列，保证同一时刻
 * 只有一个事件在处理（AGENTS §8.1）。防止国产机 CPU 调度激进导致的并发问题。
 *
 * 使用 Executors.newSingleThreadExecutor() — JDK 自带，禁止 RxJava/Coroutine
 * /HandlerThread（AGENTS §8.1 约束）。
 *
 * 内部包含核心业务逻辑：号码标准化、去重检查、HTTP 推送、失败入重试队列。
 *
 * 入参/出参/副作用：
 *   - submitIncomingCall(caller)：提交来电事件到队列
 *   - retrySingleCall(item)：供 RetryQueue.drain() 调用，重试单条
 *   - destroy()：关闭线程池
 */
public class EventExecutor {

    private static final String TAG = "crmcallmonitor";

    // 单线程池，保证串行（AGENTS §8.1）
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RetryQueue retryQueue;
    private final DedupStore dedupStore;

    // 当前配置（由 MainActivity 设置）
    private volatile String serverUrl;
    private volatile int dispatcherId;
    private volatile String deviceId;
    private volatile String token;

    // 回调接口（通知 MainActivity 状态变化）
    public interface Callback {
        void onUnboundDetected();
        void onAccountError();
    }

    private Callback callback;

    public EventExecutor(DedupStore dedupStore) {
        this.dedupStore = dedupStore;
    }

    /** 设置重试队列引用（初始化时由 CallMonitorService 调用） */
    public void setRetryQueue(RetryQueue retryQueue) {
        this.retryQueue = retryQueue;
    }

    /** 设置当前配置（由 MainActivity 启动服务时调用） */
    public void setConfig(String serverUrl, int dispatcherId, String deviceId, String token) {
        this.serverUrl = serverUrl;
        this.dispatcherId = dispatcherId;
        this.deviceId = deviceId;
        this.token = token;
    }

    /** 设置回调 */
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 提交来电事件到串行队列
     *
     * @param rawCaller 原始来电号码（EXTRA_INCOMING_NUMBER 的值）
     */
    public void submitIncomingCall(String rawCaller) {
        executor.submit(() -> handleIncomingCall(rawCaller));
    }

    /**
     * 供 RetryQueue.drain() 调用的重试方法
     *
     * @param item 重试条目
     * @return true 重试成功，false 重试失败（需继续排队）
     */
    public boolean retrySingleCall(RetryQueue.RetryItem item) {
        return doPush(item.caller);
    }

    /**
     * 核心：处理来电（标准化 → 去重 → 推送 → 失败入重试）
     */
    private void handleIncomingCall(String rawCaller) {
        // 1. 号码标准化
        String normalized = normalizeCaller(rawCaller);
        if (normalized == null) {
            Log.i(TAG, "incoming_call caller=" + (rawCaller != null ? rawCaller : "null")
                    + " normalized_caller=null (skipped)");
            return;
        }

        // 2. 先消费重试队列（AGENTS §8.2 触发时机 1）
        retryQueue.drain();

        // 3. 去重检查
        if (dedupStore.isDuplicate(normalized)) {
            Log.d(TAG, "incoming_call caller=" + rawCaller
                    + " normalized=" + normalized + " (dedup, skip)");
            return;
        }

        // 4. 标记为"已在处理"
        dedupStore.markPushed(normalized);

        // 5. 执行推送
        boolean success = doPush(normalized);

        if (success) {
            Log.i(TAG, "push_success caller=" + normalized + " ts=" + System.currentTimeMillis());
            // 已成功，DedupStore 中已有记录，无需额外操作
        }
        // 失败时 doPush 已处理入重试队列
    }

    /**
     * 执行实际的 HTTP 推送
     *
     * @param caller 标准化后的号码
     * @return true 推送成功（200）；false 失败（需要重试或已处理业务错误）
     */
    private boolean doPush(String caller) {
        if (serverUrl == null || token == null || deviceId == null) {
            Log.w(TAG, "push failed: config not ready");
            return false;
        }

        long ts = System.currentTimeMillis() / 1000;
        int responseCode = HttpUtil.pushIncoming(serverUrl, dispatcherId, deviceId,
                token, caller, ts);

        switch (responseCode) {
            case 200:
                // 成功
                return true;

            case 403:
                // 已解绑 — 不入重试队列（AGENTS §7 / PRD §6.6）
                Log.w(TAG, "push_failed_403 caller=" + caller);
                dedupStore.remove(caller);
                if (callback != null) {
                    callback.onUnboundDetected();
                }
                return false;

            case 404:
                // 调度员账号异常 — 不入重试队列
                Log.w(TAG, "push_failed_404 caller=" + caller);
                dedupStore.remove(caller);
                if (callback != null) {
                    callback.onAccountError();
                }
                return false;

            default:
                // 5xx / 超时 / 网络断开 — 入重试队列
                int retryCount = 0; // 新入队，重试计数由 RetryQueue 管理
                Log.w(TAG, "push_failed_network caller=" + caller
                        + " retry_count=" + retryCount + " code=" + responseCode);
                retryQueue.enqueue(caller, System.currentTimeMillis());
                return false;
        }
    }

    /**
     * 号码标准化（PRD §6.4 表格逻辑实现）
     *
     * 规则（按优先级）：
     *   1. null 或空 → null
     *   2. 包含 * 或 # → null（特服号/测试号）
     *   3. 以 + 开头但不是 +86 → null（国际号码，本期不支持）
     *   4. 以 +86 开头 → 去掉 +86 后继续
     *   5. 去前导 0
     *   6. 非纯数字或非 11 位 → null
     *   7. 返回标准化号码
     *
     * 注意：PRD §6.4 伪代码中 "if not rawNumber 以 + 开头: return null"
     * 与表格（0 开头→推送）矛盾，这里按表格实现（伪代码为该字段笔误）。
     *
     * @param rawNumber 原始来电号码（EXTRA_INCOMING_NUMBER 的值）
     * @return 标准化后的号码，或 null（不应推送）
     */
    public static String normalizeCaller(String rawNumber) {
        if (rawNumber == null || rawNumber.isEmpty()) {
            return null;
        }

        // 包含 * 或 # 的特服号/测试号（PRD §6.4）
        if (rawNumber.contains("*") || rawNumber.contains("#")) {
            return null;
        }

        String number = rawNumber.trim();

        // 以 + 开头但不是 +86 → 国际号码，本期不支持（PRD §6.4）
        if (number.startsWith("+") && !number.startsWith("+86")) {
            return null;
        }

        // 处理 +86 前缀
        if (number.startsWith("+86")) {
            number = number.substring(3);
        }

        // 去前导 0（PRD §6.4：0 开头标准化后推送）
        while (number.startsWith("0")) {
            number = number.substring(1);
        }

        // 双卡副卡来电：EXTRA_INCOMING_NUMBER 可能为空（已在前面处理）
        // 校验是否为 11 位纯数字（标准手机号）
        if (number.length() != 11 || !number.matches("\\d{11}")) {
            return null;
        }

        return number;
    }

    /** 释放资源 */
    public void destroy() {
        executor.shutdown();
    }
}
