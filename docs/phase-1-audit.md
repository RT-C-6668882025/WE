# Moling AI Paper：第一阶段代码审计与最小改造方案

审计日期：2026-10-28。交付范围：源码静态审计、复用决策、文件变更计划、实施与验收顺序。本轮不修改业务代码、UI、构建脚本或依赖，不将静态审计等同于构建/设备验证。

## 1. 决策摘要

以墨灵为唯一 Android 主程序，继续使用 Kotlin + 原生 View。保留自由手写模式全部交互，另增 PDF 练习模式。直接复用墨灵书写、ML Kit、Anthropic Messages 兼容链路；PDF 阅读优先验证 AndroidPdfViewer 的固定发布版本，通过 PdfEngine 隔离；局部适配 xnotes 几何代码；Inklus 与 Folio 仅参考选定实现。Google Drive 使用官方 Android 授权与 Drive REST API，Room 管本地状态，WorkManager 管延迟同步。Agent 独立运行，通过适配器连接，不打包整个 Agent 到 APK。

三个必须先解决的缺口：

1. 当前原始矢量笔迹没有磁盘持久化，不能把现有“历史记录”当作练习存档。
2. PDF 练习不能沿用“停笔 800ms → 自动提交 → 笔迹消散”的流程，应在独立模式中手动提交并始终保留原笔迹。
3. ChatGPT DevKit 既不是 Android SDK，也不是宽松开源许可证：订阅 Provider 暂不进入可交付功能。

## 2. 审计基线与证据边界

已实际拉取下列源码，按固定 commit 审查，而非只读产品介绍。

| 项目 | 审计 commit | 源码定位 |
|---|---|---|
| 墨灵 | `07335aab8838bf97b523c176ce71bbf66a767064` | https://github.com/xiaoxi668v-prog/moling |
| WE | `07335aab8838bf97b523c176ce71bbf66a767064` | https://github.com/RT-C-6668882025/WE |
| xnotes | `26f42b2945df85087ab8d36a5344aa0e0c949707` | https://github.com/shardulvs/xnotes-android |
| Inklus | `725fe9e92a0f81ade6dded1bcb45e20e5c7d984c` | https://github.com/Pedroj-64/inklus |
| AndroidPdfViewer | `f0aa7a728927e464363d19ac64170763d6435172` | https://github.com/zacharee/AndroidPdfViewer |
| Folio | `77014869e9eee17b2a8bf71d213f345705b9b975` | https://github.com/allient/folio（由官网源码链接确认） |
| DevKit | `0a36fefeb913055c8c7a1a29b63d82b96b2e841a` | https://github.com/openai/sign-in-with-chatgpt-devkit |
| Codex | `5d5e5c507def46c4e53b30441cf8198c8663c9d2` | https://github.com/openai/codex |

WE 当前与墨灵处于同一 commit，可直接作为实施仓库，无须再导入或合并其他 App。许可证结论以对应 commit 的 LICENSE 和具体文件头为依据；传递依赖、字体和 native 二进制仍需在选定发布包后完成分发清单。

验证分级：已验证＝实际读取源码/配置；设计方案＝尚未实现；未验证＝没有运行或官方明确支持证据。未运行平板、模拟器、OAuth 或模型请求。Hermes 未由用户给出仓库，本报告按 NousResearch/hermes-agent 作候选，未对其全库进行代码审计，不能视为适配完成。

## 3. 墨灵现状

以下路径均相对于 `app/src/main/java/com/opc/baize/`。

