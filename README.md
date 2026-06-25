# crm-callmonitor

鸭暖屯泉 CRM 来电弹屏 APK — 中心调度员来电推送工具。

## 目录

```
crm-callmonitor/
├── AGENTS.md                # AI 编码规范
├── app/                     # Android 工程（Gradle 构建）
├── docs/
│   ├── PRD-crm-callmonitor.md    # 产品需求
│   └── BACKEND-REQUIREMENTS.md   # 后端需求
├── .github/workflows/      # GitHub Actions 云打包
├── keystore/               # 签名配置说明
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 构建

本工程**不本地编译**，使用 GitHub Actions 云打包：

1. push 代码到 main 分支
2. GitHub Actions 自动产 APK
3. 在 Actions页面下载 `crm-callmonitor-release` 产物

或手动触发云打包：GitHub → Actions → Android APK Build → Run workflow

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Java 8 |
| 最低 SDK | 24 (Android 7.0) |
| 目标 SDK | 34 (Android 14) |
| 构建 | Gradle 8.x + AGP 8.x |
| HTTP | HttpURLConnection |

## 更多

- 产品需求：`docs/PRD-crm-callmonitor.md`
- 编码规范：`AGENTS.md`
- 后端需求：`docs/BACKEND-REQUIREMENTS.md`
