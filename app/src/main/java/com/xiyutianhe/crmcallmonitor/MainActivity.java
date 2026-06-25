package com.xiyutianhe.crmcallmonitor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 配置页（MainActivity）
 *
 * 职责：极简配置页（PRD §4.1），输入服务器地址、dispatcher_id、绑定码，
 * 提供扫一扫入口（PRD §4.1.2）。首次配置完成后启动 CallMonitorService
 * 进入后台运行模式。
 *
 * 工作流程（PRD §4.1）：
 *   1. 首次安装 → 显示表单 → 用户填/扫二维码 → 点保存并启动
 *   2. 已有配置 → 直接启动前台服务（不显示表单）
 *
 * 入参/出参/副作用：
 *   - onActivityResult()：接收 ScanActivity 返回的二维码数据
 *   - 成功后保存 api_token 到 PrefsStore，启动 CallMonitorService
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "crmcallmonitor";
    private static final int REQ_CODE_PHONE_STATE = 100;
    private static final int REQ_CODE_SCAN = 200;

    private EditText etServerUrl;
    private EditText etDispatcherId;
    private EditText etBindCode;
    private Button btnScan;
    private Button btnSave;
    private TextView tvStatus;

    private EventExecutor eventExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查是否已有配置（已有则直接启动服务）
        if (PrefsStore.hasToken(this)) {
            startMonitorService();
            finish(); // 不显示配置页
            return;
        }

        setContentView(R.layout.activity_main);

        etServerUrl = findViewById(R.id.et_server_url);
        etDispatcherId = findViewById(R.id.et_dispatcher_id);
        etBindCode = findViewById(R.id.et_bind_code);
        btnScan = findViewById(R.id.btn_scan);
        btnSave = findViewById(R.id.btn_save_and_start);
        tvStatus = findViewById(R.id.tv_status);

        // 预填上次未保存的配置（如果有）
        String savedUrl = PrefsStore.getServerUrl(this);
        int savedDispId = PrefsStore.getDispatcherId(this);
        if (!TextUtils.isEmpty(savedUrl)) {
            etServerUrl.setText(savedUrl);
        }
        if (savedDispId > 0) {
            etDispatcherId.setText(String.valueOf(savedDispId));
        }

        eventExecutor = new EventExecutor();

        // 扫一扫按钮
        btnScan.setOnClickListener(v -> {
            // 检查 CAMERA 权限（PRD §5 / PRD §4.1.2 流程图第 5 步）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA}, REQ_CODE_SCAN);
                return;
            }
            startScanActivity();
        });

        // 保存并启动按钮
        btnSave.setOnClickListener(v -> onSaveAndStart());

        // 申请必要权限
        requestRequiredPermissions();

        // 国产机白名单引导（PRD §6.1）
        BatteryOptHelper.showBatteryOptimizationGuideIfNeeded(this);
    }

    private void startScanActivity() {
        Intent intent = new Intent(this, ScanActivity.class);
        startActivityForResult(intent, REQ_CODE_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CODE_SCAN && resultCode == RESULT_OK && data != null) {
            // 回填扫码结果（PRD §4.1.2 第 6 步）
            if (data.hasExtra("server_url")) {
                etServerUrl.setText(data.getStringExtra("server_url"));
            }
            if (data.hasExtra("dispatcher_id")) {
                etDispatcherId.setText(String.valueOf(data.getIntExtra("dispatcher_id", 0)));
            }
            if (data.hasExtra("code")) {
                etBindCode.setText(data.getStringExtra("code"));
            }
            Toast.makeText(this, R.string.qr_filled, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CODE_SCAN) {
            // CAMERA 权限被授予后重试扫码
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanActivity();
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_CODE_PHONE_STATE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                // READ_PHONE_STATE 拒绝后核心功能不可用（PRD §6.1）
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_required_title)
                        .setMessage(R.string.phone_permission_required)
                        .setPositiveButton(R.string.go_to_settings, (d, w) -> {
                            // 引导用户去系统设置开启
                            BatteryOptHelper.openAppSettings(this);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        }
    }

    private void requestRequiredPermissions() {
        // READ_PHONE_STATE — 核心功能，必须申请（PRD §6.1）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_CODE_PHONE_STATE);
        }

        // POST_NOTIFICATIONS (Android 13+) — 可选
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
            }
        }
    }

    private void onSaveAndStart() {
        String serverUrl = etServerUrl.getText().toString().trim();
        String dispIdStr = etDispatcherId.getText().toString().trim();
        String code = etBindCode.getText().toString().trim();

        // 字段校验
        if (TextUtils.isEmpty(serverUrl)) {
            showStatus(R.string.error_server_url_required);
            return;
        }
        if (TextUtils.isEmpty(dispIdStr)) {
            showStatus(R.string.error_dispatcher_id_required);
            return;
        }
        if (TextUtils.isEmpty(code)) {
            showStatus(R.string.error_bind_code_required);
            return;
        }

        int dispatcherId;
        try {
            dispatcherId = Integer.parseInt(dispIdStr);
        } catch (NumberFormatException e) {
            showStatus(R.string.error_dispatcher_id_invalid);
            return;
        }

        btnSave.setEnabled(false);
        showStatus(R.string.status_binding);

        // 调用 bind-confirm 接口
        eventExecutor.submit(() -> {
            try {
                String deviceId = PrefsStore.getDeviceId(this);
                String brand = Build.MANUFACTURER;
                String model = Build.MODEL;
                String androidVer = Build.VERSION.RELEASE;

                String apiToken = HttpUtil.bindConfirm(
                        serverUrl, code, dispatcherId,
                        deviceId, brand, model, androidVer);

                if (apiToken != null) {
                    // 保存配置
                    PrefsStore.saveConfig(this, serverUrl, dispatcherId, apiToken, deviceId);

                    runOnUiThread(() -> {
                        showStatus(R.string.status_bind_success);
                        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
                        startMonitorService();
                        finish();
                    });
                } else {
                    runOnUiThread(() -> {
                        showStatus(R.string.status_bind_failed);
                        btnSave.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "bind-confirm failed", e);
                runOnUiThread(() -> {
                    showStatus(R.string.status_bind_network_error);
                    btnSave.setEnabled(true);
                });
            }
        });
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, CallMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void showStatus(int resId) {
        tvStatus.setText(resId);
        tvStatus.setVisibility(TextView.VISIBLE);
    }
}
