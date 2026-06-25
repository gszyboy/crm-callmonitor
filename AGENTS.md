# AGENTS.md — 鸭暖屯泉 · crm-callmonitor（APK 端）

> **本文档是给 AI 编码助手看的"约束规范"**（即下一个进入这个目录的 AI 写代码时必须遵守的规则）。
> **产品需求请看 `docs/PRD-crm-callmonitor.md`**，本文档不重复产品需求。

---

## 0. 核心设计原则（v0.6 新增，**最重要，先看这**）

### 原则：**国产 Android 手机中保证高可用高可靠**

- 这是本模块**最重要的设计原则**，所有具体决策（技术栈选型/重试机制/厂商适配/依赖取舍）都从这条原则衍生
- **不为极简而极简**：能保证高可用高可靠的复杂度，**必须做**
- 国产 Android 手机特点：
  - 厂商魔改系统严重（华为/小米/OPPO/Vivo/三星）
  - 后台进程**经常被系统杀掉**（不像 Google 原生 Android）
  - 没有 GMS（Google Mobile Services），**不能依赖 ML Kit 等 Google 服务**
  - 杀后台策略不统一，需逐一适配

### 原则的 4 个具体落地

| 落地项 | 为什么要做 |
|---|---|
| 1. **重试队列**（网络错误时入队，重试 3 次）| 国产机网络切换频繁（4G ↔ WiFi ↔ 5G），瞬断常见，**不重试就丢来电** |
| 2. **持久化去重**（SharedPreferences 存最后推送的号码）| 国产机杀进程后**内存 Map 清空**，**不持久化就重复推送** |
| 3. **事件队列**（ExecutorService 单线程池）| 国产机 CPU 调度激进，**多广播并发会阻塞** |
| 4. **5 大厂商白名单跳转**（华为/小米/OPPO/Vivo/三星）| 国产机**不引导用户加白名单**，APP 必被杀，所有努力白费 |

### 不为极简而极简的边界

- ✅ 可以引成熟第三方库（如 zxing-android-embedded）
- ✅ 可以加复杂度（如重试/去重/事件队列/厂商适配）
- ❌ 但**不**做"HMAC 签名"等安全复杂度（彭立峰 v0.6 拍板 B，**安全让位于简洁**）
- ❌ 但**不**做"AI 通话"等业务扩展（v0.6 明确不做）

---

## 1. 模块定位

| 项 | 内容 |
|---|---|
| 模块名 | `crm-callmonitor` |
| 责任端 | **APK 端**（原生 Android 应用）|
| 责任内容 | Java 代码、Gradle 构建、APK 签名打包、APK 与后端 HTTP 接口的客户端实现 |
| **不责任** | 后端 API 实现、数据库表结构、WebSocket 服务、PC 端弹窗、统计看板 |

**绝对边界**：本目录**不包含**任何 PHP/ThinkPHP/数据库迁移/WebSocket/Nginx 配置代码。

---

## 2. 目录结构（v0.6 升级）

