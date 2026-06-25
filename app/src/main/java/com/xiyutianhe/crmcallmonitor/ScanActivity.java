package com.xiyutianhe.crmcallmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

/**
 * 扫码页（ScanActivity）
 *
 * 职责：调起 zxing-android-embedded 库扫码，解析二维码 JSON 后
 * 通过 Intent 回传给 MainActivity 回填表单（AGENTS §8.6）。
 *
 * APK 不直接保存，给用户复核机会（AGENTS §5.1 / PRD §4.1.2）。
 *
 * 入参/出参/副作用：
 *   - onCreate()：启动 ZXing 扫码
 *   - onActivityResult()：处理扫码结果，解析 JSON，回传 MainActivity
 *
 * QR 内容格式（PRD §4.1.3）：
 *   { "server_url": "...", "dispatcher_id": 123, "code": "A8X9K2", "ts": 1719400000 }
 */
public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "crmcallmonitor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        // 使用 zxing-android-embedded 的 IntentIntegrator（AGENTS §8.6）
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setOrientationLocked(false);        // 支持横竖屏
        integrator.setBeepEnabled(false);              // 不发声
        integrator.setPrompt(getString(R.string.scan_prompt));
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE); // 只扫 QR
        integrator.initiateScan();  // 启动扫码
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            String qrContent = result.getContents();
            Log.d(TAG, "QR scanned: " + qrContent);

            // 解析 JSON
            try {
                JSONObject json = new JSONObject(qrContent);

                // 检查必填字段是否完整（PRD §4.1.2）
                if (!json.has("server_url") || !json.has("dispatcher_id")
                        || !json.has("code")) {
                    Toast.makeText(this, R.string.qr_format_error, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }

                String serverUrl = json.getString("server_url");
                int dispatcherId = json.getInt("dispatcher_id");
                String code = json.getString("code");

                // 检查 ts 字段，过期提示（APK 不强制校验 — PRD §4.1.2）
                if (json.has("ts")) {
                    long ts = json.getLong("ts");
                    long now = System.currentTimeMillis() / 1000;
                    if (now - ts > 600) { // 超过 10 分钟
                        Toast.makeText(this, R.string.qr_maybe_expired, Toast.LENGTH_LONG).show();
                    }
                }

                // 通过 Intent 回传数据给 MainActivity
                Intent resultIntent = new Intent();
                resultIntent.putExtra("server_url", serverUrl);
                resultIntent.putExtra("dispatcher_id", dispatcherId);
                resultIntent.putExtra("code", code);
                setResult(RESULT_OK, resultIntent);

            } catch (Exception e) {
                Log.e(TAG, "QR parse error", e);
                Toast.makeText(this, R.string.qr_format_error, Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
            }
        } else {
            // 用户取消扫码
            setResult(RESULT_CANCELED);
        }

        finish();
    }
}