| 模块 | 事实与可保留部分 | 对目标的缺口 |
|---|---|---|
| `MainActivity.kt` | 单 Activity，代码创建原生 View；`buildUi`、`wireCallbacks`、`runRound`、设置和历史面板集中编排；单线程 worker 执行识别/模型链路 | 新功能不继续堆进 Activity；只增加模式路由、装配接口和生命周期转发 |
| `InkView.kt` | `Pt(x,y,w,t)`、`strokes` 是权威数据；离屏 Bitmap 增量绘制、中点贝塞尔、压力映射笔宽；接收历史 MotionEvent，距离过滤约 1dp；预测点只用于视觉笔尾，不入权威数据 | 点中存的是已换算笔宽，并非原始 pressure/tilt；“原始矢量”目前也已经过采样过滤。新 schema 应保留实际采集点及原始压力等可用字段，兼容旧宽度；不能声称可恢复历史未保存点 |
| `HwHandwritingLayer.kt`、`HwWritingContainer.kt`、`HwTrackConverter.kt` | 反射探测华为 HWEditView，读取轨迹转换成 InkView 点；无私有 SDK 编译依赖，失败回退自绘 | 硬件直写与 PDF 缩放/裁剪/恢复显示尚未验证；练习模式首版使用自绘路径，保留自由模式直写；后续单独真机验证 |
| `EinkRefreshController.kt` | 反射 EPDC；写入/阅读刷新模式及生命周期恢复 | 保留，不让网络/同步代码进入落笔热路径 |
| `IdleInkDetector.kt`、`core/Gestures.kt`、`core/TouchGate.kt` | 停笔判断、双指水平翻页、角落长按与误触闸 | 原自由模式不变；练习模式单独处理笔/手指、缩放、平移、圈选，不能让双指缩放触发原翻页 |
| `net/MlKitRecognizer.kt` | zh-Hani Digital Ink，笔迹轨迹输入；首次下载模型，工作线程等待下载/识别，各有 20s 等待；识别异常回退 | 下载前不能保证离线识别；写字必须始终离线可用。无需替换识别器；生命周期关闭资源需后续核查 |
| `net/DeepSeekClient.kt`、`core/DeepSeekProtocol.kt` | `POST endpoint/v1/messages`，`x-api-key`、`anthropic-version: 2023-06-01`；文本 history，默认 `max_tokens=400`；两次尝试，部分 4xx 不重试 | 目前无图像 content block、工具调用、结构化批改、流式 UI；不能把“生成 PNG”当作已经支持视觉模型 |
| `net/FallbackClient.kt`、`core/FallbackProtocol.kt` | 可选自建局域网识别/问答通道；不是开源仓库内完整服务端 | 保留兼容；不能把用户未部署的服务当作必备基础设施 |
| `BookView.kt`、`PageStore.kt` | 页面文本展示；`pages.json` v1 保存 `you/book` 文本 | 无 PDF、无矢量笔迹档案 |
| `SessionStore.kt`、`core/Session.kt`、`core/SessionMemory.kt` | `sessions.json` 保存问答历史；可从 pages.json 迁移；对话上下文为最近 6 轮内存数据 | JSON 整表覆盖、异常吞掉，没有事务、同步版本或稳定文档身份；笔迹不在 Session 中 |
| 设置与清单 | 运行时 endpoint/key/model 存 SharedPreferences；仅 INTERNET 权限；关闭系统备份；允许 cleartext；固定 portrait | OAuth 令牌应使用 Android Keystore 保护；原密钥迁移另做小变更；平板横屏需独立验收，首版不无关改布局 |

关键失数据路径：普通模式 `onIdleTimeout` 取得 snapshot、导出 PNG，然后 `fadeOutAndClear`，再 submit；硬件模式通过轨迹读取并在提交收尾清场。两条路径都不是可编辑笔迹存档。网络失败不应决定练习笔迹是否保留。

## 4. 模块复用矩阵

“依赖”指可独立引入的库；“适配”指摘取有限代码并保留出处；“参考”指不直接合入其 App/运行时。