```
crm-callmonitor/
├── AGENTS.md
├── docs/
│   └── PRD-crm-callmonitor.md
├── README.md
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xiyutianhe/crmcallmonitor/
│       │   ├── MainActivity.java          # 配置页（含扫码入口）
│       │   ├── ScanActivity.java          # v0.6 新增：扫码页
│       │   ├── CallMonitorService.java    # 前台服务
│       │   ├── CallReceiver.java          # 监听来电广播
│       │   ├── BootReceiver.java          # 开机自启
│       │   ├── HttpUtil.java              # HTTP 推送
│       │   ├── RetryQueue.java            # v0.6 新增：重试队列
│       │   ├── DedupStore.java            # v0.6 新增：持久化去重
│       │   ├── EventExecutor.java         # v0.6 新增：串行事件队列
│       │   ├── NetworkWatcher.java        # v0.6 新增：网络恢复监听
│       │   ├── PrefsStore.java            # 加密存储
│       │   └── BatteryOptHelper.java      # 国产机白名单引导
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/activity_scan.xml   # v0.6 新增
│           ├── values/{strings,colors,themes}.xml
│           ├── drawable/ic_launcher_foreground.xml
│           └── mipmap-*/ic_launcher.png
├── .github/workflows/android-build.yml
├── keystore/README.md
├── .gitignore
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 3. 技术栈（AI 写代码时必须遵守）

| 项 | 值 | 禁止 |
|---|---|---|
| 语言 | **Java 8** | ❌ 不用 Kotlin（**AI 生成代码时 Java 8 报错率更低、调试更稳**）|
| 最低 SDK | 24（Android 7.0）| |
| 目标 SDK | 34（Android 14）| |
| 构建工具 | Gradle 8.x + Android Gradle Plugin 8.x | ❌ 不用 Maven/Ant |
| HTTP 客户端 | **HttpURLConnection**（JDK 自带）| ❌ 不用 OkHttp/Retrofit/Volley |
| 持久化 | **EncryptedSharedPreferences**（AndroidX Security）| ❌ 不用明文 SharedPreferences |
| 设备 ID | **Settings.Secure.ANDROID_ID** | ❌ 不用 IMEI/MAC/OAID |
| 事件队列 | **ExecutorService**（JDK 自带）| ❌ 不用 RxJava/Coroutine |
| 重试队列 | **SharedPreferences + 内存缓冲** | ❌ 不用 Room/SQLite/Redis |
| 扫码库 | **`com.journeyapps:zxing-android-embedded`** | ❌ 不用 ML Kit（国产机无 GMS 会失败）|
| 第三方依赖 | **2 个允许**：AndroidX Security + zxing-android-embedded | ❌ 不引其他 |

> **第三方库使用原则**：能"**有把握的"**就用 SDK 自带；**没有把握的**或**SDK 实现复杂**的，引一个成熟的第三方库是合理的（**不为极简而极简**）。每引一个库必须记录"为什么不能用 SDK 自带实现"。

---

## 4. 编码规范

### 4.1 基础

- 4 空格缩进
- 文件末尾保留一个空行
- 一行不超过 120 字符
- UTF-8 编码

### 4.2 命名

| 类型 | 规范 | 示例 |
|---|---|---|
| 类名 | PascalCase | `CallReceiver` |
| 方法/变量 | camelCase | `getDispatcherId()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_PUSH_RETRY` |
| 资源 ID | snake_case | `R.id.btn_save` |
| 布局文件 | snake_case | `activity_main.xml` |

### 4.3 包结构（平铺一个包，禁止分包）

```
com.xiyutianhe.crmcallmonitor
├── MainActivity
├── ScanActivity                 # v0.6 新增
├── CallMonitorService
├── CallReceiver
├── BootReceiver
├── HttpUtil
├── RetryQueue                   # v0.6 新增
├── DedupStore                   # v0.6 新增
├── EventExecutor                # v0.6 新增
├── NetworkWatcher               # v0.6 新增
├── PrefsStore
└── BatteryOptHelper
```

**禁止**分包。**禁止**创建子包如 `service/` `receiver/` `util/`。

### 4.4 主线程规则（**绝对禁止**违反）

- ❌ 禁止在主线程做网络 I/O
- ❌ 禁止在主线程做文件 I/O
- ❌ 禁止在主线程做 SharedPreferences 读写
- ✅ 网络/IO 放 `EventExecutor`（v0.6 新增的串行线程池）或 `new Thread()`
- ✅ UI 更新用 `runOnUiThread()` 切回主线程
- ✅ BroadcastReceiver 内禁止直接更新 UI（必须发本地广播通知 Activity）

### 4.5 异常处理

- 任何可能失败的调用必须 try-catch
- 异常**不弹 Toast** 给用户
- 异常**记 LogCat**：`Log.e("crmcallmonitor", "...", e)`
- 网络异常 → **入重试队列**（v0.6 行为），不直接丢弃

---

## 5. 必做/不做清单

### 5.1 必做

- 监听 `PHONE_STATE_CHANGED` 广播（state=RINGING 时取 `EXTRA_INCOMING_NUMBER`）
- **提交来电事件到 `EventExecutor` 串行处理**（v0.6 新增）
- **失败来电入 `RetryQueue`，网络恢复时重试**（v0.6 新增）
- **用 `DedupStore` 持久化去重**（v0.6 新增，进程重启仍生效）
- 启动前台服务（带状态栏通知）
- 开机自启
- HTTP POST 推来电号码到后端（接口约定见 PRD §6）
- 加密存储 api_token
- **点"扫一扫"按钮调起相机扫描二维码**（v0.6 新增）
- **解析二维码后只回填表单，不直接提交**（v0.6 新增，给用户复核机会）

### 5.2 绝不做（AI 禁止写这些代码）

- ❌ 不调用 `PhoneStateListener` 替代 BroadcastReceiver
- ❌ 不实现 `TelecomManager.acceptRingingCall()` / `endCall()`（不接管来电）
- ❌ 不读 `READ_CONTACTS` / `READ_CALL_LOG`
- ❌ 不申请 `SYSTEM_ALERT_WINDOW`
- ❌ 不写 UI 显示客户信息
- ❌ 不实现 WebSocket 客户端
- ❌ 不创建新的 Activity（除 MainActivity 和 ScanActivity 外）
- ❌ 不引入 Kotlin（用 Java 8）
- ❌ 不引入 OkHttp/Retrofit（用 HttpURLConnection）
- ❌ **不做 HMAC 签名**（v0.6 决策，保持简单）
- ❌ **不引第三方扫码库**（v0.6 决策：只允许 zxing-android-embedded 这一个；引其他扫码库要写理由）
- ❌ 不写后端代码

---

## 6. AndroidManifest 权限模板（v0.6 升级）

### 6.1 必申请

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.CAMERA"/>          <!-- v0.6 新增 -->
```

