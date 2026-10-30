# Android 内置 Agent / Skill / MCP

## 产品约束

WE 必须在 Android 单独使用。电脑、ADB、Termux、Node、用户部署的引擎服务器都不是运行前提。模型推理继续通过已有 ChatGPT 订阅请求；模型服务和用户选择的远程 MCP 自然需要联网。

电脑桥接方案 PR #3 已关闭且未合并。本分支从原 v0.3.2 订阅代码开始，未引入 HTTP 监听端口、OpenCode 桌面依赖或电脑配置脚本。

## 复用实现

固定使用 `cc.noharry:agent-runtime-android:0.3.0`：
https://github.com/NoHarry/android-agent-runtime

复用其 Android 进程内 Agent 循环、按需读取 SKILL.md、沙箱文件工作区、工具分发、Streamable HTTP MCP 客户端、SQLite 会话和事件日志，以及 OpenAI Responses 序列化与工具事件解析。MIT 声明保留在 assets/agent-licenses。

这是仍在 0.x 阶段的框架，因此固定版本，不跟随上游 main。接口按 Maven Central 的 0.3.0 sources 核对。

## WE 的适配

- `SubscriptionAgentTransport` 在进程内调用已有 SubscriptionGateway。无需转交令牌；没有第二次登录或另一套授权。
- 新增原始 Responses 通道，保留工具调用和结果。先验证 completed 事件，再让运行时执行工具，失败和中断响应不会执行部分工具。
- 请求使用 store=false，并保留推理模型的 encrypted reasoning 与原始 function_call 项。工具轮次检查点在应用禁止备份目录中原子保存，继续请求时恢复，避免框架的通用消息模型丢失推理签名。
- 不自动换 Provider、不开启付费 API 回退，模型重试上限为一次。
- 运行时、Skills、文件、MCP 客户端均在 Android。脚本依赖尚未安装：当前不宣称任意 Python、Shell、Node 型 Skill 都能运行。

## 当前入口

主界面设置 → **WE 工作区 · Skill / MCP**。

1. 新建或选择工作区。
2. 通过 Android 文件选择器导入单个 Skill 文件夹（包含 SKILL.md，以及参考资料/附件）。复制到该工作区 `.agents/skills/<name>/`，避免依赖外部文件授权的长期有效性。
3. 配置公开 HTTPS MCP 的 id 和 url。当前未实现 MCP OAuth、私密认证或 stdio 服务器宿主。
4. 输入目标，运行时按需读取 Skill，调用文件工具或 MCP，再生成结果。可查看工作区生成的文本文件。
5. “验证本机工具调用”直接使用当前订阅模型执行创建文件→读回文件，无需电脑测试脚本。

工作区文件、导入 Skill、MCP 配置、SQLite 会话和对话记录在应用内保存。跨进程续聊使用已保存对话；中途被系统杀掉的任务不会自动重放工具。停止任务/关闭页面取消当前运行，不清除订阅凭据。

## 当前边界

此提交完成 Android 原生运行入口。已接通文本→分页学习纸→手写→选区/整页识别→带回工作区继续生成的首版流程。留白与紧凑两档排版，支持空白纸、可选停笔识别及仅笔书写。富文本组件、自适应图形框架和直接画布编辑工具仍未完成。

当前模型响应在本轮完整校验后交给运行时，因此按模型轮次显示结果，未实现网络字节级实时显示。

## 验证

共享测试同时用于 Robolectric 和 Android instrumentation。测试使用真实依赖、真实工作区文件和当前进程内的 MCP 测试服务，模型响应为合成数据：

读取 Skill 原文 → 读取 MCP schema → 调用 MCP 加法 → 工具结果回传 → 写入学习文件 → 读回文件 → 完成。

还需验证：真实账号工具能力，以及用户平板的书写/纸面整合。模拟数据测试不代表真实订阅账号已验证。