| 来源 | 具体模块/路径 | 方式 | 结论与代价 |
|---|---|---|---|
| 墨灵 | InkView、Hw*、EinkRefreshController、ML Kit、DeepSeekProtocol/Client | 保留并加薄接口 | 主程序唯一底座；不换 Compose/Flutter，不重写书写引擎 |
| AndroidPdfViewer | `android-pdf-viewer/`，`PDFView.kt`、onDrawAll、缩放/页面尺寸/跳页接口 | **依赖候选** | 原生 View，与当前 UI 形态吻合；只负责阅读渲染，编辑笔迹另存。当前源码版本 4.0.3，发布坐标 `dev.zwander:pdfviewer`；发布包可获取性、AAR metadata 与当前工具链未验证，不立即锁为已兼容 |
| xnotes | `core/pdf/PdfPageGeometry.kt`、`PageFrame.kt`、`core/geometry`；`canvas/SelectionMath.kt` | **适配候选** | 坐标/旋转几何最值得复用；SelectionMath 依赖 CanvasItem、Stroke、Geometry 等模型，不能原文件无修改复制。只抽所需点/线/多边形命中逻辑并做接口适配 |
| xnotes | `platform/PdfMarkups.kt`、`PdfExportContext.kt`、`canvas/CanvasView.kt` | 参考；PDF 导出以后再适配 | 依赖自有文档/模型及 PDFBox，非现成批注 AAR；不引入完整编辑器、tree-sitter 或 Compose UI |
| Inklus | `lib/services/drive_sync_service.dart`、`inklus_format.dart`、`models/stroke.dart` | **参考** | Flutter/Dart App，无 Kotlin 可直接依赖模块；可参考 appProperties 中 noteId、文件夹组织、revision 下载、离线本地优先。backupNote 是查找后 create/update，未看到双端基线比较或条件覆盖保护，不能直接当冲突安全同步引擎；不可复用其 OAuth client ID |
| Folio | `apps/mobile/androidApp/.../PdfReaderEngine.kt` | **参考/后续有限适配** | 原生 PdfReaderSession/PdfPageReaderView 展示了 PDFium 渲染、页面锚点、OCR、缩放与批注投影，但依赖 ReaderAnnotation、自有数据及应用上下文。不是独立阅读 SDK，当前无需第二个 PDF 引擎 |
| Folio | `LocalBackup.kt`、共享层数据/笔迹格式 | 参考 | 适合参考可编辑格式、校验备份；本地备份不是 Drive OAuth/双向同步实现；不带入整套 KMP/Compose/Firebase |
| DevKit | `packages/local/src/{oauth,storage,responses,types}.ts` | **暂不合入** | Node.js 本机 SDK + React；types 中输入 content 为 string，未提供图像内容块接口；许可证含非商业限制。Android、授权资格、视觉请求、GPL 组合分发都未过门槛 |
| Codex | `codex-rs/app-server`、app-server-protocol、`protocol/src/user_input.rs` | **协议适配** | Rust 外部进程；有 stdio/WebSocket 传输、Image/LocalImage 输入类型。Android 端只做客户端/受控桥接，不把 Rust CLI 和 shell 执行环境打包入 APK。图像类型存在不等于特定订阅端点接受图像 |
| Google 官方组件 | Android AuthorizationClient、Drive v3 REST；Room、WorkManager | **直接依赖候选，版本待解析** | 不从其他 App 移植 OAuth；选择与现有 minSdk/AGP/Kotlin 可解析的固定稳定版本后再提交锁定记录 |

## 5. 许可证、SDK 与构建成本

| 项目 | 已读许可证 | 工具链/SDK（本次源码） | 集成判断 |
|---|---|---|---|
| 墨灵 | GPL v3 文本；保留全部原声明 | Gradle 8.7、AGP 8.5.2、Kotlin 2.0.21、JDK/JVM 17；min26/compile34/target34 | 衍生 App 按 GPL 相应义务提供源码，不应改成闭源或 MIT 整包 |
| xnotes | MIT | AGP 8.12.3、Kotlin 2.2.21、JVM17；min26/compile36/target36；NDK27、CMake3.22.1 | 有限源文件适配可保留 MIT 头/归属；不照搬工具链和 native 编辑器依赖 |
| Inklus | GPL v3；部分文件明确 GPL-3.0-or-later | Dart ^3.13.0、Flutter；Android JVM17；SDK 数值委托 flutter 配置 | 引入 Flutter 整体成本高且违反目标；仅参考，若复制/翻译代码仍保留 GPL 来源义务 |
| Folio | Apache-2.0 | AGP9.1.1、Kotlin2.4.20、Compose/KMP；min25/compile37/target37；README 要求 JDK21、Bun1.4+ | Apache 声明/NOTICE 随选取代码保留；不升级底座来容纳整 App |
| AndroidPdfViewer | Apache-2.0 | 当前 AGP9.1.0、Kotlin2.3.10、JVM21；min24/compile36；PdfiumAndroid2.0.0 | minSdk 与墨灵相容不代表二进制兼容；旧 Kotlin 编译器、AAR minCompileSdk、JVM 字节码、传递 AndroidX/native ABI 均需实测 |
| DevKit | Sign-in with ChatGPT DevKit Noncommercial License v1.0 | 本机 Node.js SDK；示例 macOS14+、Node22.12+ | 不能称为 MIT/Apache 开源 SDK；非商业限制与 GPL 自由再分发要求有组合风险，本项目不直接复制/链接打包，后续独立评估授权与协议实现条件 |
| Codex | Apache-2.0，另有 NOTICE | Rust/Cargo/Bazel 工作区、系统执行/沙箱依赖 | 外部部署，Android 客户端只实现实际用到协议；无需构建全仓作为 APK 依赖 |

