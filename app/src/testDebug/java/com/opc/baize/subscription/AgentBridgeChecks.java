package com.opc.baize.subscription;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real loopback HTTP checks with synthetic model output, no account or Android required. */
public final class AgentBridgeChecks {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        AtomicInteger calls = new AtomicInteger(); AtomicInteger closed = new AtomicInteger();
        java.util.concurrent.CountDownLatch cleanup = new java.util.concurrent.CountDownLatch(1);
        String sse = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"add\",\"arguments\":\"{\\\"a\\\":7}\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
        AgentSubscriptionBridge bridge = new AgentSubscriptionBridge(0, new AgentSubscriptionBridge.Backend() {
            public JSONObject models() throws Exception { return new JSONObject("{\"object\":\"list\",\"data\":[{\"id\":\"fixture\"}]}"); }
            public AgentSubscriptionBridge.Upstream responses(JSONObject body) throws Exception {
                calls.incrementAndGet();
                require(!body.getBoolean("store"), "store must be false");
                require(body.getJSONArray("tools").getJSONObject(0).getString("name").equals("add"), "tools lost");
                require(body.getJSONArray("input").getJSONObject(0).getString("call_id").equals("c0"), "call output lost");
                return new AgentSubscriptionBridge.Upstream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), () -> { closed.incrementAndGet(); cleanup.countDown(); });
            }
        });
        bridge.start(2000, true);
        String url = "http://127.0.0.1:" + bridge.getListeningPort();
        try {
            require(request(url, "GET", "/v1/models", null, null, null).status == 401, "missing auth accepted");
            require(request(url, "GET", "/v1/models", "wrong", null, null).status == 401, "wrong auth accepted");
            require(request(url, "GET", "/v1/models", bridge.password(), null, "https://example.test").status == 403, "browser accepted");
            require(request(url, "GET", "/v1/models", bridge.password(), null, null).status == 200, "models failed");
            require(request(url, "POST", "/v1/chat/completions", bridge.password(), "{}", null).status == 404, "arbitrary route accepted");
            require(request(url, "POST", "/v1/responses", bridge.password(), "{\"stream\":false}", null).status == 400, "non-stream accepted");
            require(request(url, "POST", "/v1/responses", bridge.password(), "{\"stream\":true,\"previous_response_id\":\"old\"}", null).status == 400, "remote state accepted");
            Result response = request(url, "POST", "/v1/responses", bridge.password(), "{\"model\":\"fixture\",\"stream\":true,\"store\":true,\"tools\":[{\"type\":\"function\",\"name\":\"add\"}],\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"c0\",\"output\":\"12\"}]}", null);
            require(response.status == 200 && response.body.equals(sse), "SSE bytes changed");
            require(calls.get() == 1, "unexpected inference call");
            require(cleanup.await(2, java.util.concurrent.TimeUnit.SECONDS) && closed.get() == 1, "upstream not closed exactly once");
        } finally { bridge.stop(); }
        AgentSubscriptionBridge next = new AgentSubscriptionBridge(0, null);
        require(!bridge.password().equals(next.password()), "bridge key reused");
        System.out.println("PASS: authenticated loopback routing, origin rejection, stateless requests, tools/results preserved, exact SSE, upstream cleanup, ephemeral key.");
    }
    static final class Result { final int status; final String body; Result(int s, String b) { status=s; body=b; } }
    static Result request(String base, String method, String path, String key, String body, String origin) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(3000); c.setRequestMethod(method);
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        if (origin != null) c.setRequestProperty("Origin", origin);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            return new Result(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }
}