### 6.2 运行时动态申请

- `READ_PHONE_STATE`（MainActivity 启动时申请）
- `POST_NOTIFICATIONS`（启动前台服务前申请）
- `CAMERA`（v0.6 新增，ScanActivity 启动时申请）

### 6.3 绝不能加的权限

- ❌ `READ_CONTACTS`
- ❌ `READ_CALL_LOG`
- ❌ `ANSWER_PHONE_CALLS` / `MANAGE_OWN_CALLS`
- ❌ `CALL_PHONE`
- ❌ `SYSTEM_ALERT_WINDOW`
- ❌ `READ_SMS` / `RECEIVE_SMS`
- ❌ `RECORD_AUDIO`
- ❌ `READ_PHONE_NUMBERS`

---

## 7. 与后端的接口

> APK 只调 2 个接口。接口详细约定见 `docs/PRD-crm-callmonitor.md` §6。

**AI 在 `HttpUtil.java` 中实现**：

```java
String bindConfirm(String serverUrl, String code, int dispatcherId, 
                   String deviceId, String brand, String model, String androidVer);

int pushIncoming(String serverUrl, int dispatcherId, String deviceId,
                 String token, String caller, long ts);
```

**返回码约定**：

| 响应码 | APK 行为 |
|---|---|
| 200 | 静默成功；更新 `DedupStore` |
| 403 | 通知 MainActivity 显示"已解绑"；**不入重试队列** |
| 404 | 通知 MainActivity 显示"调度员账号异常"；**不入重试队列** |
| 5xx/超时/网络断开 | **入 `RetryQueue`**（最多 3 次）|

---

## 8. v0.6 新增：核心机制实现约束

### 8.1 事件队列（`EventExecutor.java`）

**AI 实现时**：

```java
// 单线程池，保证串行
private final ExecutorService executor = Executors.newSingleThreadExecutor();

// CallReceiver.onReceive() 内
executor.submit(() -> {
    handleIncomingCall(caller);  // 内部：去重 → HTTP → 失败入重试
});
```

**禁止**：
- ❌ 不用 RxJava / Coroutine
- ❌ 不创建多线程池（**只要 1 个**）
- ❌ 不用 HandlerThread / AsyncTask（已过时）

### 8.2 重试队列（`RetryQueue.java`）

**AI 实现时**：

```java
// 存储用 SharedPreferences（key: "pending_calls"）
// 格式：List of JSON {caller, ts, retry_count}

// 触发消费时机：
// 1. EventExecutor 处理新来电前
// 2. NetworkWatcher 检测到网络恢复时
// 3. MainActivity.onResume 时

// 最多重试 3 次（>3 次丢弃，记日志）
```

### 8.3 持久化去重（`DedupStore.java`）

**AI 实现时**：

```java
// 存储用 SharedPreferences（key: "last_pushed"）
// value: Map<号码, 时间戳>（**不是单个 key**，否则多号码并发时会错杀）

// 10 秒内同一号码只推 1 次（不论进程重启）

// 去重范围（v0.6 二更正，与 PRD §4.3 一致）：
// ✅ "已成功推送 200"的号码 → 记入去重
// ✅ "已在重试队列"的号码 → 记入去重（**不限于成功**）
// ❌ "从未入队"的号码 → 不记入（v0.6 初版写"只针对已成功"是错的，会导致重试 3 次失败后被错误重推）
```

### 8.4 网络恢复监听（`NetworkWatcher.java`）

**AI 实现时**：

```java
// 用 ConnectivityManager.NetworkCallback（Android 7+ 替代旧 API）
// 检测网络从"无连接"变"已连接"时，触发 RetryQueue.drain()
```

### 8.5 国产机白名单引导（`BatteryOptHelper.java`）

**5 大厂商 componentName 完整版**（AI 必须全部实现，每个都用 try-catch 包）：

```java
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
        } catch (ActivityNotFoundException e) {
            // 该厂商 ROM 改名了 → 走通用电池优化入口
            openBatteryOptimizationSettings(ctx);
        }
    } else {
        openBatteryOptimizationSettings(ctx);
    }
}

private static void openBatteryOptimizationSettings(Context ctx) {
    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
    intent.setData(Uri.parse("package:" + ctx.getPackageName()));
    try {
        ctx.startActivity(intent);
    } catch (Exception e) {
        // 设备不允许跳转，记日志
        Log.e("BatteryOptHelper", "无法跳转电池优化", e);
    }
}
```

