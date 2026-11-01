# 绘画 MCP 的设备端与服务端升级

设备端新增六个绘画工具，工具总数为十六个。既有订阅授权、Keystore、会话恢复、模型目录和模型选择实现没有修改。

## 设备验收

CI 会检查原子编辑失败回滚、版本冲突、持久化撤销/重做、旧版本写入保护、当前画布即时更新、带图片导出和既有订阅回归。`DrawingDeviceTest.mcpEditsTheVisibleCanvasAndExportsTheSameRevision` 在 Android 前台实际打开画布，经 MCP 添加图层与图形，确认编辑器与导出版本一致，并保存截图供视觉检查。

## 目标服务器部署

当前会话不能直接 SSH 目标服务器，因此不能声称线上已出现这六个工具。在服务器本地取得本分支代码后执行：

```bash
sudo bash tools/deploy-we-drawing-relay.sh
```

脚本仅适用于已知的 `we-mcp.service`：工作目录 `/home/ubuntu/we-mcp`，启动脚本 `relay-local-we.mjs`，Node `/opt/node22/bin/node`，监听 `127.0.0.1:8787`，配对数据 `/var/lib/we-mcp/relay.json`。任何一项不符会在修改前停止；不要套用到 WordTok 或其他服务。

脚本备份旧启动文件和配对数据，将中继代码与同一份工具清单放入按提交分隔的版本目录，保留数据路径与允许来源，仅重启 WE 服务。检查本机健康、`https://localhost` 来源响应头，以及现有配对的十六个工具名称；不输出配对凭据、不重新配对，不改 nginx、证书或其他服务。验收失败仅回滚启动脚本，不回滚运行期间可能更新过的配对数据。

服务器完成后仍需用原有配对的外部 Agent 验收：列出十六个工具 → 列出画布 → 读取 revision → 编辑 → 在 App 中看到结果 → 导出收到图片。旧版本 App 不认识新工具，须同时升级设备端。