AndroidPdfViewer README 保留了旧上游“API11/JCenter/3.2-beta”的段落；当前 Gradle 已是 min24，不能依据旧说明认定兼容。仓库明确说明主动维护有限。依赖验证先检查 Maven Central 的实际发布版本/POM/AAR，再决定：使用已验证固定版本，或在 PdfEngine 后换平台 PdfRenderer（min21）/独立 PDFium。首版默认单页切换、页内缩放，减少多页虚拟布局和多指手势复杂度，不重造 PDF 解码器。

native 发布检查包括 arm64 平板、ABI 缺失降级/错误提示、16KB page-size 兼容、native license/NOTICE、安全更新来源与大 PDF 内存；这些均尚未跑设备验证。具体包体/构建耗时没有测量值，不作数字承诺。

墨灵 assets 下三款字体另需补齐各自授权来源与随包许可证；主 LICENSE 不自动覆盖第三方字体。ML Kit 等服务/二进制遵循各自条款，不因底座 GPL 获得额外授权。

## 6. 最小结构：保留自由手写，增加练习容器

初期保持单 `:app` Gradle 模块，按包隔离职责；不先拆成大量 library module。

- `PaperWorkspaceView`：FrameLayout 容器，下层 PdfEngine 视图，上层独立 PaperInkView 与选区视图。
- `InkDocument`：与 Android View 解耦的文档/页/笔划/采样点模型。每笔稳定 UUID；点保存页坐标、时间、笔宽及可采集的原始 pressure/tilt/toolType；预测点永不落盘。不要把 InkView.Pt 作为跨层永久格式。
- `PageTransform`：统一维护文档页坐标到 View 的矩阵及其逆矩阵。页坐标采用 crop 后、旋转处理明确的页面空间，单位 PDF point；持久化页尺寸、CropBox、rotation、PDF 内容 hash。书写、回显、圈选、模型裁图共用同一变换；笔宽也作尺度转换。不要保存屏幕像素或只保存归一化坐标而丢页面定义。
- `LocalWorkspaceRepository`：原 PDF、矢量笔迹、批改结果三类独立文件；Room 管引用、版本、队列、状态；位图只作缓存或模型输入。
- `ModelProvider`：text/image/structuredOutput/tools 能力显式声明；首个 AnthropicMessagesProvider 委托原 DeepSeekClient，原文本请求保持不变。练习批改另有请求策略，不套用原 400 token/persona。
- `AgentProvider`：run/events/cancel 与 toolRequest/result；独立于 ModelProvider。网络可用时才连接，断开不影响笔迹保存。
- `StorageProvider`：list/download/upload/createFolder/getVersion；Drive 实现不进入 UI 或书写层。

PDF 模式独立手动提交；自由模式仍用原停笔/清场流程。圈选应通过显式练习工具进入，不能把正常圆形笔划自动识别为提问。操作限定在练习模式，入口放现有面板，避免改动原画布交互。提交先创建不可变 revision 快照；后续书写不改动在途批改输入。

图片批改需裁取“PDF 题干 + 对应矢量笔迹”的派生图片，并记录 documentId/pageId/region/strokeIds/sourceRevision。当前文本 Provider 只能接收识别文字；没有视觉能力时明确提示题干/图形不足，不能伪装成完整图形试卷批改。删除/编辑笔迹后旧批改可保留但标记版本不匹配。

## 7. Drive/Room 设计与冲突边界

