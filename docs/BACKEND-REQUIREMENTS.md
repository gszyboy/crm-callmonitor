# 鸭暖屯泉 CRM 来电弹屏 - 后端需求文档

> **本文件是给后端/前端开发人员看的**：APK 跑起来需要后端提供什么能力、PC 端要做什么配合。
> **APK 自己的产品需求**见 `PRD-crm-callmonitor.md`。
> **APK 编码规范**见 `../AGENTS.md`。
>
> **维护责任**：本文件由 APK 端维护者（Hermes）创建。后端开发人员**可自由修改**（如已实现 / 有不同方案 / 决定不做等），**但修改前请通知 APK 端**。

---

## 1. APK 调用的 2 个接口

### 1.1 `POST /api/call/bind-confirm`

**用途**：APK 首次配置，绑定"这台手机"到"这个调度员"

```
请求方法: POST
请求 URL:  {SERVER_URL}/api/call/bind-confirm
请求体:    application/json
{
  "code": "A8X9K2",
  "dispatcher_id": 123,
  "device_id": "ANDROID_ID_xxx",
  "phone_brand": "Xiaomi",
  "phone_model": "Redmi Note 13",
  "android_version": "14"
}

成功响应 (200):
{
  "code": 0,
  "data": { "api_token": "tk_xxxxxxxxxx" }
}

错误响应:
400 绑定码无效或过期
400 设备已绑定其他调度员
5xx 服务器异常
```

**APK 行为**：
- 成功后保存 `api_token` 到本地加密存储
- 错误响应：提示用户对应错误

### 1.2 `POST /api/call/incoming`

**用途**：把来电号码推给后端

```
请求方法: POST
请求 URL:  {SERVER_URL}/api/call/incoming
Query 参数:
  caller=138xxxx
  dispatcher_id=123
  device_id=ANDROID_ID_xxx
  token=tk_xxxxxxxxxx
  ts=1719400000

成功响应 (200): { "code": 0, "msg": "ok" }
错误响应:
403 设备已解绑
404 调度员不存在
5xx 服务器异常
```

**APK 行为**：
| 响应 | 行为 |
|---|---|
| 200 | 静默成功；记入"已推送"集合（防止重推）；如在重试队列则删除该条 |
| 403 | 清掉本地 token；通知 MainActivity 显示"已解绑"；不入重试队列 |
| 404 | 通知 MainActivity 显示"调度员账号异常"；不入重试队列 |
| 5xx/超时/网络断开 | 入重试队列（最多 3 次）|

### 1.3 通用约定

- 协议：HTTPS（生产）/ HTTP（仅开发）
- 超时：APK 端 5 秒（连接 + 读取）
- 编码：UTF-8
- **鉴权方式**：query 参数传 token（APK 不存 JWT、不读 cookie）
- **HMAC 签名**：本期不做（保持简单）

---

## 2. 后端需要提供的能力（P0 必提供）

| # | 能力 | 说明 | APK 影响 |
|---|---|---|---|
| 1 | `POST /api/call/bind-confirm` 接口 | 接收 §1.1 格式的请求，返回 api_token | 没这接口 APK 装不上 |
| 2 | `POST /api/call/incoming` 接口 | 接收 §1.2 格式的请求 | 没这接口 APK 装上不能用 |
| 3 | HTTPS 部署 | 上述 2 个接口必须支持 HTTPS | 没 HTTPS APK 没法用（HTTP 仅开发）|
| 4 | 6 位绑定码生成能力 | 管理员能为 dispatcher 生成 6 位字母+数字绑定码（10 分钟有效）| 没这能力 APK 无法完成扫码绑定 |
| 5 | `admin_user` 表有 `role='dispatcher'` 用户 | 这是 APK 能绑定的前提（不是对客户公司人数的强制要求）| 没这前提 APK 无法绑定 |
| 6 | 设备绑定关系存储 | 记录"哪个 dispatcher 绑了哪台手机" | 没这表管理端无法管理设备 |
| 7 | 设备解绑能力（解绑后返回 403）| 管理员能解绑某 dispatcher 的某设备 | 没这能力设备丢了不能解绑 |
| 8 | PC 端管理后台生成二维码 | 内容见 §3 | 没这能力 APK 扫码绑定无法使用 |

