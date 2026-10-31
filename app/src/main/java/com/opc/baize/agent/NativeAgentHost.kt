@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

import android.content.Context
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolProvider
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.runtime.android.AndroidAgentRuntime
import cc.noharry.agent.transport.OkHttpHttpTransport
import com.opc.baize.subscription.SubscriptionGateway
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Entire orchestration runs in this Android process. No socket bridge, ADB, Node or remote engine. */
object NativeAgentHost {
    fun build(context: Context, workspace: WeWorkspace, model: String): AgentRuntime {
        require(model.isNotBlank()) { "请先选择订阅文字模型" }
        val gateway = SubscriptionGateway.get(context)
        check(gateway.isAuthorized) { "请先登录 ChatGPT 订阅" }
        val transport = SubscriptionAgentTransport(gateway::agentResponse,
            File(context.noBackupFilesDir, "we-agent/${workspace.id}/responses.json"))
        // The placeholder satisfies the reusable serializer; only our transport can execute it.
        // It is never sent to a network endpoint. Gateway attaches its own existing OAuth token.
        val client = OpenAiModelClient({ "managed-by-we" }, model = model, endpoint = "responses",
            stream = true, serverSideSearch = false, transport = transport)
        return buildWithClient(context, workspace, client, WorkspaceFiles(context).mcp(workspace))
    }
    fun buildWithClient(context: Context, workspace: WeWorkspace, client: ModelClient, mcpJson: String): AgentRuntime {
        val builder = AndroidAgentRuntime.builder(context)
            .workspaceRoot(workspace.root)
            .sessionEventLogRoot(File(context.noBackupFilesDir, "we-agent/${workspace.id}/events"))
            // 0.3.0's native read tools use a partial JSON string decoder that rejects legal
            // escaped slashes in paths. Canonical JSON is semantically identical. Original
            // provider function-call items remain in SubscriptionAgentTransport's checkpoint.
            .modelClient(object : ModelClient by client {
                override fun complete(request: ModelRequest) = client.complete(request).map { event ->
                    if (event is ModelEvent.ToolCall) event.copy(argumentsJson =
                        runCatching { Json.parseToJsonElement(event.argumentsJson).toString() }.getOrDefault(event.argumentsJson))
                    else event
                }
            }).modelConcurrency(1).modelRetry(maxAttempts = 1)
            .loopBudget(maxModelRounds = 20, maxToolCallsPerRun = 40, maxWallClockMillis = 240000)
            .nativeWebFetchEnabled(false)
            .systemPrompt("你在 WE（Write Everything）的 Android 工作区中。用中文工作。按需读取 Skill 原文和参考资料。使用 /memory/pages/ 作为学习内容和 Skill 输出的持久根目录，保留相对目录结构；/scratch/ 仅用于临时数据。根据用户反馈继续推进；版面应适合手写，预留书写空间。未经用户要求不改变原文件。不要声称完成尚未调用的工具或尚未保存的内容。")
        if (workspace.transcript.isFile) builder.runContext("上次保存的工作区对话：\n" + workspace.transcript.readText().takeLast(48000))
        val servers = JSONArray(mcpJson)
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            builder.toolProvider(McpToolProvider(McpHttpClient(server.getString("url"), { emptyMap() },
                OkHttpHttpTransport(), "WE-Android", "0.4.0"), serverId = server.getString("id")))
        }
        return builder.build()
    }
}