推荐起步范围：应用工作区及用户显式选择授权的文件。`drive.file` 不能任意浏览用户整个 Drive；若产品要求全盘浏览/修改，需要另行选择对应广域 scope 及验证流程，不能仅改 UI 就宣称具备全盘权限。SAF 可用于导入一份本地 PDF，但获得 URI 不自动获得同一文件的 Drive API fileId/授权。

Android OAuth 使用自己的 Cloud 项目、包名和 debug/release 签名身份；授权与登录分开处理。无 Google 服务的华为设备、撤销授权、未安装 Play 服务时保留本地模式；不承诺标准 Android 授权组件可在所有华为机型运行。

建议 Room 表：Document、Page、InkRevision、GradeResult、RemoteBinding、SyncOperation、SyncCursor。Document 使用本地 UUID；RemoteBinding 按 accountId/provider/localId 绑定 Drive fileId，另存 remoteVersion/headRevisionId（可用时）、modifiedTime、checksum、baseRevision、lastSyncedLocalRevision。文件名是展示属性，不能充当主键；同名、移动、重命名不新建本地文档。

文件组织：原 PDF 独立、每个不可变笔迹 revision 独立、批改 JSON 独立，manifest 引用各文件 ID/hash/schemaVersion。Room 事务不能跨 Drive 文件形成原子提交；先完整上传不可变内容并校验，再发布 manifest；只下载有完整依赖的 manifest。记录幂等 operationId，超时重试先查已创建的操作对象，避免重复文件。令牌/密钥不得写进 manifest 或上传到 Drive。

首版冲突规则：

| 本地相对基线 | 远端相对基线 | 行为 |
|---|---|---|
| 未改 | 未改 | 无操作 |
| 已改 | 未改 | 上传新不可变 revision；推进本地同步基线 |
| 未改 | 已改 | 下载校验后应用；原本地版本可回退 |
| 已改 | 已改 | 保存两分支，显示冲突待选；不按时间戳静默覆盖 |
| 任意 | 删除/权限丢失 | 保留本地和待同步操作，区分删除冲突与授权错误 |

Drive version 不是通用 CAS 锁。上传前读版本仍有竞争窗口，不能称为已解决并发覆盖。首版采用每设备独立 revision/分支记录，manifest 可重建；如需共享 head 原子推进，必须验证目标 API 的条件写入语义/失败码，不能想当然把 version 当 If-Match。笔迹编辑/删除及批改结果不作不受约束的 JSON 自动合并。

WorkManager 仅在本地保存后、网络满足时执行批量同步，按账号/文档串行；429/5xx 退避、断点/恢复、changes 分页游标、失效游标重扫、账号切换隔离均需测试。Drive revisions 是辅助恢复机制，不作为唯一的学习档案保留策略。

## 8. ChatGPT 与 Agent 验证门槛

官方当前文档确认有 open-source app 的 ChatGPT plan usage 路径，不能笼统写“所有第三方均不可用”；同样不能据此认定 Android 原生集成已受支持。

已读 DevKit 的 OAuth、storage/types/responses 相关源码与官方概览：本机可信 Node 进程负责凭据、loopback callback、PKCE/state/nonce、身份/授权验证、模型发现与 Responses 流。types 的 `ResponseInputMessage.content` 为 string，`StreamResponseOptions` 无图像块类型。其现成 SDK 的视觉输入接口不存在；这不能反推后端一定拒绝所有图像。

订阅 Provider 保持未实现，进入实施前必须逐项形成证据：

1. 官方对 Android 平台/回调方式、本机运行边界、注册资格和当前支持套餐的明确说明；cookbook 目前以 Plus/Pro 测试为例，不代替完整资格矩阵。
2. 注册实际返回的 client_id、允许 scope、授权撤回/续期/拒绝同意行为；不能借用示例或 Codex 客户端身份。
3. 官方目标路由和允许的 Responses 参数；分别验证文本与 image input 的请求格式、模型能力和失败语义，不拿普通 API 文档替代订阅路由证据。
4. Android 浏览器往返、进程重建、Keystore 令牌保护实测；GPL 与 DevKit 非商业许可组合方式需解决后才能分发其代码。

Codex 当前源码具有 stdio/WebSocket 和图像输入类型，但不是 Android AAR。建议用户控制的电脑/服务器运行 app-server，Android 连接经认证加密的窄桥接协议；实现 initialize/协商、thread/turn、事件、取消、审批与重连。远端 LocalImage 路径不指向平板文件，需受控上传/图像引用转换；不能把 Android 路径直接发给远端。不能把 app-server 登录成功当作任意第三方模型 API 授权。

