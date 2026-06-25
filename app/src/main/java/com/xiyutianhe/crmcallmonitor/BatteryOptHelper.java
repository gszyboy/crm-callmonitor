package com.xiyutianhe.crmcallmonitor;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

/**
 * 国产机白名单引导（BatteryOptHelper）
 *
 * 职责：提供引导用户将 APP 加入系统白名单的弹窗和跳转逻辑。
 * 支持 5 大厂商（华为/小米/OPPO/Vivo/三星）+ 通用电池优化入口。
 *
 * 5 大厂商 componentName 完整版（AGENTS §8.5）：
 *   华为 com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity
 *   小米 com.miui.securitycenter / com.miui.permcenter.autostart.AutoStartManagementActivity
 *   OPPO com.coloros.safecenter.permission.startup.StartupAppListActivity
 *   Vivo com.vivo.permissionmanager.activity.BgStartUpManagerActivity
 *   三星 com.samsung.android.lool / com.samsung.android.sm.battery.ui.BatteryActivity
 *
 * 每个 startActivity 都用 try-catch 包；失败回退到通用电池优化入口（AGENTS §8.5 重要）。
 *
 * 入参/出参/副作用：
 *   - showBatteryOptDialog(ctx)：弹引导弹窗
 *   - openStartupSettings(ctx)：直接跳厂商白名单页
 *   - openBatteryOptimizationSettings(ctx)：通用电池优化入口
 */
public final class BatteryOptHelper {

    private static final String TAG = "crmcallmonitor";

    // 引导弹窗已显示的天 key（SharedPreferences）
    private static final String PREF_LAST_GUIDE_DAY = "last_battery_guide_day";

    private BatteryOptHelper() {}

    /**
     * 弹出自名单引导弹窗（PRD §6.1）
     * 每天最多显示 1 次（AGENTS §8.5 约束）
     *
     * @param context Context
     * @param prefsStore PrefsStore（用于记录上次引导日期）
     */
    public static void showBatteryOptDialog(final Context context, final PrefsStore prefsStore) {
        // 每天最多弹 1 次
        int today = (int) (System.currentTimeMillis() / 86400_000L);
        int lastDay = prefsStore.getInt(PREF_LAST_GUIDE_DAY, 0);
        if (lastDay == today) {
            return; // 今天已经弹过了
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("后台运行引导");
        builder.setMessage("为确保来电能正常推送，请允许 APP 在后台运行");
        builder.setPositiveButton("去设置", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openStartupSettings(context);
                prefsStore.putInt(PREF_LAST_GUIDE_DAY, (int) (System.currentTimeMillis() / 86400_000L));
            }
        });
        builder.setNeutralButton("明天再提醒", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                prefsStore.putInt(PREF_LAST_GUIDE_DAY, (int) (System.currentTimeMillis() / 86400_000L));
            }
        });
        builder.setNegativeButton("稍后", null);
        builder.setCancelable(true);
        builder.show();
    }

    /**
     * 跳转到厂商白名单/自启动设置页（AGENTS §8.5 完整版）
     * 每个 startActivity 都用 try-catch 包，失败回退到通用电池优化入口
     */
    public static void openStartupSettings(Context ctx) {
        String brand = Build.MANUFACTURER.toLowerCase();
        Intent intent = null;

        if (brand.contains("huawei")) {
            intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ));
        } else if (brand.contains("xiaomi") || brand.contains("redmi")) {
            intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ));
        } else if (brand.contains("oppo")) {
            intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ));
        } else if (brand.contains("vivo")) {
            intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ));
        } else if (brand.contains("samsung")) {
            intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
            ));
        }

        if (intent != null) {
            try {
                ctx.startActivity(intent);
                Log.i(TAG, "openStartupSettings: " + brand);
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "厂商 ROM 改名，回退到通用电池优化入口", e);
                openBatteryOptimizationSettings(ctx);
            }
        } else {
            // 非 5 大厂商，直接走通用入口
            openBatteryOptimizationSettings(ctx);
        }
    }

    /**
     * 通用电池优化入口
     * 跳转到系统设置 → 电池优化 → 选择本 APP 设置为"不优化"
     */
    private static void openBatteryOptimizationSettings(Context ctx) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + ctx.getPackageName()));
        try {
            ctx.startActivity(intent);
            Log.i(TAG, "openBatteryOptimizationSettings");
        } catch (Exception e) {
            Log.e(TAG, "无法跳转电池优化", e);
        }
    }
}
