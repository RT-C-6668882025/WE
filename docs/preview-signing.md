# 固定预览签名

固定签名只作用于 `com.opc.moling.preview`，不会改变正式应用的签名。
PR 构建不读取签名密钥；仅 main 的 push / 手动构建使用配置。

在仓库 Settings → Secrets and variables → Actions 配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| WE_PREVIEW_KEYSTORE_B64 | 私有 JKS 文件的完整 Base64，单行 |
| WE_PREVIEW_STORE_PASSWORD | JKS 密码 |
| WE_PREVIEW_KEY_ALIAS | 签名别名 |
| WE_PREVIEW_KEY_PASSWORD | 对应私钥密码 |

再配置 Variables：

| Variable | 内容 |
| --- | --- |
| WE_PREVIEW_CERT_SHA256 | 证书 SHA-256 指纹（64 位十六进制，可带冒号） |
| WE_PREVIEW_SIGNING_REQUIRED | `true`，启用后缺少签名配置将阻止 main 构建发布 |

密钥应由维护者安全生成并独立备份；不要提交进仓库或发送到聊天。
通过 `keytool -list -v -keystore 私有文件.jks -alias 别名` 获取公开证书指纹。
配置完成后在 main 手动运行 Android native agent。流水线验证 APK 证书匹配，
临时密钥仅写入 runner 临时目录，权限 0600，结束时删除，不上传为构建产物。

现有 0.12 及之前的 CI 包使用临时签名。新密钥不能使这些已安装包直接覆盖升级；
固定签名启用后的版本之间才有相同签名这一升级前提。应用 ID 和 versionCode
也必须保持兼容。现有数据迁移需要独立方案，不能通过重签旧 APK 实现。

模拟器会在写入加密测试会话后执行 `adb install -r`，检查覆盖安装保留文件与
Android Keystore 身份。此检查使用同一 APK，不代表完成旧临时签名到新签名的迁移。

现有发布标签不会被覆盖。密钥配置成功后，需为首个固定签名版本递增 versionCode / versionName 并使用新发布标签；不得用不同证书替换已经分发的 0.12 包。