Hermes 暂按 NousResearch 版本候选：外部 Agent，而非 Kotlin 内嵌模块；第四阶段先固定 commit 并审其 API/ACP/事件与图片通道后选择传输。此处不虚构稳定 HTTP endpoint。

学习工具建议：generateProblems、gradeSelection、saveMistake、explainSelection。工具输入包含稳定文档/页/选区/revision，输出结构化结果与证据引用；写入工具执行幂等检查。Agent 不得绕过本地 Repository 直接改 DB/原 PDF，也不默认开放整个平板文件或 shell。

## 9. 改造文件清单（计划，尚未创建业务文件）

下表新增 Kotlin 路径均在 `app/src/main/java/com/opc/baize/` 内。

| 阶段 | 修改现有文件 | 新增文件/包与职责 |
|---|---|---|
| P0 基线 | 后续按需新增 `.github/workflows/android.yml`；暂不改版本 | CI 跑现有 testDebugUnitTest/lintDebug/assembleDebug，保存构建日志；依赖验证单独提交 |
| P1 本地笔迹 | `InkView.kt` 只加输入/快照桥；`MainActivity.kt` 加模式路由与装配 | `ink/InkDocument.kt`、`ink/InkCodec.kt`、`storage/LocalWorkspaceRepository.kt`、`storage/WorkspaceFiles.kt`；schema、原子写/恢复、稳定 ID |
| P2 PDF 阅读 | `app/build.gradle.kts` 固定 PDF 依赖；`MainActivity.kt` 接入练习容器 | `pdf/PdfEngine.kt`、`pdf/AndroidPdfViewerEngine.kt`、`pdf/PageTransform.kt`、`paper/PaperWorkspaceView.kt`、`paper/PaperInkView.kt`、`paper/PaperInputController.kt` |
| P3 选区批改 | `MainActivity.kt` 委托提交；`net/DeepSeekClient.kt` 原实现尽量不动；`core/DeepSeekProtocol.kt` 保留旧文本契约 | `paper/SelectionController.kt`、`paper/SelectionGeometry.kt`、`paper/RegionExporter.kt`、`model/ModelProvider.kt`、`model/AnthropicMessagesProvider.kt`、`grading/GradingService.kt`、`grading/GradeResult.kt` |
| P4 Room | `app/build.gradle.kts` 增 Room/KSP 等经验证组合；逐步接通 Repository | `storage/db/WorkspaceDatabase.kt`、`Entities.kt`、`WorkspaceDao.kt`、`Migrations.kt`；`storage/LegacySessionImporter.kt` 仅在迁移旧历史时使用，原 JSON 保留 |
| P5 Drive | `app/build.gradle.kts` 增授权/后台工作依赖；`AndroidManifest.xml` 按实际回调组件配置；`MainActivity.kt` 面板入口 | `storage/StorageProvider.kt`、`drive/DriveStorageProvider.kt`、`drive/DriveAuthManager.kt`、`drive/DriveBrowserView.kt`、`sync/SyncPlanner.kt`、`SyncWorker.kt`、`ConflictResolver.kt`、`security/CredentialStore.kt` |
| P6 Agent | 设置面板只增加连接配置 | `agent/AgentProvider.kt`、`CodexAgentProvider.kt`、`HermesAgentProvider.kt`、`AgentTransport.kt`、`tools/StudyToolRegistry.kt`；Provider 独立 capability/超时/取消 |
| 条件阶段 | 官方资格/Android/视觉/许可门槛通过后才进入 | `model/ChatGptSubscriptionProvider.kt`，验证前不创建假成功实现，不展示“已完成”登录能力 |
| 归属文档 | `LICENSE` 保留原文，不改许可证归属 | 随实际引入创建 `THIRD_PARTY_NOTICES.md`、字体许可目录、依赖锁定/审计记录 |

P1-P6 是建议提交边界，不是一次性大重构。现有 `BookView.kt`、Hw*、EinkRefreshController、PageStore/SessionStore 及自由模式手势原则上不改；必要改动另开小提交并跑原测试。新增接口不要求立即把旧所有代码搬家。

