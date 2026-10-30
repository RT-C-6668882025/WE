# GPT 订阅接入候选：源码审计与本次采用方案

审计日期：2026-10-28。基于以下固定提交的源码，未把 README 宣称当作实机通过。墨灵生产界面和 Anthropic 接口保持原样；修改限 debug 验证模块。

## 结论

这五个候选分成两类。官方 DevKit 使用动态应用注册、独立授权范围和公开 API；另四个使用同一个 Codex CLI 公共 client ID、普通 OAuth 范围，以及 ChatGPT 的 Codex backend。四个库并不构成四条相互独立的订阅服务。MIT 允许复用代码，不等于服务端已授权墨灵获得某个 client 的资格。

本次保留官方应用授权通道，参考 DevKit 的 JWKS 缓存机制，增加授权前预取，减少当前实机故障点。优先备选 MobMock 的 Android 消息转换与 SSE 模块，不能直接把整个鉴权/存储层纳入生产。Codex Agent 后续可走官方 app-server，须作为单独 AgentProvider，不能把它标成任意学习 App 的官方模型订阅 Provider。

## 模块复用矩阵

| 项目、固定提交 | 源码确认的路线 | Android 复用方式 | 许可证与成本 | 决定 |
|---|---|---|---|---|
| [MobMock](https://github.com/wamynobe/mobmock/tree/5b3d0bd0b2554424d7149921004b19087b89fb6e) | Config.kt 的 Codex client；OAuthManager PKCE；UpstreamClient 请求 backend-api/codex/responses | 原生 Kotlin library，优先评估 converter、reasoning、SSE；鉴权与存储需适配 | MIT；min26、compile35、AGP8.7.3、Kotlin2.1、JDK17；墨灵当前 compile34/AGP8.5.2/Kotlin2.0.21，不能假定直接构建兼容 | 第三方备选中 Android 成本最低；未整包引入 |
| [官方 DevKit](https://github.com/openai/sign-in-with-chatgpt-devkit/tree/0a36fefeb913055c8c7a1a29b63d82b96b2e841a) | local/src/oauth.ts 动态注册、direct scope、公开 /v1 API；jose 验签与公钥缓存 | Node/TypeScript，非 AAR；独立按公开协议实现 Android；已有验证模块沿用这条路线 | DevKit Noncommercial License v1.0，非 MIT；不复制其实现进 GPL 程序 | 本次采用公钥缓存设计；原生实现仍待实机订阅验证 |
| [login-with-chatgpt](https://github.com/opencoredev/login-with-chatgpt/tree/a1330697f35a1c6429009953acc8ec3e2f9f1f1c) | core/src/config.ts、oauth.ts、codex-transport.ts；Codex browser/device，服务端 proxy | TypeScript/Node>=18；可独立服务器或参考刷新并发、目标地址约束、会话代理，非 Android 依赖 | MIT；整个 server 引入部署和维护成本；不为当前 APK额外搭代理 | 参考实现；不打包 Node/网页框架 |
| [Flutter Kit](https://github.com/MobAI-App/chatgpt-auth-kit-flutter/tree/d1db6eae1f820a36370da0475873e1bcd724d6b2) | oauth_client.dart 固定 Codex client；loopback_server.dart；responses_client.dart 的 Codex backend | Dart/Flutter>=3.10，secure_storage/url_launcher 等插件；只能参考原理 | MIT；整体引入 Flutter 改动框架且增加包体、桥接成本 | 不引入框架；参考双栈 loopback 与刷新令牌保留逻辑 |
| [OpenCode](https://github.com/anomalyco/opencode/tree/b6c43a524265496086fc0fdd83180bfa47d4d22e) | packages/core/src/plugin/provider/openai.ts：Codex browser/device、刷新、backend 路由 | Bun/Node/Effect；完整代理程序，不是 Android library | MIT；整 App 嵌入成本高 | 参考账户、路由和设备码状态机，不整体合并 |

## 为什么不能直接照搬就算通过

- MobMock OAuthManager.parseJwtClaims 是 Base64 解码，不是签名验证；AuthStorage 将 token 写入私有目录 JSON，未采用 Android Keystore 加密。其 completeLogin(code, session) 不校验回调 state，调用方必须补上；它生成了 state 并不代表外层已经校验。
- Flutter Kit 在 OAuthFlow 层校验 state，但 OAuthClient/Jwt 读取账户字段不提供本项目现有的 OIDC 验签流程。login-with-chatgpt 与 OpenCode 同样从 token claims 提取 Codex 账户信息，不能据此断言完成墨灵的身份绑定。
- 因此第三方“无需下载公钥”与本次截图的 TLS 失败，不是同一验证链路的成功/失败对照。移除验签会改变验证语义，并不能证明 TLS 问题被解决。
- 第三方客户端硬编码的模型映射不代表用户当前可用模型；必须以当前账号返回的列表为准。图片 schema 存在也不代表实机图片请求已完成。
- 此次未运行第三方项目全部构建/测试，未使用用户账号登录这些客户端，未验证它们的服务端授权资格；许可证和兼容性结论来自仓库源码配置。

## 本次最小改造文件与实施顺序

1. `app/src/debug/java/com/opc/baize/subscription/IdentityKeyCache.java`：独立公钥预取与缓存，10 分钟有效，使用单调时钟，缓存只含公钥。未知 kid 时重新下载一次；错误签名、受众、nonce、账号不匹配仍拒绝。
2. `SubscriptionProbeClient.java`：读完官方配置后预取公钥，再打开系统浏览器；换令牌后使用缓存验签。密钥轮换或缓存过期仍可能需要下载，不缓存未经验证的身份。
3. `ProbeDiagnostics.java`、`SubscriptionProbeActivity.java`：明确显示“授权前预取身份公钥”和“使用公钥验证身份”，修正页面版本号。
4. `IdentityKeyCacheChecks.java`、`SubscriptionProtocolTest.java`、`tools/check-subscription-protocol.sh`：真实 RSA 签名验证缓存命中、到期、轮换、空公钥恢复、错签名/错受众拒绝。
5. `app/build.gradle.kts`、debug Manifest、release workflow：v0.1.4 独立 APK，便于与旧诊断包共存；debug/release 构建和 lint 后发布。
6. 实机依次验证授权、模型列表、文本、图片；只有四项通过再提取正式 ModelProvider。若仍因官方资格或稳定性不可用，再单列 MobMock/Codex 实验 Provider，使用独立凭据和路由，标注为第三方 Codex 通道，不混用当前动态注册令牌。

## 复用边界和回滚

本次未复制或打包五个候选项目的源码；属于源码审计与独立实现缓存机制，无新运行依赖。现有 OkHttp 沿用。生产 source set 未修改；回滚该提交或停用 debug APK 即可回到原状态。缓存与新连接重试减少请求失败机会，不承诺修复代理/网络/TLS 根因，不宣称官方订阅 Provider 已完成。
