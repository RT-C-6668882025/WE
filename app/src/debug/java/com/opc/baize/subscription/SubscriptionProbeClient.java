package com.opc.baize.subscription;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Debug-only single-registration probe. Tokens live in memory and never reach preferences. */
final class SubscriptionProbeClient implements Closeable {
    interface Browser { void open(String url); }
    private final SharedPreferences registration;
    private volatile ServerSocket listener;
    private volatile HttpsURLConnection connection;
    private volatile boolean closed;
    private JSONObject discovery;
    private JSONObject session;
    private String subject;
    private String clientId;
    private long expiresAt;

    SubscriptionProbeClient(Context context) {
        registration = context.getSharedPreferences("subscription_probe_registration", Context.MODE_PRIVATE);
        subject = registration.getString("subject", null);
        clientId = registration.getString("client_id", "");
    }
    private void check() throws Failure { if (closed || Thread.currentThread().isInterrupted()) throw new Failure("cancelled"); }
    private String hostId() throws Failure {
        String host = registration.getString("host_id", "");
        if (host.isEmpty()) {
            host = "urn:uuid:" + UUID.randomUUID();
            if (!registration.edit().putString("host_id", host).commit()) throw new Failure("registration_save_failed");
        }
        return host;
    }
    private static String officialAuthUrl(String url) throws Exception {
        URI u = new URI(url);
        if (!"https".equals(u.getScheme()) || !"auth.openai.com".equals(u.getHost())
            || u.getRawUserInfo() != null || u.getRawFragment() != null || (u.getPort() != -1 && u.getPort() != 443))
            throw new Failure("invalid_discovery_endpoint");
        return url;
    }
    private JSONObject discover() throws Exception {
        if (discovery == null) {
            JSONObject d = json("GET", ISSUER + "/.well-known/openid-configuration", null, null, null);
            if (!ISSUER.equals(d.optString("issuer"))) throw new Failure("invalid_discovery_issuer");
            for (String key : new String[]{"authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint"})
                officialAuthUrl(d.getString(key));
            discovery = d;
        }
        return discovery;
    }
    String signIn(Browser browser) throws Exception {
        check(); JSONObject d = discover(); String host = hostId();
        String state = random(), nonce = random(), verifier = random();
        ServerSocket server = new ServerSocket(); listener = server;
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        server.setSoTimeout(1000);
        String redirect = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
        try {
            browser.open(authorize(d.getString("authorization_endpoint"), redirect, host, clientId, state, nonce, verifier));
            Map<String, String> cb = waitCallback(server, state);
            check();
            clientId = cb.get("client_id");
            // Save the non-secret issued registration before exchanging a one-use code.
            if (!registration.edit().putString("client_id", clientId).commit()) throw new Failure("registration_save_failed");
            JSONObject result = json("POST", d.getString("token_endpoint"), form(fields(
                "grant_type", "authorization_code", "client_id", clientId, "code", cb.get("code"),
                "code_verifier", verifier, "redirect_uri", redirect, "resource", RESOURCE)),
                "application/x-www-form-urlencoded", null);
            install(result, nonce, false);
            return hasDirectPermission() ? "身份与订阅权限验证通过；尚未验证模型请求。" : "身份验证通过，但未授权订阅调用。请重新授权或使用原模型接口。";
        } finally { server.close(); if (listener == server) listener = null; }
    }
    private Map<String, String> waitCallback(ServerSocket server, String state) throws Exception {
        long deadline = System.nanoTime() + 300_000_000_000L;
        while (System.nanoTime() < deadline) {
            check();
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                String line = boundedLine(socket.getInputStream(), 16384);
                String[] request = line.split(" ");
                Map<String, String> result = null; Failure failure = null;
                try {
                    if (request.length != 3 || !"GET".equals(request[0]) || !request[2].startsWith("HTTP/1.")) throw new Failure("invalid_callback_request");
                    result = callback(request[1], state, clientId);
                } catch (Failure e) { failure = e; }
                  catch (Exception e) { failure = new Failure("invalid_callback_request"); }
                String text = failure == null ? "Authorization received. Return to Moling to see verification results." : "Callback rejected. Return to Moling or retry sign-in.";
                byte[] body = text.getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(((failure == null ? "HTTP/1.1 200 OK" : "HTTP/1.1 400 Bad Request")
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body); socket.getOutputStream().flush();
                if (failure == null) return result;
                if (failure.code.equals("access_denied") || failure.code.equals("oauth_callback_error")) throw failure;
            } catch (SocketTimeoutException ignored) { /* Continue accepting until overall deadline. */ }
        }
        throw new Failure("authorization_timeout");
    }
    private void install(JSONObject result, String nonce, boolean refreshing) throws Exception {
        JSONObject claims;
        if (refreshing && !result.has("id_token") && subject != null) {
            // OAuth refresh can omit an ID token; the existing verified registration stays bound.
            claims = new JSONObject().put("sub", subject);
        } else {
            claims = IdentityVerifier.verify(result.getString("id_token"),
                json("GET", discover().getString("jwks_uri"), null, null, null), clientId, nonce, subject, System.currentTimeMillis() / 1000);
        }
        String scopes = result.optString("scope", refreshing && session != null ? session.getString("scope") : "");
        if (scopes.isEmpty()) throw new Failure("missing_granted_scopes");
        long ttl = result.optLong("expires_in", 0);
        if (ttl <= 0 || ttl > 604800 || !"bearer".equalsIgnoreCase(result.optString("token_type"))
            || result.optString("access_token").isEmpty()) throw new Failure("invalid_token_response");
        String refresh = result.optString("refresh_token", refreshing && session != null ? session.optString("refresh_token") : "");
        if (hasScope(scopes, "offline_access") && refresh.isEmpty()) throw new Failure("missing_refresh_token");
        check();
        String nextSubject = claims.getString("sub");
        if (!registration.edit().putString("subject", nextSubject).commit()) throw new Failure("registration_save_failed");
        result.put("scope", scopes).put("refresh_token", refresh);
        subject = nextSubject; session = result; expiresAt = System.currentTimeMillis() + ttl * 1000;
    }
    boolean hasDirectPermission() { return session != null && hasScope(session.optString("scope"), DIRECT_SCOPE) && hasScope(session.optString("scope"), "resource.invoke"); }
    private String token() throws Exception {
        check();
        if (!hasDirectPermission()) throw new Failure("plan_permission_required");
        if (System.currentTimeMillis() >= expiresAt - 30000) throw new Failure("token_expired_refresh_or_sign_in");
        return session.getString("access_token");
    }
    List<String> models() throws Exception {
        JSONObject result = json("GET", RESOURCE + "/models", null, null, token());
        JSONArray models = result.getJSONArray("models"); List<String> slugs = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            if ("list".equals(m.optString("visibility")) && !m.optString("slug").isEmpty()) slugs.add(m.getString("slug"));
        }
        if (slugs.isEmpty()) throw new Failure("no_visible_models");
        return slugs;
    }
    String inference(String model, String image) throws Exception {
        HttpsURLConnection c = open("POST", RESOURCE + "/responses", token());
        try {
            c.setRequestProperty("Accept", "text/event-stream");
            send(c, request(model, image).toString(), "application/json");
            requireSuccess(c);
            String type = c.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) throw new Failure("invalid_stream_content_type");
            Stream stream = new Stream(); long deadline = System.nanoTime() + 120_000_000_000L;
            InputStream in = new BufferedInputStream(c.getInputStream());
            while (!stream.completed) {
                check(); if (System.nanoTime() > deadline) throw new Failure("stream_timeout");
                String line = boundedLineOrNull(in, 2 * 1024 * 1024);
                if (line == null) break;
                try { stream.line(line); }
                catch (Failure e) { throw new Failure(e.code, c.getResponseCode(), c.getHeaderField("x-request-id")); }
            }
            return stream.finish();
        } finally { c.disconnect(); connection = null; }
    }
    String refresh() throws Exception {
        check();
        if (session == null || session.optString("refresh_token").isEmpty()) throw new Failure("sign_in_required");
        if (System.currentTimeMillis() < expiresAt - 60000) throw new Failure("refresh_not_due");
        Object earliest = session.opt("earliest_refresh_at");
        if (earliest != null && earliest != JSONObject.NULL) {
            long allowed;
            if (earliest instanceof Number) allowed = ((Number) earliest).longValue() * 1000;
            else {
                try { allowed = java.time.Instant.parse(earliest.toString()).toEpochMilli(); }
                catch (Exception e) { throw new Failure("invalid_refresh_schedule"); }
            }
            if (System.currentTimeMillis() < allowed) throw new Failure("refresh_not_due");
        }
        JSONObject old = session;
        // Single worker serializes rotation. A failed validation requires a new sign-in; no old-token retry.
        try {
            JSONObject next = json("POST", discover().getString("token_endpoint"), form(fields(
                "grant_type", "refresh_token", "client_id", clientId, "refresh_token", old.getString("refresh_token"),
                "resource", RESOURCE)), "application/x-www-form-urlencoded", null);
            install(next, null, true); return "令牌刷新并验证身份成功。";
        } catch (Exception e) { session = null; throw e; }
    }
    String signOut() {
        JSONObject old = session; session = null;
        if (old == null || old.optString("refresh_token").isEmpty()) return "已清除本地会话；注册与 host ID 保留。";
        try {
            HttpsURLConnection c = open("POST", discover().getString("revocation_endpoint"), null);
            try {
                send(c, form(fields("token", old.getString("refresh_token"), "token_type_hint", "refresh_token", "client_id", clientId)), "application/x-www-form-urlencoded");
                if (c.getResponseCode() != 200) return "本地会话已清除，远端撤销未确认；请在 ChatGPT 设置中断开应用。";
                return "远端会话撤销成功；本地令牌已清除。";
            } finally { c.disconnect(); connection = null; }
        } catch (Exception e) { return "本地会话已清除，远端撤销未确认；请在 ChatGPT 设置中断开应用。"; }
    }
    private HttpsURLConnection open(String method, String url, String bearer) throws Exception {
        check();
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection(); connection = c;
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setRequestMethod(method);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        check(); return c;
    }
    private static void send(HttpsURLConnection c, String body, String type) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true); c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }
    private JSONObject json(String method, String url, String body, String type, String bearer) throws Exception {
        HttpsURLConnection c = open(method, url, bearer);
        try {
            if (body != null) send(c, body, type);
            requireSuccess(c);
            try (InputStream in = c.getInputStream()) { return new JSONObject(read(in, 2 * 1024 * 1024)); }
        } finally { c.disconnect(); connection = null; }
    }
    private static void requireSuccess(HttpsURLConnection c) throws Exception {
        int status = c.getResponseCode();
        if (status >= 200 && status < 300) return;
        String code = "http_error";
        try (InputStream in = c.getErrorStream()) {
            if (in != null) {
                JSONObject obj = new JSONObject(read(in, 32768));
                Object err = obj.opt("error");
                if (err instanceof JSONObject) code = errorCode((JSONObject) err);
                else if (err instanceof String) code = errorCode(new JSONObject().put("code", err));
                else if (obj.has("detail")) code = "admission_rejected_detail";
            }
        } catch (Exception ignored) { /* Report status without echoing server bodies. */ }
        throw new Failure(code, status, c.getHeaderField("x-request-id"));
    }
    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new Failure("body_too_large"); out.write(b, 0, n); }
        return out.toString("UTF-8");
    }
    static String boundedLineOrNull(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (out.size() >= limit) throw new Failure("line_too_large"); out.write(b);
        }
        if (b == -1 && out.size() == 0) return null;
        String line = out.toString("UTF-8"); return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
    private static String boundedLine(InputStream in, int limit) throws Exception {
        String line = boundedLineOrNull(in, limit); if (line == null) throw new Failure("empty_callback_request"); return line;
    }
    @Override public void close() {
        closed = true; session = null;
        HttpsURLConnection c = connection; if (c != null) c.disconnect();
        ServerSocket s = listener; if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