## 10. 实施顺序、验收与回滚

1. **基线可复现**：固定当前 commit；在有 Android SDK 的 CI 跑现有单测、lint、debug 构建；记录设备上的自由手写与硬件回退。原测试不通过则先处理基线，不能在新增功能后混淆归因。
2. **依赖探针**：独立提交验证 PDF 发布包解析与最小 View 显示，不改原默认模式。若当前发布物要求升级工具链，单独评估兼容发布版本或 PdfEngine 替代实现。没有成功构建就不进入阅读组件正式集成。
3. **本地练习核心**：矢量 schema/原子存档 → 坐标矩阵 → PDF+笔迹叠层 → 翻页/缩放/恢复。先保证离线可写可读；结束一笔后异步保存，进入后台前提交缓冲；进程强杀最多损失在途未提交笔，并在设计中明确恢复边界。
4. **选区/手动提交**：矩形/套索命中、PDF+笔迹裁图、稳定 revision；接薄 ModelProvider，原 Anthropic 文本链路保持测试一致；视觉能力单独开门槛。
5. **Room 本地状态**：事务、文件引用一致性、schema migration 与崩溃恢复；保留旧 JSON；验证后才切换历史读取。
6. **Drive**：授权 → 文件列表/文件夹/传输 → 单端同步 → 双端冲突/删除/账号切换；最后启用后台同步。
7. **Agent**：先用假传输验证状态机，再接真实 Codex/Hermes；工具执行经过本地仓库，网络失败可退出而不阻塞写字。
8. **订阅 Provider**：仅在第 8 节门槛齐备后实施，不能成为前面阶段交付依赖。

最低关键测试：矩阵正逆映射及 0/90/180/270 旋转/不同页面尺寸/CropBox；缩放后笔宽与圈选命中；序列化 round-trip、损坏文件恢复和预测点不落盘；提交期间继续写字，旧批改不会覆盖新 revision；无网络/模型失败仍能写与恢复；Room migration；同步规划的双改/删除/重试幂等；Drive 真账号授权撤回、两台设备冲突；普通 Android 平板 + 华为直写真机、横竖屏策略与大扫描 PDF 内存。JVM 单测不能替代 native PDF/笔输入/电子墨水刷新真机验证。

每一能力单独 commit/PR，不改默认自由模式；练习和同步入口可关闭。DB 升级提供前向迁移与备份，不做破坏式迁移；Git revert 不等于数据回滚，旧文件和原 PDF 不原地覆盖。首版失败可退回自由模式而不删除工作区。

## 11. 本轮实际验证记录与未完成项

- 已确认 WE 与墨灵 HEAD 相同；已读取主手写、硬件反射、识别、模型协议、存档及参考项目关键代码/配置/许可证。
- 执行命令：`./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon`。
- 结果：退出码 1；Gradle wrapper 下载 `gradle-8.7-bin.zip` 时 `java.net.SocketException: Network is unreachable`，尚未进入 Android 编译或测试阶段。环境有 JDK17；常见 SDK 路径未发现 SDK，此项尚未完成全局定位。不能报告任何单测、lint、APK 构建通过。
- 没有修改业务代码/依赖；没有执行 Google/OpenAI OAuth，没有模型请求、设备安装或 Agent 连通测试。
- 本报告是可执行改造方案，不是第二至第四阶段实现完成报告。PDF 发布物兼容、完整传递许可证/SBOM、字体授权、16KB/native 验证、Android 订阅官方支持与视觉输入均是明确未关闭项。

## 12. 参考资料

源码证据：第 2 节仓库 + 固定 SHA + 第 3/4/5 节具体路径，可在 GitHub `blob/<SHA>/<path>` 精确定位。

官方资料（2026-10-28 读取；后续实施需再次核对）：

- https://developers.google.com/identity/authorization/android
- https://developers.google.com/drive/api/guides/manage-changes
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.openai.com/siwc
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://github.com/openai/sign-in-with-chatgpt-devkit/blob/0a36fefeb913055c8c7a1a29b63d82b96b2e841a/LICENSE
- https://github.com/NousResearch/hermes-agent （候选识别，非本轮全库审计）
