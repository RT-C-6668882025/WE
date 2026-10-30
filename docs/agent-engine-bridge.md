# WE Skill / MCP 引擎接入

## 实现范围

保留 v0.3.2 的 Android 订阅授权、Keystore 持久化、账号模型目录和刷新 journal。新通道并行于旧的文字回答接口，不替换登录协议，不搬运令牌。

复用 OpenCode 1.2.27 作为外部执行引擎：Skills 发现和按需读取、MCP 会话、工具执行循环、文件工作区和上下文管理均由现成引擎负责。复用 NanoHTTPD 2.3.1 提供 Android loopback HTTP，不自行实现 HTTP 解析。引擎中已有的 `@ai-sdk/openai` Responses 适配器负责解释工具调用事件和继续请求。

WE 的最小适配是一个 `/v1/responses` 流通道。原接口只提取文字；新通道原样保留 function_call、call_id、参数、推理项目和完整 SSE。引擎回传 function_call_output 时也保持原始结构。请求强制 store=false，拒绝 previous_response_id 和后台任务，工具限为本地执行的 function。

这是一条可测试的引擎连接原型。Android 本身尚未内置 OpenCode 运行时；WE 尚无工作区前端、Skill 管理页面、画布工具和引擎结果到原生页面的排版协议。它们属于下一层产品集成，不能因引擎链路通过就标记完成。

## 第一次运行（电脑 + Android）

1. 安装此分支构建的 debug APK，覆盖安装同签名的现有测试包。包名保持 `.aipaper.persist32`，不要求迁移现有登录。不要卸载旧包；卸载会删除系统密钥。
2. 打开订阅设置，确认当前登录可用，点击“开启 Skill / MCP 引擎连接”。服务仅监听 127.0.0.1:43187；查看并复制临时连接密钥。该密钥不是订阅令牌，但持有者可通过本机连接使用订阅额度。
3. 电脑安装 Android platform-tools，通过 USB 或已配对的无线 ADB 连接设备，执行：

```sh
adb forward tcp:43187 tcp:43187
```

4. 在 `tools/agent-engine` 安装固定版本依赖并创建专用工作区：

```sh
npm ci
npm run setup -- /path/to/WE-workspace YOUR_ACCOUNT_MODEL_ID
```

模型 ID 必须从 WE 当前账号目录复制，不使用固定猜测的模型名。配置只保存环境变量引用，不保存连接密钥或订阅令牌。已有 opencode.json 时 setup 拒绝覆盖。

5. 设置本次会话的环境变量，执行真实账号能力探针（会使用订阅额度）：

```sh
export WE_BRIDGE_KEY='本次临时连接密钥'
npm run probe -- YOUR_ACCOUNT_MODEL_ID
```

PowerShell 使用 `$env:WE_BRIDGE_KEY = '本次临时连接密钥'`，后续 npm 命令相同。探针要求模型调用加法函数、本地执行并回传结果、模型再返回答案。失败时停止引擎接入排查，不自动改用付费 API。

6. 启动引擎，仍使用当前 Android 订阅：

```sh
node start.mjs /path/to/WE-workspace
```

保持 Android 应用运行。当前是原型，没有前台保活服务；系统回收进程后需要重新开启桥接并更新临时密钥，但正常的订阅登录仍由原有持久化机制恢复。关闭桥接、退出订阅或重新授权会断开引擎连接。

## 安装 Skill / MCP

把已有 Skill 文件夹放到 `WE-workspace/.opencode/skills/<name>/SKILL.md`，参考资料保留相对目录。可显式要求引擎使用指定 Skill，或让它按描述匹配。脚本型 Skill 的 Python、命令和二进制依赖运行在电脑上，仍需安装对应运行环境。

DBS 原文：https://github.com/dontbesilent2025/dbskill/tree/main/skills/dbs-learning 。该项目当前 LICENSE 是 CC BY-NC 4.0；本仓库未将其内容重新打包分发，使用时保留来源与许可证。

MCP 沿用 OpenCode 的原生配置。例如在该工作区 opencode.json 增加：

```json
{
  "mcp": {
    "your_tools": {
      "type": "remote",
      "url": "https://YOUR_MCP_HOST/mcp",
      "enabled": true
    }
  }
}
```

本地 MCP 则使用 `type: local` 和 `command: [可执行文件, 参数]`。连接密钥与 MCP 凭据不要提交到仓库。默认工具权限为 ask，读取和 Skill 加载为 allow；按实际工作区需要设置。

## 验证证据与边界

- `tools/check-agent-bridge.sh` 编译真实生产桥接类，检查 HTTP 鉴权、Origin 拒绝、路由限制、完整工具参数和结果、字节级 SSE 保留、连接关闭与临时密钥轮换。
- Node 集成测试启动真实 OpenCode 1.2.27、真实 MCP SDK stdio 服务和生产桥接类。模拟模型先请求加载 Skill，再请求 MCP 加法，收到两轮工具结果后才输出成功标记。模拟服务会检查 Skill 实际内容标记以及真实加法结果。
- 这些测试不使用真实订阅账号，不证明账号允许工具调用。`npm run probe` 才测试设备上的真实授权和所选模型。
- Android CI 另执行完整单测、lint、debug/release 构建。构建结果与账号实机验证分别记录。

## 复用来源

| 组件 | 固定版本 | 作用 | 协议 |
|---|---|---|---|
| https://github.com/anomalyco/opencode | v1.2.27 | Skills、MCP、Agent 循环、Responses provider | MIT |
| https://github.com/NanoHttpd/nanohttpd | 2.3.1 | Android HTTP 服务 | BSD-3-Clause |
| https://github.com/modelcontextprotocol/typescript-sdk | 1.27.1 | 集成测试中的真实 MCP 服务 | MIT |

NanoHTTPD 声明随 APK 放在 assets/agent-licenses。OpenCode 与 MCP SDK 作为锁定 npm 依赖运行，不把另一套完整 App 合并进 WE。
