# WE 对外 MCP

Android 执行工作区工具。公网中继仅转发 JSON 指令和结果，不运行 Agent，不接触 ChatGPT 订阅凭据；转发的内容包含被调用工具读取的资料和手写图像。复用 WordTok 的配对、队列、租约、持久回执与撤销协议，独立部署，不能共用 WordTok 配对或数据文件。

## 在 Android 使用

设置 → Agent 连接 · MCP → 开启连接。本机端点为 `http://127.0.0.1:49327/mcp`，点击“复制本机 MCP 配置”，粘贴给同一 Android 上支持自定义 Authorization 头的客户端。仅绑定 loopback，每次启动生成新密钥，不对局域网裸露服务。

公网使用：填写下文部署后的 **HTTPS 根地址** → 配对 → 开启连接 → 复制公网 MCP 地址。该地址包含随机密钥，保存配对后固定，解除配对后失效。地址只能交给受信任的 Agent。未提供预先托管的公网域名；这份仓库不会自动修改用户的 WordTok 中继或其他站点。

Android 前台服务在切后台后继续连接，通知栏可停止；进程回收、强制停止、网络断开、厂商后台限制会影响可用性。本版不自动开机启动，也不承诺锁屏永久在线。重新打开设置并开启连接会沿用保存的配对。本机 MCP 地址不适用于云端 ChatGPT 连接器。

## 工具

工具定义唯一来源：`app/src/main/assets/we-mcp-tools.json`，Android 和中继共用。

- `list_workspaces` / `create_workspace` / `rename_workspace` / `manage_workspace`：支持名称与归档筛选、置顶和归档；回收站操作只在应用内进行。
- `list_files` / `read_file` / `write_file`：只访问当前工作区 `memory/` 与 `.agents/skills/`；文本不超过 256 KB。现有文件写入需匹配 `expected_sha256`，学习页与会话快照不允许覆盖。
- `read_page`：读取工作区 PDF 的指定页、已保存的笔迹向量和反馈，同时返回 PDF+笔迹的 MCP 图像，由外部 Agent 使用视觉能力理解。不会上传尚未落盘的当前笔画。
- `publish_learning_page`：传入 request 及结构化 page（或旧 content），生成可直接落笔的新 PDF。page 支持题目与答题留白、绘图区、参考图和批注边距，协议见 ../docs/writable-pages.md。read_page 同时返回内容块坐标，关联笔迹与原题。；原页和笔迹保留。打开工作区“资料 → 最近学习页”，或在学习纸上点“查看生成页”继续。
- `get_operation_result`：查询保存回执。

建议流程：列出工作区 → 读取原资料及带手写的页面图像 → 理解反馈 → 发布新页 → 用户继续书写 → 再读取页面。模型、Skill 解释和推理由连接的 Agent 完成，不再要求用户手动串接多个接口。Skill 文件以数据方式写入，MCP 不执行任意 shell、脚本或访问订阅凭据。

写入必须提供 `operation_id`。相同编号与参数重试返回同一回执，不重复创建；同编号不同参数拒绝。设备先保存执行意图，再保存资料与最终回执；如果中途进程被杀，记录保持 running/unknown，**不会自动重放不确定操作**。只有 succeeded 是保存确认。先检查工作区，再决定新操作。中继保留终态回执 7 天；设备保留本地回执以阻止更晚的重放。

## 部署独立公网中继

需要 Node 22+、HTTPS 域名和持久数据目录。以仓库根目录为 Docker 构建上下文：

```sh
docker build -f mcp/Dockerfile -t we-mcp-relay .
docker run -d --name we-mcp-relay --restart unless-stopped \
  -p 127.0.0.1:8787:8080 -v we-mcp-data:/data we-mcp-relay
```

用 Caddy/Nginx 将独立域名的 HTTPS 转发到 `127.0.0.1:8787`，示例见 Caddyfile.example。不要记录 MCP 路径或 Authorization 头。`GET /health` 应返回 name=write-everything、protocol=2、persistent=true。默认拒绝所有浏览器 Origin；只给原生 MCP 客户端使用。确有浏览器客户端时，可通过 `ALLOWED_ORIGINS` 精确列出受信任 Origin，不要使用通配符。

单实例 JSON 存储；不要让多实例同时写同一个数据文件。文件自动设为 600，目录 700。部署前备份数据；丢失数据会丢失配对。配对最多 1000 个，每配对最多 10000 条未清理回执、20 个在途请求，单请求 1 MiB。`DATA_FILE` 指向专用目录，不能入库；不要复用 WordTok 的数据文件。公开配对端点应由反向代理限制频率，适合自用单实例。

Streamable HTTP 协商支持 2025-03-26、2025-06-18、2025-11-25；单 JSON 响应，GET 不提供 SSE 时返回 405，通知返回 202。公网地址是私有配对能力地址，并非通用 OAuth MCP 服务；客户端必须支持此类 URL。未声称兼容所有云端客户端。

## 验证

`node --test mcp/relay.test.mjs` 验证配对、队列、真实 HTTP、回执、重试冲突、重启恢复及撤销。`WorkspaceMcpTest` 验证 Android 工具与本机 HTTP；`McpDeviceTest` 在 Android 模拟器使用真实 PdfRenderer 检查图像、笔迹保留及两轮新页。JVM PDF 使用测试写入器，不能替代设备测试。公网真实域名与手机后台连接需部署后实测。

协议参考：https://modelcontextprotocol.io/specification/2025-11-25/basic/transports

HTTP 层：NanoHTTPD 2.3.1（BSD-3-Clause）。中继来源：本账号 WordTok 的 mcp/relay.mjs；WE 版本按 GPL-3.0 发布，保留上游项目致谢。
