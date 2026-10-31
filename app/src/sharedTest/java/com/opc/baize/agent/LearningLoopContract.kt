@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

import android.content.Context
import cc.noharry.agent.model.OpenAiModelClient
import com.opc.baize.paper.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real runtime tools and renderer, deterministic model; never uses account credentials. */
object LearningLoopContract {
    fun verify(context: Context,
        render: (File, File) -> Unit = { source, output -> LearningPageRenderer.render(source, output, true) },
        validatePdf: Boolean = true) = runBlocking {
        val workspace = WorkspaceFiles(context).create("loop-${UUID.randomUUID()}")
        try {
            suspend fun generate(turn: LearningTurn, content: String, previous: String? = null, omitFile: Boolean = false): File {
                var round = 0
                val transport = SubscriptionAgentTransport({ raw ->
                    val input = JSONObject(raw).getJSONArray("input").toString()
                    val actions = buildList {
                        if (previous != null) add("read_file" to JSONObject().put("path", previous).toString())
                        if (!omitFile) {
                            add("write_file" to JSONObject().put("path", "/memory/pages/${turn.source.name}").put("content", content).toString())
                            add("read_file" to JSONObject().put("path", "/memory/pages/${turn.source.name}").toString())
                        }
                        add("finish" to JSONObject().put("response", "LEARNING_DONE").toString())
                    }
                    if (previous != null && round > 0) assertTrue("Previous lesson was not read: $input", input.contains("FIRST_LESSON"))
                    val (name, args) = actions[round++]
                    val call = JSONObject().put("type", "function_call").put("id", "fc_${turn.id}_$round").put("call_id", "call_${turn.id}_$round")
                        .put("name", name).put("arguments", args).put("status", "completed")
                    val completed = JSONObject().put("type", "response.completed").put("response", JSONObject()
                        .put("id", "resp_${turn.id}_$round").put("status", "completed").put("output", JSONArray().put(call)))
                    "data: " + JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", call) + "\n\n" +
                        "data: " + JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", call) + "\n\n" +
                        "data: $completed\n\n"
                }, File(workspace.container, "checkpoints-${turn.id}.json"))
                val client = OpenAiModelClient({ "test-placeholder" }, model = "fixture", endpoint = "responses", stream = true,
                    serverSideSearch = false, transport = transport)
                val runtime = NativeAgentHost.buildWithClient(context, workspace, client, "[]")
                return try { turn.execute(runtime, render = render) } finally { runtime.close() }
            }
            val first = LearningTurn.prepare(workspace, "帮我练英语时态")
            val firstPdf = generate(first, "FIRST_LESSON\nFill in: He ___ yesterday.\n请在下面作答。")
            val repository = PaperRepository(context)
            val oldId = repository.importPdf(android.net.Uri.fromFile(firstPdf))
            val ink = PaperPage(600, 840, 1, listOf(PaperStroke(listOf(InkPoint(100f, 140f, 0.5f, 1)))))
            repository.save(oldId, 0, ink)
            val oldPdfBytes = firstPdf.readBytes()
            val path = LearningTurn.sourcePath(firstPdf)
            val second = LearningTurn.prepare(workspace, "手写识别结果：went。要求：解释过去式，再出一道题。", path,
                "文档 $oldId，第 1 页，笔迹版本 1，整页")
            assertTrue(second.prompt.contains("went")); assertTrue(second.prompt.contains(path))
            val secondPdf = generate(second, "SECOND_LESSON\nwent is the past tense of go.\nTry one more question.", path)
            assertNotEquals(firstPdf, secondPdf)
            assertArrayEquals(oldPdfBytes, firstPdf.readBytes())
            assertEquals(ink, repository.load(oldId, 0, 600, 840))
            if (validatePdf) AndroidPdfEngine(secondPdf).use { assertTrue(it.pageCount >= 1) }
            assertEquals(secondPdf, LearningTurn.latestReady(workspace))
            assertTrue(workspace.transcript.readText().contains("went"))
            val missing = LearningTurn.prepare(workspace, "Do not pretend an unsaved answer is a page")
            var rejected = false
            try { generate(missing, "", omitFile = true) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Missing page must be rejected", rejected)
            assertEquals(secondPdf, LearningTurn.latestReady(workspace))
            val journal = JSONObject(File(workspace.container, "turns/${missing.id}.json").readText())
            assertEquals("interrupted", journal.getString("state"))
            var escaped = false
            try { LearningTurn.prepare(workspace, "bad source", "/../title.txt") } catch (_: IllegalArgumentException) { escaped = true }
            assertTrue(escaped)
            Unit
        } finally { workspace.container.deleteRecursively() }
    }
}
