# 订阅持久登录 v0.3.1

## 缺陷
此前令牌仅存内存，页面切换虽然修复了，但进程被回收会丢失会话；订阅功能因此不应被称为完整。

## 实现
- SecureCredentialStore：Android Keystore非导出AES256密钥、AES-GCM随机12字节IV、128位认证标签、应用包名与schema作为AAD；AtomicFile密文在noBackupFilesDir，禁用备份；不明文写SharedPreferences，不上传Drive。
- SessionJournal：verified / pending / refresh_in_flight 三态。已验签会话使用绝对access expiry保存，绑定动态注册clientId与已验证subject。启动从authenticated encryption的已验证记录恢复，过期时通过原刷新逻辑处理。
- 刷新前原子记录in-flight且不保留可重放旧令牌；收到替代响应后先保存pending，再在原接收时刻验证签名/claims，最终保存verified。公钥服务故障不会丢失新refresh token，可恢复pending而不再次兑换旧grant。若请求已发出但无响应/进程在响应保存前被杀，无法知道服务器是否轮换，为避免重放需重新登录。
- 普通client.close保留密文。明确退出/取消授权清理密钥和密文；写入与关闭协调，已取消的旧任务不能写回并复活登录。登录恢复不代表刷新服务永远可用，身份/范围与端点约束维持原实现。

## 验证
纯JVM状态测试覆盖保存/重建、绝对expiry、pending恢复、flight不恢复旧grant、注册绑定、退出删除。设备测试使用合成凭据，不调用OpenAI：API30 emulator实际AndroidKeyStore写入 → 结束instrumentation并am force-stop → 新instrumentation进程恢复 → 删除后不再恢复；另测篡改认证标签拒绝、过期会话仍保留refresh状态。设备任务纳入同一个精确source build，发布工作流等待它与单测/lint/debug/release构建全部通过。

## 边界
新版是独立测试包，首次安装仍需登录一次，旧版未落盘的令牌无法迁移。卸载、清数据、Keystore密钥丢失/失效、明确退出、服务端撤销/刷新令牌失效、刷新结果不明确等需要重新登录。UI如恢复受阻显示具体状态，不将临时连接故障直接标记为授权成功。后续版本升级仍需配置稳定发布签名；当前独立测试包不保证跨不同包名迁移。