---

## 3. PC 端管理后台要做什么

### 3.1 调度员管理页加"显示二维码"按钮

- 入口：管理端 → 调度员管理 → "生成绑定码"按钮旁 → "显示二维码"按钮
- 二维码内容：JSON 字符串（见下）
- 二维码生成：前端用 `qrcode.js` 等库生成图片
- 二维码有效期：与绑定码一致（10 分钟）

**二维码内容格式**：

```json
{
  "server_url": "https://crm.xiyutianhe.com",
  "dispatcher_id": 123,
  "code": "A8X9K2",
  "ts": 1719400000
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `server_url` | string | ✅ | 后端完整 URL（含 https://）|
| `dispatcher_id` | int | ✅ | 当前登录的调度员 ID |
| `code` | string | ✅ | 6 位绑定码（10 分钟内有效）|
| `ts` | int | ✅ | 二维码生成时间戳（秒）|

### 3.2 APK 安装包分发（由后端/前端负责）

APK 编译产出后（云打包），**由后端/前端把 APK 文件放到管理后台供下载**（扫码下载或直接点击下载）。APK 不管这件事。

---

## 4. 责任边界（明确说明）

| 范围 | 谁负责 |
|---|---|
| APK 端的 Java 代码、Gradle 构建、APK 签名打包 | Hermes（APK 端）|
| `POST /api/call/bind-confirm` 接口实现 | 后端开发人员 |
| `POST /api/call/incoming` 接口实现 | 后端开发人员 |
| 设备绑定关系存储的数据库表 | 后端开发人员 |
| 绑定码生成逻辑 | 后端开发人员 |
| 设备解绑能力 | 后端开发人员 |
| PC 端管理后台"显示二维码"功能 | admin 前端开发人员 |
| PC 端弹窗（来电时显示客户信息）| admin 前端开发人员 |
| WebSocket 推送机制 | 后端 + admin 前端协商 |
| 调度员账号 / 角色 / 权限 | 后端开发人员 |
| 统计 / 单点登录 / 固话 | 后端开发人员 |

---

## 5. APK 不依赖、不关心的事

APK 端**不关心**：

- ❌ 后端数据库表结构（什么表、什么字段）
- ❌ WebSocket 服务怎么部署
- ❌ PC 端弹窗怎么实现（用什么 UI 框架）
- ❌ 统计怎么做、用什么图表
- ❌ 单点登录怎么实现
- ❌ admin 前端怎么写

APK 只懂 HTTP POST。后端爱用什么数据库、什么技术栈、什么推送机制，APK 都不管。

---

## 6. APK 端技术细节（实现约束，供后端参考）

> 这些是 APK 端"做什么"，不是"怎么实现"。技术实现见 `../AGENTS.md` §3 §8。

| 行为 | 表现 |
|---|---|
| **设备唯一 ID** | 用 `Settings.Secure.ANDROID_ID`（不用 IMEI/MAC/OAID）|
| **号码去重** | 同一号码 10 秒内只推送 1 次（进程重启后仍生效）|
| **失败重试** | 网络错误入重试队列，最多重试 3 次 |
| **事件队列** | 同一秒多来电并发，串行处理不丢失 |
| **加密存储** | dispatcher_id 和 api_token 加密存本地（不存明文）|
| **进程保活** | 前台服务 + 状态栏常驻通知 + 引导用户加白名单 |

---

## 7. 文档版本

| 版本 | 日期 | 修改 | 作者 |
|---|---|---|---|
| v0.1 | 2026-06-26 | 初始版（从 PRD v0.7 四更拆出）| Hermes |
