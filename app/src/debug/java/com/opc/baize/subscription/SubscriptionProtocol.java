package com.opc.baize.subscription;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independently implemented from the public SIWC protocol; no DevKit source included. */
public final class SubscriptionProtocol {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final String DIRECT_SCOPE = "chatgpt.tokens.use.direct";
    public static final String SCOPES = "openid profile email offline_access resource.invoke " + DIRECT_SCOPE;
    private SubscriptionProtocol() {}

    public static final class Failure extends Exception {
        public final String code;
        public final int status;
        public final String requestId;
        public Failure(String code) { this(code, 0, ""); }
        public Failure(String code, int status, String requestId) {
            super(code); this.code = code; this.status = status;
            this.requestId = requestId == null ? "" : requestId.replaceAll("[^a-zA-Z0-9_\\-]", "").substring(0, Math.min(100, requestId.replaceAll("[^a-zA-Z0-9_\\-]", "").length()));
        }
    }

    public static String random() {
        byte[] b = new byte[32]; new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    public static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
    public static String form(Map<String, String> values) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return b.toString();
    }
    public static Map<String, String> fields(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }
    public static String authorize(String endpoint, String redirect, String host, String client,
                                   String state, String nonce, String verifier) throws Exception {
        Map<String, String> p = fields("client_id", client.isEmpty() ? "dynamic_agent_client" : client,
            "response_type", "code", "redirect_uri", redirect, "scope", SCOPES,
            "resource", RESOURCE, "state", state, "nonce", nonce,
            "code_challenge_method", "S256", "code_challenge", challenge(verifier), "ext_agent_host_id", host);
        if (client.isEmpty()) p.put("agent_name_hint", "Moling AI Paper");
        return endpoint + "?" + form(p);
    }
    public static Map<String, String> callback(String target, String state, String savedClient) throws Exception {
        if (target.length() > 16384) throw new Failure("callback_too_large");
        URI u = new URI(target);
        if (u.isAbsolute() || u.getRawAuthority() != null || !"/auth/callback".equals(u.getRawPath()) || u.getRawFragment() != null)
            throw new Failure("invalid_callback_path");
        Map<String, String> q = new LinkedHashMap<>();
        if (u.getRawQuery() != null) for (String part : u.getRawQuery().split("&")) {
            String[] kv = part.split("=", 2);
            String key = URLDecoder.decode(kv[0], "UTF-8");
            String value = kv.length == 2 ? URLDecoder.decode(kv[1], "UTF-8") : "";
            if (q.put(key, value) != null) throw new Failure("duplicate_callback_parameter");
        }
        if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), q.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8)))
            throw new Failure("state_mismatch");
        if (q.containsKey("error")) throw new Failure("access_denied".equals(q.get("error")) ? "access_denied" : "oauth_callback_error");
        String id = q.getOrDefault("client_id", savedClient);
        if (id.isEmpty() || "dynamic_agent_client".equals(id)) throw new Failure("missing_issued_client_id");
        if (!savedClient.isEmpty() && !id.equals(savedClient)) throw new Failure("client_id_mismatch");
        if (q.getOrDefault("code", "").isEmpty()) throw new Failure("missing_code");
        q.put("client_id", id); return q;
    }
    public static boolean hasScope(String scope, String wanted) {
        for (String s : scope.split("\\s+")) if (s.equals(wanted)) return true;
        return false;
    }
    public static JSONObject request(String model, String imageUrl) throws Exception {
        if (model.isEmpty()) throw new Failure("model_required");
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text")
            .put("text", imageUrl == null ? "Reply with exactly MOLING_OK" : "Read the arithmetic in this image and reply with only its answer."));
        if (imageUrl != null) {
            if (!imageUrl.startsWith("data:image/png;base64,") || imageUrl.length() > 2 * 1024 * 1024)
                throw new Failure("invalid_probe_image");
            content.put(new JSONObject().put("type", "input_image").put("image_url", imageUrl));
        }
        return new JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
    }

    /** SSE framing is separate from JSON events; success requires a terminal completed event. */
    public static final class Stream {
        private final StringBuilder data = new StringBuilder();
        public final StringBuilder text = new StringBuilder();
        public boolean completed;
        public void line(String line) throws Exception {
            if (completed) return;
            if (line.isEmpty()) { dispatch(); return; }
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).replaceFirst("^ ", ""));
                if (data.length() > 2 * 1024 * 1024) throw new Failure("event_too_large");
            }
        }
        private void dispatch() throws Exception {
            if (data.length() == 0) return;
            String raw = data.toString(); data.setLength(0);
            if ("[DONE]".equals(raw)) return;
            JSONObject e = new JSONObject(raw);
            String type = e.optString("type");
            if (type.equals("response.output_text.delta")) {
                text.append(e.getString("delta"));
                if (text.length() > 1024 * 1024) throw new Failure("output_too_large");
            } else if (type.equals("response.completed")) {
                JSONObject r = e.optJSONObject("response");
                if (r == null || !"completed".equals(r.optString("status"))) throw new Failure("invalid_completed_event");
                completed = true;
            } else if (type.equals("response.failed") || type.equals("error")) {
                JSONObject r = e.optJSONObject("response");
                JSONObject err = r == null ? e.optJSONObject("error") : r.optJSONObject("error");
                throw new Failure(errorCode(err == null && type.equals("error") ? e : err));
            } else if (type.equals("response.incomplete")) throw new Failure("response_incomplete");
        }
        public String finish() throws Exception {
            dispatch();
            if (!completed) throw new Failure("stream_interrupted");
            return text.toString();
        }
    }
    public static String errorCode(JSONObject error) {
        String code = error == null ? "response_failed" : error.optString("code", "response_failed");
        // Do not display arbitrary upstream bodies that may contain credentials or submitted content.
        switch (code) {
            case "subscription_sharing_user_not_eligible":
            case "subscription_sharing_usage_limit_exceeded":
            case "subscription_sharing_usage_unavailable":
            case "invalid_grant": case "invalid_scope": case "invalid_request_error":
            case "model_not_found": case "invalid_api_key": return code;
            default: return "response_failed";
        }
    }
}
