package com.opc.baize.subscription;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Synthetic model behind the real Android bridge class, for the real OpenCode+MCP contract. */
public final class AgentBridgeFixture {
    static JSONObject event(String type) throws Exception { return new JSONObject().put("type", type); }
    static void emit(StringBuilder out, JSONObject event) { out.append("data: ").append(event).append("\n\n"); }
    public static void main(String[] args) throws Exception {
        AgentSubscriptionBridge bridge = new AgentSubscriptionBridge(0, new AgentSubscriptionBridge.Backend() {
            public JSONObject models() throws Exception { return new JSONObject("{\"object\":\"list\",\"data\":[{\"id\":\"we-fixture\"}]}"); }
            public AgentSubscriptionBridge.Upstream responses(JSONObject request) throws Exception {
                JSONArray input = request.getJSONArray("input");
                JSONArray tools = request.getJSONArray("tools");
                boolean hasSkill = false, hasMcp = false, readSkill = false, added = false;
                for (int i=0; i<tools.length(); i++) {
                    hasSkill |= "skill".equals(tools.getJSONObject(i).optString("name"));
                    hasMcp |= "we_fixture_add".equals(tools.getJSONObject(i).optString("name"));
                }
                if (!hasSkill || !hasMcp || request.getBoolean("store")) throw new Exception("engine_tools_missing");
                for (int i=0; i<input.length(); i++) {
                    JSONObject item = input.getJSONObject(i);
                    if (!"function_call_output".equals(item.optString("type"))) continue;
                    if ("call_skill".equals(item.optString("call_id"))) {
                        if (!item.optString("output").contains("WE_SKILL_FIXTURE_MARKER")) throw new Exception("skill_not_loaded");
                        readSkill = true;
                    }
                    if ("call_mcp".equals(item.optString("call_id"))) {
                        if (!item.optString("output").contains("12")) throw new Exception("mcp_result_wrong");
                        added = true;
                    }
                }
                String id = "resp_fixture_" + (added ? "3" : readSkill ? "2" : "1");
                JSONObject item;
                if (added) item = new JSONObject().put("type", "message").put("id", "msg_fixture").put("role", "assistant").put("status", "completed")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "WE_ENGINE_OK_12").put("annotations", new JSONArray())));
                else item = new JSONObject().put("type", "function_call").put("id", readSkill ? "fc_mcp" : "fc_skill")
                    .put("call_id", readSkill ? "call_mcp" : "call_skill").put("name", readSkill ? "we_fixture_add" : "skill")
                    .put("arguments", readSkill ? "{\"a\":7,\"b\":5}" : "{\"name\":\"we-bridge-fixture\"}").put("status", "completed");
                JSONObject response = new JSONObject().put("id", id).put("object", "response").put("created_at", 1700000000).put("model", "we-fixture")
                    .put("status", "completed").put("output", new JSONArray().put(item))
                    .put("usage", new JSONObject().put("input_tokens", 10).put("output_tokens", 5).put("total_tokens", 15)
                        .put("input_tokens_details", new JSONObject().put("cached_tokens", 0)).put("output_tokens_details", new JSONObject().put("reasoning_tokens", 0)));
                StringBuilder sse = new StringBuilder();
                emit(sse, event("response.created").put("response", new JSONObject(response.toString()).put("status", "in_progress").put("output", new JSONArray())));
                JSONObject start = new JSONObject(item.toString());
                if (added) start.put("content", new JSONArray()); else start.put("arguments", "");
                emit(sse, event("response.output_item.added").put("output_index", 0).put("item", start));
                if (added) {
                    emit(sse, event("response.content_part.added").put("item_id", item.getString("id")).put("output_index", 0).put("content_index", 0)
                        .put("part", new JSONObject().put("type", "output_text").put("text", "").put("annotations", new JSONArray())));
                    emit(sse, event("response.output_text.delta").put("item_id", item.getString("id")).put("output_index", 0).put("content_index", 0).put("delta", "WE_ENGINE_OK_12"));
                } else {
                    emit(sse, event("response.function_call_arguments.delta").put("item_id", item.getString("id")).put("output_index", 0).put("delta", item.getString("arguments")));
                    emit(sse, event("response.function_call_arguments.done").put("item_id", item.getString("id")).put("output_index", 0).put("arguments", item.getString("arguments")));
                }
                emit(sse, event("response.output_item.done").put("output_index", 0).put("item", item));
                emit(sse, event("response.completed").put("response", response));
                byte[] bytes = sse.toString().getBytes(StandardCharsets.UTF_8);
                System.err.println("fixture_round=" + (added ? 3 : readSkill ? 2 : 1));
                return new AgentSubscriptionBridge.Upstream(new ByteArrayInputStream(bytes), () -> {});
            }
        });
        bridge.start(15000, true);
        System.out.println(new JSONObject().put("baseURL", "http://127.0.0.1:" + bridge.getListeningPort() + "/v1").put("key", bridge.password()));
        System.out.flush();
        try { while (System.in.read() != -1) {} } finally { bridge.stop(); }
    }
}
