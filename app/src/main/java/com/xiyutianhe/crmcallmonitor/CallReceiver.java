package com.xiyutianhe.crmcallmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * 来电广播监听（CallReceiver）
 *
 * 职责：监听 PHONE_STATE_CHANGED 系统广播，提取来电号码后提交给 EventExecutor
 * 串行处理（AGENTS §5.1 / PRD §4.2）。
 *
 * 严格区分来电状态机（PRD §6.3）：
 *   ✅ IDLE → RINGING：推送（取 EXTRA_INCOMING_NUMBER）
 *   ❌ IDLE → OFFHOOK：去电，不推送
 *   ❌ RINGING → OFFHOOK：接听，不推送（已处理过）
 *   ❌ RINGING → IDLE：挂断/未接，不推送（已处理过）
 *   ❌ OFFHOOK → IDLE：通话结束，不推送
 *   ✅ OFFHOOK → RINGING：呼叫等待，推送（新来电方）
 *
 * 实现要点（PRD §6.3）：
 *   - 只在 EXTRA_STATE_RINGING 状态时处理
 *   - state == RINGING && previousState != OFFHOOK 判断是新来电
 *     （防止 OFFHOOK → RINGING 被误判为去电 — 但这里取反：RINGING 时
 *      只要 previousState==OFFHOOK 就是呼叫等待，需要推送）
 *
 * BroadcastReceiver 内禁止直接更新 UI（AGENTS §4.4）
 *
 * 入参/出参/副作用：
 *   - onReceive()：接收广播，解析状态，提取号码，提交到 EventExecutor
 */
public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "crmcallmonitor";

    // 保存上一次状态用于呼叫等待判断
    private static String lastState = TelephonyManager.EXTRA_STATE_IDLE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;

        // 只在 RINGING 状态处理（PRD §6.3）
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            // 判断是否是真实的"新来电"（不是去电后回显）
            // 状态跳变 IDLE → RINGING：新来电 → 推送
            // 状态跳变 OFFHOOK → RINGING：呼叫等待 → 推送（PRD §6.3）
            // 状态跳变 RINGING → RINGING：不会发生
            if (TelephonyManager.EXTRA_STATE_IDLE.equals(lastState)
                    || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(lastState)) {

                boolean isOffhookToRing = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(lastState);

                String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                Log.i(TAG, "incoming_call caller=" + (incomingNumber != null ? incomingNumber : "null")
                        + " previousState=" + lastState
                        + " is_offhook_to_ring=" + isOffhookToRing);

                // 提交到 EventExecutor 串行处理
                EventExecutor executor = CallMonitorService.getEventExecutor();
                if (executor != null) {
                    executor.submitIncomingCall(incomingNumber);
                } else {
                    Log.w(TAG, "EventExecutor not available (service not running?)");
                }
            }
        }

        // 更新上一次状态
        lastState = state;
    }
}
