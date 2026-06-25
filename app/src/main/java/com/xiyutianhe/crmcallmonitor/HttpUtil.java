package com.xiyutianhe.crmcallmonitor;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * HTTP 推送工具
 *
 * 职责：提供 2 个静态 HTTP 方法给 EventExecutor 调用。
 *   - bindConfirm()：首次配置时调 bind-confirm 接口
 *   - pushIncoming()：每次来电时调 incoming 接口
 *
 * 使用 HttpURLConnection（JDK 自带），禁止 OkHttp/Retrofit（AGENTS §3）。
 * 禁止在主线程调用（AGENTS §4.4）：这两个方法会被 EventExecutor 串行线程池执行。
 *
 * 入参/出参/副作用：
 *   bindConfirm:
 *     入参：serverUrl, code, dispatcherId, deviceId, brand, model, androidVer
 *     返回：响应体字符串（通常是 JSON 或 token）
 *     副作用：建立 HTTP 连接，读响应
 *   pushIncoming:
 *     入参：serverUrl, dispatcherId, deviceId, token, caller, ts
 *     返回：HTTP 状态码（200=成功, 403=解绑, 404=账号异常, 5xx=失败）
 *     副作用：建立 HTTP 连接，读响应
 */
public final class HttpUtil {

    private static final String TAG = "crmcallmonitor";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    // 禁止实例化
    private HttpUtil() {}

    /**
     * bindConfirm — 首次配置时调用
     *
     * POST {serverUrl}/api/call/bind-confirm
     * Body: JSON { code, dispatcher_id, device_id, brand, model, android_ver }
     *
     * @param serverUrl    后端地址（例如 https://crm.example.com）
     * @param code         绑定码
     * @param dispatcherId 调度员 ID
     * @param deviceId     设备唯一 ID (ANDROID_ID)
     * @param brand        手机品牌 (Build.MANUFACTURER)
     * @param model        手机型号 (Build.MODEL)
     * @param androidVer   Android 版本 (Build.VERSION.RELEASE)
     * @return 响应体字符串（正常情况下包含 api_token），失败返回 null
     */
    public static String bindConfirm(String serverUrl, String code, int dispatcherId,
                                     String deviceId, String brand, String model,
                                     String androidVer) {
        try {
            String urlStr = serverUrl.replaceAll("/+$", "") + "/api/call/bind-confirm";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            // Build JSON body
            String jsonBody = "{"
                    + "\"code\":\"" + escapeJson(code) + "\","
                    + "\"dispatcher_id\":" + dispatcherId + ","
                    + "\"device_id\":\"" + escapeJson(deviceId) + "\","
                    + "\"brand\":\"" + escapeJson(brand) + "\","
                    + "\"model\":\"" + escapeJson(model) + "\","
                    + "\"android_ver\":\"" + escapeJson(androidVer) + "\""
                    + "}";

            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "bindConfirm responseCode=" + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } else {
                Log.w(TAG, "bindConfirm failed, code=" + responseCode);
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "bindConfirm network error", e);
            return null;
        }
    }

    /**
     * pushIncoming — 每次来电时调用
     *
     * POST {serverUrl}/api/call/incoming
     * Body: JSON { caller, dispatcher_id, device_id, ts }
     * Header: Authorization: Bearer {token}
     *
     * @param serverUrl    后端地址
     * @param dispatcherId 调度员 ID
     * @param deviceId     设备唯一 ID
     * @param token        api_token
     * @param caller       标准化后的来电号码
     * @param ts           当前时间戳（秒）
     * @return HTTP 响应码（200/403/404/5xx），-1 表示网络错误
     */
    public static int pushIncoming(String serverUrl, int dispatcherId, String deviceId,
                                   String token, String caller, long ts) {
        try {
            // 构建 URL（serverUrl + /api/call/incoming）
            String urlStr = serverUrl.replaceAll("/+$", "") + "/api/call/incoming";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            // Build JSON body
            String jsonBody = "{"
                    + "\"caller\":\"" + escapeJson(caller) + "\","
                    + "\"dispatcher_id\":" + dispatcherId + ","
                    + "\"device_id\":\"" + escapeJson(deviceId) + "\","
                    + "\"ts\":" + ts
                    + "}";

            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();

            int responseCode = conn.getResponseCode();

            // Read and discard response body to avoid connection leak
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                responseCode < 400 ? conn.getInputStream() : conn.getErrorStream(),
                                "UTF-8"));
                while (reader.readLine() != null) { /* discard */ }
                reader.close();
            } catch (IOException ignored) {
                // OK to ignore body read errors
            }

            // 不打印 token 到日志（AGENTS §4.5 / PRD §6.7）
            String safeCaller = caller != null ? caller : "null";
            Log.i(TAG, "pushIncoming caller=" + safeCaller + " code=" + responseCode);

            return responseCode;
        } catch (IOException e) {
            Log.e(TAG, "pushIncoming network error", e);
            // -1 表示网络错误（超时/连接失败/无网络）
            return -1;
        }
    }

    /**
     * 简单的 JSON 字符串转义（防止特殊字符导致 JSON 解析失败）
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
