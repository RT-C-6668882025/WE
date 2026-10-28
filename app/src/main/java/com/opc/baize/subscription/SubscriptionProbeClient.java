package com.opc.baize.subscription;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.Response;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Single-registration official subscription client. Tokens live in memory and never reach preferences. */
final class SubscriptionProbeClient implements Closeable {
    interface Browser { void open(String url); }
    private final SharedPreferences registration;
    private volatile LoopbackReceiver listener;
    private final ProbeDiagnostics diagnostics = new ProbeDiagnostics();
    String diagnostics() { return diagnostics.snapshot(); }
    String healthUrl() { LoopbackReceiver r = listener; return r == null ? null : r.healthUrl(); }
    void beginOperation() { diagnostics.reset(); }
    private final ProbeHttp http = new ProbeHttp(diagnostics);
    private final IdentityKeyCache identityKeys = new IdentityKeyCache(
        () -> json("GET", discover().getString("jwks_uri"), null, null, null));
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
        check(); session = null; expiresAt = 0; diagnostics.mark(ProbeDiagnostics.Stage.DISCOVERY);
        JSONObject d = discover(); String host = hostId();
        diagnostics.mark(ProbeDiagnostics.Stage.PREFETCH_KEYS);
        identityKeys.prefetch();
        check();
        String state = random(), nonce = random(), verifier = random();
        LoopbackReceiver receiver = new LoopbackReceiver(); listener = receiver;
        String redirect = receiver.callbackUrl();
        try {
            diagnostics.mark(ProbeDiagnostics.Stage.WAIT_CALLBACK);
            browser.open(authorize(d.getString("authorization_endpoint"), redirect, host, clientId, state, nonce, verifier));
            Map<String, String> cb = receiver.await(state, clientId, diagnostics);
            check();
            clientId = cb.get("client_id");
            // Save the non-secret issued registration before exchanging a one-use code.
            if (!registration.edit().putString("client_id", clientId).commit()) throw new Failure("registration_save_failed");
            diagnostics.mark(ProbeDiagnostics.Stage.TOKEN_EXCHANGE);
            JSONObject result = json("POST", d.getString("token_endpoint"), form(fields(
                "grant_type", "authorization_code", "client_id", clientId, "code", cb.get("code"),
                "code_verifier", verifier, "redirect_uri", redirect, "resource", RESOURCE)),
                "application/x-www-form-urlencoded", null);
            install(result, nonce, false);
            diagnostics.mark(ProbeDiagnostics.Stage.AUTHORIZED);
            return hasDirectPermission() ? "身份与订阅权限验证通过；尚未验证模型请求。" : "身份验证通过，但未授权订阅调用。请重新授权或使用原模型接口。";
        } finally { receiver.close(); if (listener == receiver) listener = null; }
    }
    private void install(JSONObject result, String nonce, boolean refreshing) throws Exception {
        diagnostics.mark(ProbeDiagnostics.Stage.VERIFY_IDENTITY);
        JSONObject claims;
        if (refreshing && !result.has("id_token") && subject != null) {
            // OAuth refresh can omit an ID token; the existing verified registration stays bound.
            claims = new JSONObject().put("sub", subject);
        } else {
            claims = identityKeys.verify(result.getString("id_token"),
                clientId, nonce, subject, System.currentTimeMillis() / 1000);
        }
        validateTokenFields(result, session, refreshing);
        long ttl = result.optLong("expires_in", 0);
        check();
        String nextSubject = claims.getString("sub");
        if (!registration.edit().putString("subject", nextSubject).commit()) throw new Failure("registration_save_failed");
        subject = nextSubject; session = result; expiresAt = System.currentTimeMillis() + ttl * 1000;
    }
    boolean hasDirectPermission() { return session != null && hasScope(session.optString("scope"), DIRECT_SCOPE) && hasScope(session.optString("scope"), "resource.invoke"); }
    private String token() throws Exception {
        check();
        if (!hasDirectPermission()) throw new Failure("plan_permission_required");
        if (System.currentTimeMillis() >= expiresAt - 30000) refresh();
        if (!hasDirectPermission()) throw new Failure("sign_in_required");
        return session.getString("access_token");
    }
    List<ModelCatalog.Entry> catalog() throws Exception {
        String access = token(); diagnostics.mark(ProbeDiagnostics.Stage.MODELS);
        return ModelCatalog.parse(json("GET", RESOURCE + "/models", null, null, access));
    }
    List<String> models() throws Exception {
        List<String> ids = new ArrayList<>();
        for (ModelCatalog.Entry model : catalog()) ids.add(model.id);
        return ids;
    }
    String inference(String model, String image) throws Exception {
        return reply(request(model, image));
    }
    String reply(JSONObject requestBody) throws Exception {
        String access = token(); diagnostics.mark(ProbeDiagnostics.Stage.INFERENCE);
        try (Response response = http.execute("POST", RESOURCE + "/responses", requestBody.toString(),
                "application/json", access, "text/event-stream")) {
            requireSuccess(response);
            String type = response.header("Content-Type");
            if (type != null && !type.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) throw new Failure("invalid_stream_content_type");
            if (response.body() == null) throw new Failure("empty_response_body");
            Stream stream = new Stream();
            InputStream in = new BufferedInputStream(response.body().byteStream());
            while (!stream.completed) {
                check();
                String line = boundedLineOrNull(in, 2 * 1024 * 1024);
                if (line == null) break;
                try { stream.line(line); }
                catch (Failure e) { throw new Failure(e.code, response.code(), response.header("x-request-id")); }
            }
            return stream.finish();
        } catch (IOException e) { throw http.failure(e); }
    }
    String refresh() throws Exception {
        diagnostics.mark(ProbeDiagnostics.Stage.REFRESH);
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
        diagnostics.mark(ProbeDiagnostics.Stage.REVOKE);
        JSONObject old = session; session = null;
        if (old == null || old.optString("refresh_token").isEmpty()) return "已清除本地会话；注册与 host ID 保留。";
        try {
            try (Response response = http.execute("POST", discover().getString("revocation_endpoint"),
                    form(fields("token", old.getString("refresh_token"), "token_type_hint", "refresh_token", "client_id", clientId)),
                    "application/x-www-form-urlencoded", null, "application/json")) {
                if (response.code() != 200) return "本地会话已清除，远端撤销未确认；请在 ChatGPT 设置中断开应用。";
                return "远端会话撤销成功；本地令牌已清除。";
            }
        } catch (Exception e) { return "本地会话已清除，远端撤销未确认；请在 ChatGPT 设置中断开应用。"; }
    }
    private JSONObject json(String method, String url, String body, String type, String bearer) throws Exception {
        try (Response response = http.execute(method, url, body, type, bearer, "application/json")) {
            requireSuccess(response);
            if (response.body() == null) throw new Failure("empty_response_body");
            return new JSONObject(read(response.body().byteStream(), 2 * 1024 * 1024));
        } catch (IOException e) { throw http.failure(e); }
    }
    private static void requireSuccess(Response response) throws Exception {
        int status = response.code();
        if (status >= 200 && status < 300) return;
        String code = "http_error";
        try {
            if (response.body() != null) {
                JSONObject obj = new JSONObject(read(response.body().byteStream(), 32768));
                Object err = obj.opt("error");
                if (err instanceof JSONObject) code = errorCode((JSONObject) err);
                else if (err instanceof String) code = errorCode(new JSONObject().put("code", err));
                else if (obj.has("detail")) code = "admission_rejected_detail";
            }
        } catch (Exception ignored) { /* Keep status without echoing credential-bearing bodies. */ }
        throw new Failure(code, status, response.header("x-request-id"));
    }
    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new Failure("body_too_large"); out.write(b, 0, n); }
        return out.toString("UTF-8");
    }
    static String boundedLineOrNull(InputStream in, int limit) throws Exception { return LoopbackReceiver.line(in, limit); }
    @Override public void close() {
        closed = true; session = null;
        http.close();
        LoopbackReceiver s = listener; if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