**重要**：
- 每个 `startActivity` 都必须 try-catch（**国产 ROM 经常改 componentName**）
- 失败回退到通用电池优化入口
- **引导失败不影响 APP 工作**

### 8.6 二维码扫描（`ScanActivity.java`，v0.6 新增）

**AI 实现时**：

```java
// 用 zxing-android-embedded 库
// app/build.gradle 依赖：
// implementation 'com.journeyapps:zxing-android-embedded:4.3.0'

// ScanActivity.onCreate() 内：
IntentIntegrator integrator = new IntentIntegrator(this);
integrator.setOrientationLocked(false);
integrator.setBeepEnabled(false);
integrator.setPrompt("请扫描二维码");
integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
integrator.initiateScan();  // 启动扫码

// onActivityResult() 内：
IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
if (result != null && result.getContents() != null) {
    String qrContent = result.getContents();
    // 解析 JSON，回填 MainActivity 的 3 个字段
    parseAndFillBack(qrContent);
}
```

**QR 内容格式**（PRD §4.1.3）：
```json
{
  "server_url": "https://crm.xiyutianhe.com",
  "dispatcher_id": 123,
  "code": "A8X9K2",
  "ts": 1719400000
}
```

**回填方式**：
- ScanActivity 解析后，通过 `Intent.putExtra` 把 3 个字段传给 MainActivity
- **APK 不直接保存**（用户需在 MainActivity 手动点"保存"）

---

## 9. 构建与发布

### 9.1 打包方式

- **GitHub Actions 云打包**（`./github/workflows/android-build.yml`）
- **不依赖**本地 Android Studio
- push 到 main 分支自动触发

### 9.2 签名

- **自建 keystore**（`keytool` 生成）
- 存到 **GitHub Secrets**（不入代码仓库）

### 9.3 版本号

- `versionCode`：整数，每次构建 +1
- `versionName`：语义化（`1.0.0`）

---

## 10. 提交规范

- 格式：`<type>: <subject>`
- type：`feat` / `fix` / `docs` / `refactor` / `chore`
- 一次提交只做一件事
- 提交前 `./gradlew build` 通过

---

## 11. 子规范入口

- **产品需求**：`docs/PRD-crm-callmonitor.md`
- **主仓库规范**：`../AGENTS.md`（**主仓库维护者更新，不在本目录责任范围**）

---

## 12. 文档版本

| 版本 | 日期 | 修改 | 作者 |
|---|---|---|---|
| v0.1 | 2026-06-26 | 初始版 | Hermes |
| v0.2 | 2026-06-26 | 与 PRD 去重；包名修正 | Hermes |
| v0.3 | 2026-06-26 | 删 AndPermission/Android-Battery-Optimize 引用 | Hermes |
| v0.4 | 2026-06-26 | **彭立峰拍板 3 项关键决策**：① 4 项高可用全加（重试队列/持久化去重/事件队列/5 大厂商跳转）② HMAC 不做 ③ **新增二维码扫描**。新增 4 个 Java 类（RetryQueue/DedupStore/EventExecutor/NetworkWatcher/ScanActivity）。允许 1 个第三方扫码库（zxing-android-embedded）。APK 上限从 1MB 调为 2MB | Hermes |
| v0.5 | 2026-06-26 | **彭立峰纠正**：上一版"只用 Android SDK 自带、不引第三方库"的措辞**是我擅自加的**（彭立峰没说过）。改正：第三方库使用原则改为"**有把握的用 SDK 自带，没把握的引成熟库是合理的（不为极简而极简）**"，每引一个库必须记录理由 | Hermes |
| v0.6 | 2026-06-26 | **彭立峰明确核心设计原则"国产 Android 手机中保证高可用高可靠"**，加到文档最开头第 0 章。所有具体决策（重试/去重/事件队列/5 大厂商跳转/扫码库选型）都从此原则衍生 | Hermes |
| v0.7 | 2026-06-26 | **彭立峰要求审查重复**：AGENTS 保持原样不变（写给 AI 的约束就该详尽），PRD 删掉了重复内容（v0.7 PRD §5/§7/§8/§10 改为引用 AGENTS）| Hermes |
| v0.7 二更 | 2026-06-26 | **彭立峰要求严格审查逻辑 bug**：发现并修复 AGENTS §8.3 与 PRD §4.3 对"去重范围"的描述矛盾（AGENTS 写"只针对已成功"，PRD 写"已成功 OR 已在重试队列"）。统一为 PRD 的版本（v0.6 二更正），并修正"Map 不是单个 key"的实现细节 | Hermes |
