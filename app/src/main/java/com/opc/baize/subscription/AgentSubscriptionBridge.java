package com.opc.baize.subscription;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in loopback Responses transport. The engine owns tools; WE alone owns OAuth. */
public final class AgentSubscriptionBridge extends NanoHTTPD {
    public static final int DEFAULT_PORT = 43187;
    static final int MAX_BODY = 8 * 1024 * 1024;
    public interface Backend {
        JSONObject models() throws Exception;
        Upstream responses(JSONObject request) throws Exception;
    }
    public static final class Upstream implements Closeable {
        public final InputStream body;
        private final Closeable owner;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        public Upstream(InputStream body, Closeable owner) { this.body = body; this.owner = owner; }
        @Override public void close() throws IOException { if (closed.compareAndSet(false, true)) owner.close(); }
    }
    private final Backend backend;
    private final String password;
    private final Set<Upstream> streams = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    public AgentSubscriptionBridge(int port, Backend backend) {
        super("127.0.0.1", port);
        this.backend = backend;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String password() { return password; }
    @Override public Response serve(IHTTPSession session) {
        if (stopped) return error(Response.Status.SERVICE_UNAVAILABLE, "bridge_stopped");
        String expected = "Bearer " + password;
        String supplied = session.getHeaders().getOrDefault("authorization", "");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            return error(Response.Status.UNAUTHORIZED, "bridge_auth_required");
        // Browser pages must not be able to drive this credentialed loopback endpoint.
        if (session.getHeaders().containsKey("origin")) return error(Response.Status.FORBIDDEN, "browser_origin_rejected");
        try {
            if (session.getMethod() == Method.GET && "/v1/models".equals(session.getUri()))
                return json(Response.Status.OK, backend.models());
            if (session.getMethod() != Method.POST || !"/v1/responses".equals(session.getUri()))
                return error(Response.Status.NOT_FOUND, "route_not_supported");
            if (session.getHeaders().containsKey("transfer-encoding"))
                return error(Response.Status.BAD_REQUEST, "content_length_required");
            String length = session.getHeaders().get("content-length");
            long size;
            try { size = Long.parseLong(length == null ? "-1" : length); }
            catch (NumberFormatException e) { return error(Response.Status.BAD_REQUEST, "invalid_content_length"); }
            if (size <= 0 || size > MAX_BODY) return error(Response.Status.BAD_REQUEST, "invalid_body_size");
            byte[] bytes = new byte[(int) size];
            new DataInputStream(session.getInputStream()).readFully(bytes);
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!Boolean.TRUE.equals(request.opt("stream")) || request.optBoolean("background") || request.has("previous_response_id"))
                return error(Response.Status.BAD_REQUEST, "stateless_stream_required");
            // Preserve tools, call IDs, reasoning items, function outputs and all SSE events.
            request.put("store", false);
            Upstream upstream = backend.responses(request);
            streams.add(upstream);
            if (stopped) { streams.remove(upstream); upstream.close(); return error(Response.Status.SERVICE_UNAVAILABLE, "bridge_stopped"); }
            InputStream stream = new FilterInputStream(upstream.body) {
                @Override public void close() throws IOException {
                    try { upstream.close(); } finally { streams.remove(upstream); }
                }
            };
            Response response = newChunkedResponse(Response.Status.OK, "text/event-stream", stream);
            response.addHeader("Cache-Control", "no-store");
            response.closeConnection(true);
            return response;
        } catch (SubscriptionProtocol.Failure e) {
            return error(e.status == 401 ? Response.Status.UNAUTHORIZED : Response.Status.INTERNAL_ERROR, e.code);
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, "bridge_request_failed");
        }
    }
    private static Response json(Response.Status status, JSONObject value) {
        Response response = newFixedLengthResponse(status, "application/json", value.toString());
        response.addHeader("Cache-Control", "no-store");
        response.closeConnection(true);
        return response;
    }
    private static Response error(Response.Status status, String code) {
        String safe = JSONObject.quote(code);
        Response response = newFixedLengthResponse(status, "application/json", "{\"error\":{\"type\":\"bridge_error\",\"code\":" + safe + ",\"message\":" + safe + "}}");
        response.addHeader("Cache-Control", "no-store");
        response.closeConnection(true);
        return response;
    }
    @Override public void stop() {
        stopped = true;
        for (Upstream stream : streams) try { stream.close(); } catch (IOException ignored) {}
        streams.clear(); super.stop();
    }
}
