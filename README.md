# WE · Write Everything

**打开就能写，回到工作区继续。**

WE 是面向 Android 手机和平板的手写与 AI 工作应用，基于 [Moling](https://github.com/xiaoxi668v-prog/moling) 改造。用手写、语音或打字表达需求，在同一个工作区保存对话、资料与学习页面。

[下载安装包](https://github.com/RT-C-6668882025/WE/releases) · [版本记录](docs/releases/) · [反馈问题](https://github.com/RT-C-6668882025/WE/issues) · [English](README.en.md)

当前源码版本：**v0.4.4 Preview**。可下载的 GitHub 版本以 Releases 页面为准。

## 怎么使用

### 首页：直接开始

- 直接手写，停笔后识别并回答；也可以点击“打字”或“语音”。
- 语音使用设备的系统识别服务；未安装相应服务时，可使用键盘语音输入。
- 点击左下角图标，打开“工作区 / 设置 / 关于”面板。

### 工作区：查看历史，继续任务

- 旧会话和已有学习工作区统一列出，原会话存档保留。
- 点击一个工作区，在首页继续原任务，恢复最近对话上下文。
- “＋ 新工作区”开始新任务。
- 每个工作区的“资料”中可查看文件、导入 PDF、打开最近学习页、重命名或删除工作区。
- PDF、学习页面等操作放在对应工作区内，设置中不再提供独立工作台入口。

### 设置：配置能力

- 登录 ChatGPT 订阅，读取账号可用模型，分别选择文字与图片模型；或使用兼容 Anthropic Messages API 的服务。
- 调整字体、字号、停笔识别延迟和书写相关选项。
- 配置本机识别、订阅视觉识别及可选的识别降级服务。

“关于”展示 WE 项目信息、许可及上游和第三方致谢。

## 手写与学习页面

- 首页支持本机 ML Kit Digital Ink 识别，也可使用选定的订阅视觉模型。
- PDF 学习页支持书写、撤销、移动、缩放、矩形选区、整页或选区识别、批改与解释。
- 笔迹独立于原 PDF 保存；识别记录关联提交时的页码、笔迹版本和选区。
- 学习页可启用“停笔后自动继续”：识别反馈交给设备内的 Agent，结合原材料生成修改版或下一份文本学习页。
- 等待期间继续书写时，生成结果先保留，通过“查看生成页”打开，避免抢切当前页面。

编排、文件和笔迹保存在 Android 端；模型推理由所选服务提供方处理，需要联网。WE 不要求电脑、Termux 或单独部署 Agent 引擎。

## Skill 与 MCP 的实际状态

当前内置 Android Agent Runtime 已具备文件工具、Skill 读取和公开 HTTPS MCP 客户端能力，相关实现见 [Android 原生 Agent](docs/android-native-agent.md)。这些是内部运行能力，不等于对外控制接口。

新增对外 MCP：设置 → **Agent 连接 · MCP**。同一 Android 的原生客户端可直接连接本机端点；外部 Agent 可读工作区与手写页面图像、读写资料/Skill，并发布新的学习页。写入有 operation_id 回执和版本检查，原页与笔迹保留。

WordTok 式固定公网地址采用“HTTPS 中继转发、Android 执行”的配对结构，源码及部署步骤见 [MCP 连接](mcp/README.md)。**没有预先托管的 WE 公网域名，需先独立部署中继；不能使用 WordTok 原地址。** 本机地址不能从云端直接访问。

当前生成页面以文本学习纸为主。可编辑图形框架、任意 Python/Node Skill 脚本、MCP OAuth 和 stdio 传输尚未支持。DSH Go、RikkaHub、Excalidraw 和 Jetpack Ink 为已研究的候选，未集成。具体见 [实现、验证与边界](docs/research/continuous-learning.md)。Google Drive 同步暂未接入。

## 安装与登录

要求 **Android 8.0 / API 26 及以上**。普通 Android 手机和平板可用；支持相应系统框架的华为墨水屏设备另有直写适配。

1. 从本仓库 [Releases](https://github.com/RT-C-6668882025/WE/releases) 下载 WE APK 并安装。
2. 打开“设置”，选择 ChatGPT 订阅或原 API 通道。
3. 使用订阅时，完成浏览器授权（保留原会话页面，不要刷新），刷新账号模型列表，选择模型并点击“保存选择并启用订阅”。
4. 返回首页开始输入，或从工作区打开已有任务。

订阅凭据通过 Android Keystore 加密保存在本机。明确退出登录会删除会话。

若浏览器授权最后停在 `127.0.0.1` 错误页，可在同次授权仍进行时，将完整地址粘贴到 App 的“浏览器回调失败：恢复本次授权”中。App 仍校验本次地址与授权状态。**该地址含一次性授权信息，不要发到 Issues、聊天或公开渠道。**

预览包使用 debug 签名。覆盖安装需要相同签名；签名不一致时先保留原安装与资料，避免卸载造成数据丢失。不同来源的预览包不保证能相互覆盖。

## 构建与验证

技术栈：Kotlin、Java、Android Views；`minSdk 26`、`compileSdk / targetSdk 34`。构建需要 **JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0**。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

Android 仪器测试：

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.opc.baize.agent.NativeAgentDeviceTest,com.opc.baize.mcp.McpDeviceTest
```

当前本地验证已通过 完整单元测试、Lint 和 APK 构建，包含旧任务续接、工作区索引、资料保留及界面入口检查。模拟模型和本机回调测试不替代真实订阅授权、识图准确率、语音服务或平板笔感验证。

## 项目与致谢

WE 由 **RT · [RT-C-6668882025](https://github.com/RT-C-6668882025)** 维护。

- [Moling](https://github.com/xiaoxi668v-prog/moling)：原项目及手写底座，作者小西校长。
- [Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime)：设备内 Agent、Skill 与 MCP 客户端能力，使用 `0.3.0`，MIT；[随附许可](app/src/main/assets/agent-licenses/Android-Agent-Runtime-MIT.txt)。
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)：Android 本机 MCP HTTP 层，2.3.1，BSD-3-Clause；许可随 APK 附带。
- Google ML Kit Digital Ink Recognition：本机笔迹识别。
- AndroidX：文档访问与运动预测等组件。
- Long Cang、Ma Shan Zheng、Zhi Mang Xing：手写字体，SIL Open Font License 1.1。

项目沿用 **GPL-3.0**，完整条款见 [LICENSE](LICENSE)。第三方组件与字体遵循各自许可。
