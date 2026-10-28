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

## v0.1.1：授权停留诊断

包名为 `com.opc.moling.subscriptionprobe.diag`，入口为「Moling 订阅诊断」，可与旧验证版和正式墨灵共存。独立测试 APK 的 CI 签名不作为生产更新签名承诺。

授权过程中每半秒显示固定阶段、经过秒数、回调次数、被拒绝请求次数与本机连通测试结果。不保存 URL、授权码或令牌。卡住时切回诊断页即可查看状态。

| 观察结果 | 能得出的结论 |
|---|---|
| 等待浏览器授权回调，回调 0 次 | App 还没有看到授权回调，尚未开始令牌交换 |
| 点击本机连通测试，浏览器显示 MOLING_CALLBACK_OK | 该浏览器能够访问该设备上仍在运行的监听端口；不代表官方授权成功 |
| 连通测试成功但官方回调仍为 0 | 继续查授权页提交、浏览器重定向或账号/工作区；不能仅凭此断言服务端故障 |
| 回调次数增加且出现拒绝 | 已到本机，检查状态/参数/Host 校验及授权尝试是否已过期 |
| 向官方服务换取令牌 | 已收到并校验回调，阻塞发生在 App 到官方 token endpoint 的请求 |
| 下载公钥并验证身份 | token exchange 已返回，继续检查 JWKS/身份校验 |

新增本机回调测试入口只在 OAuth 监听期间可用，不消耗授权尝试。它没有授权能力、也不改变 PKCE/state/nonce 校验。测试后回到原授权标签页继续；不要复制或重用旧授权 URL。

本轮在 JDK 上通过 42 项协议断言和 10 项实际回环套接字检查（合成凭据），包括 health 请求、错误 state、错误 Host、正确 callback 和取消；这些测试不访问 OpenAI，也不是 Android 设备授权结论。

### 官方 DevKit 对照

`tools/devkit-auth-control.mjs` 调用用户另行安装的官方 checkout 中原始 `packages/local/dist/oauth.js`，不修改或随本仓库分发 DevKit 源码。此脚本只测授权；不发送模型推理，令牌仅在内存，结束后尝试撤销临时会话。仅将非秘密 host/client/verified subject 注册元数据保存在运行设备的用户配置目录。

它使用 OAuth 模块而非持久化 SDK store，因此不建立不受保护的 SDK 凭据存储。完整 SDK 的 OS-backed encryption 要求仍适用于生产接入。该内部模块路径按本次 DevKit commit 固定审查，不是稳定公共 API 承诺。

在有系统浏览器的同一设备上，按官方仓库说明先安装依赖并构建 `@siwc/local`，再运行：

```
node tools/devkit-auth-control.mjs /absolute/path/to/sign-in-with-chatgpt-devkit
```

`--check` 仅验证模块可导入；本轮该检查已通过。这里的云端浏览器与 SDK 进程的本机网络不相通，打开探针监听端口得到 ERR_CONNECTION_REFUSED，因此没有发起会产生无效回调对照的真实账号授权。**同账号官方 DevKit 真实授权仍未完成**，必须在浏览器和 SDK 共享 loopback 的设备上进行。

## v0.1.2：令牌交换的网络层

设备截图已证明：授权回调到达 1 次、被拒绝 0 次，151 秒进入 token exchange，154 秒出现 SocketException。因此，浏览器到 App 的实际授权回调已经成功；本轮定位在 App 换取令牌的传输阶段。没有 HTTP 状态/服务端拒绝码，尚不能判断为资格拒绝或授权服务业务错误。

验证页的 HttpURLConnection 改为固定版本 OkHttp 4.12.0（仅 debugImplementation），继续使用官方 HTTPS endpoint 和系统证书校验。新请求不复用空闲连接，排除长时间浏览器授权后复用失效连接的变量。这个变量是待验证假设，不是已复现的根因。

- 记录固定网络阶段：DNS、TCP/代理连接、TLS、请求头、请求体写出、等待/收到 HTTP 响应。
- 异常只转换为固定分类：connection_reset、broken_pipe、socket_closed、DNS、connect、timeout、TLS/certificate、EOF 等；不输出异常原文、URL、地址、请求体或凭据。
- 禁止自动重试和跨域重定向；令牌交换/刷新失败后需要显式重新授权，不自动重放可能已消费的授权码或 refresh token。
- TLS/证书校验不作降级；HTTP 总调用上限 120 秒，连接 15 秒、读取和写入各 30 秒。
- 包名为 `com.opc.moling.subscriptionprobe.netdiag`，入口「Moling 订阅网络诊断」，可与之前版本共存。

主机验证：42 项协议检查、10 项回环套接字检查、10 项安全异常分类检查、6 项真实 OkHttp 到本地合成 HTTP 服务的检查通过。后者验证连续请求确实使用不同 TCP 连接，以及服务端在收到 POST 后断开时只发送一次、不自动重放。全部新增 debug Java 对 Android 34 接口编译通过。完整 Android 单测/lint/APK 构建以当前提交 CI 为准；实际账号换令牌是否恢复仍待设备验证。

可选主机网络测试：给 `tools/check-subscription-protocol.sh` 设置 `OKHTTP_TEST_CLASSPATH`，使用以冒号连接的 okhttp、okio-jvm 和 kotlin-stdlib jar 路径。Gradle 中的 debug 单测会直接使用声明的依赖。Apache 授权及归属信息随 debug APK 的 subscription-network-licenses 资源提供。


## v0.1.3 公钥连接恢复
实机日志显示授权回调通过、令牌端点 HTTP 200，随后 JWKS 公钥下载发生 TLS 握手失败。这不能证明订阅调用已通过，也无法仅据此确定是代理、网络链路或服务端原因。
公开配置 GET 增加最多两次新连接重试；取消及证书校验失败立即停止。POST 授权码、刷新、推理均不重放，身份签名验证保持不变。真实订阅模型请求仍待用户实机验证。


## v0.1.4 参考候选项目后的公钥预取
候选源码审计见 [subscription-candidates.md](subscription-candidates.md)。参考官方 DevKit 公钥解析器缓存思路，独立实现 10 分钟、客户端内存级 JWKS 缓存；授权前预取是墨灵针对实机网络故障增加的步骤，并非声称 DevKit 自带此流程。只有未知 kid 更新一次公钥，签名和 claims 失败立即拒绝。
预取失败时不会启动浏览器，也不会消耗授权码；超过缓存有效期或发生签名密钥轮换仍需网络。此措施减少授权完成后的连接数，不保证修复底层 TLS 链路。未接入第三方 Codex 私有通道。
