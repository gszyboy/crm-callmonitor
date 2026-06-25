# 签名说明

> **本文件只记录 keystore 生成和配置步骤。**.keystore 文件本身**不入代码仓库**，存入 GitHub Secrets。

## 生成 keystore

首次运行前，在本地执行：

```bash
keytool -genkey -v -keystore release.keystore \
  -alias crmcallmonitor \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass <你的密码> -keypass <你的密码> \
  -dname "CN=CallMonitor, O=XiYuTianHe, C=CN"
```

## 配置 GitHub Secrets

将生成的 `.keystore` 编码为 Base64：

```bash
base64 release.keystore > release.keystore.b64
```

然后在 GitHub 仓库 → Settings → Secrets → Actions 添加：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | `release.keystore.b64` 文件内容 |
| `KEYSTORE_PASSWORD` | 你的 storepass |
| `KEY_ALIAS` | `crmcallmonitor` |
| `KEY_PASSWORD` | 你的 keypass |

## 注意

- 密码丢失后需重新生成 keystore
- 用户端覆盖安装时必须使用**同一个** keystore 签名
- 不上架的 APK 可用调试签名（默认 Android debug 证书）
