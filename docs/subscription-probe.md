# ChatGPT 订阅：Android 最小验证

状态：实验性、debug-only；不构成已验证的订阅 Provider。2026-10-28。

## 实现范围

本分支加入独立 launcher「Moling 订阅验证」。debug applicationId 为 `com.opc.moling.subscriptionprobe`，与正式墨灵隔离；release 不包含验证 Activity/协议代码，原 MainActivity 和 Anthropic 接口保持原状。没有引入其他 App、Node.js、React 或新的生产依赖。

已按官方文档独立实现：

- `127.0.0.1` 随机端口回调，先监听再打开系统浏览器；5 分钟授权超时、取消关闭监听。
- 动态客户端注册、稳定 host ID、注册 ID 重用、PKCE S256、state/nonce 校验、重复参数/客户端置换拒绝。
- 从固定官方 OIDC discovery 获取端点，仅接受 auth.openai.com HTTPS 端点；OpenAI JWKS 的 RS256 ID Token 验签及 issuer/audience/azp/expiry/nonce/subject 校验。
- 检查实际 token response 的订阅 scope；登录成功与订阅权限独立判断。
- 官方账号模型列表；文本测试；本地生成「7 + 5 = ?」PNG 的图像测试。
- Responses 使用公开 `https://api.openai.com/v1/responses`，`store:false`、`stream:true`；只有 `response.completed` 才算传输成功，中断/限额/失败/未完成单独处理；输出还检查预期答案。
- 接近过期时手动刷新，串行处理轮换；刷新没有新 ID Token 时保留原已验证身份，有新 ID Token 则重新验证。验证失败需重新登录，不重用可能已消费的旧 refresh token。
- 主动退出尝试官方 session revocation；远端失败明确告知。使用配额错误不自动改用 API Key 计费路径。

令牌只在内存：不写磁盘、SharedPreferences、日志、剪贴板、URL 或报告。SharedPreferences 只保存非秘密 host ID、issued client ID 和验证后的 subject。关闭页面/进程结束后必须重新登录。用户应手动在 ChatGPT 设置检查/撤销残留授权。

当前只管理一个注册。换账号/工作区不覆写已验证注册；验证版需要清除测试 App 数据/卸载后重新注册。生产多账号管理、Keystore 持久化、刷新轮换恢复、设备锁定与正式 Provider 路由均未实现。本验证页没有自动操作墨灵的笔迹或调用原接口。

## 为什么没有直接把 DevKit 打进 APK

已在独立上游 checkout 执行：

```
npm ci --ignore-scripts --no-audit --no-fund
npm run build -w @siwc/local
node --experimental-strip-types --test packages/local/test/*.test.mjs
```

结果：57/57 测试通过。因此没有认定 DevKit 本身失效。

Android 阻碍是 `@siwc/local` 依赖 Node.js 的 crypto/http/fs/进程设施，现成 responses 输入类型只接受文本；不是可直接添加的 Android AAR。上游 LICENSE 是非商业许可。本分支不复制/翻译其源码，不打包 DevKit，按公开协议独立实现 Android 验证代码；商用/分发资格仍需遵循相关服务条款与项目 GPL。

## 构建与平板验证

GitHub Actions `Subscription probe` 会在 `probe/subscription-native` 分支和相关 PR 运行：

```
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon
```

成功后从该次 Actions 的 `moling-subscription-probe-debug` artifact 下载并解压 APK，安装到有浏览器和网络的 Android 8+ 设备。CI 构建不登录、不接收任何账号密钥，不会消耗订阅额度。

1. 打开「Moling 订阅验证」，点击 Continue with ChatGPT，完成自己账号的官方授权后切回验证页。不要把授权链接、回调 code 或 token 复制给他人。
2. 看到“身份与订阅权限验证通过”后点击获取可用模型。若只授权身份而未授权订阅，不继续推理。
3. 从真实账号返回的模型中选择模型，点击文本测试。预期收到完成事件且输出 `MOLING_OK`。
4. 点击图片测试。请求会携带生成的 PNG；预期收到完成事件且回答 `12`。有完成事件但答案未匹配，会明确标记，不视为视觉语义验证通过。
5. 接近 access token 过期时测试刷新；未到时间返回 `refresh_not_due`。刷新后再次请求模型/文本。
6. 点击退出并撤销；再尝试模型请求应要求登录。也可在浏览器中撤销授权后测试拒绝路径。

失败时只记录：设备/Android/浏览器版本、发生步骤、HTTP 状态、页面显示的安全错误码和 request ID。不要记录授权 URL、callback URL、code、token、用户试卷或网络响应全文。尚未提供自动导出报告功能。

这是设备本机 loopback 授权，不能把云端浏览器登录当作平板验证。没有在当前开发会话中实际登录用户账号；未证明 Android 属于官方明确列出的支持平台，最终以真实服务返回与官方范围为准。

## 当前证据

- DevKit 自带本地测试：57 通过，非真实账号测试。
- 原生协议：42 项离线断言通过，包括 RFC7636 challenge、callback/CSRF、scope、图像请求体、SSE 截断与失败、JWKS 验签、nonce/audience/subject/expiry/算法/篡改拒绝。
- 全部新增 debug Java 源文件已用 Android 34 android.jar 编译检查通过；这不替代 Gradle/D8/lint/APK 构建。
- CI 已在 commit `1226df7c954e32cf999d7641aad2450a6f175275` 完成原单测、协议单测、lint、debug/release 构建，结果为 BUILD SUCCESSFUL；后续修正以当前分支最新 Actions 为准。
- 官方 OIDC discovery 实际连通且端点契约检查通过（无账号、无令牌请求）。
- **真实 Android OAuth、账号订阅资格、模型文本/图片调用、刷新和撤销仍待设备实测。不能标记为完成。**

主机复现协议检查：设置 `JSON_JAVA_SOURCE` 指向 JSON-java checkout 的 `src/main/java`，运行 `bash tools/check-subscription-protocol.sh`。本次主机临时测试使用 JSON-java commit `9926542da15fc2d4bf917d744439c5617a5a1650`；不将该源码或 jar 随应用分发。Gradle 单测使用项目原有的 org.json 测试依赖。

## 来源

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://github.com/openai/sign-in-with-chatgpt-devkit/tree/0a36fefeb913055c8c7a1a29b63d82b96b2e841a
