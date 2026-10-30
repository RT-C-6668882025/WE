package com.opc.baize.subscription;

import android.content.Context;
import java.util.List;
import org.json.JSONObject;

/** One application-scoped session; all grants and requests serialized. Credentials are restored from encrypted no-backup storage. */
public final class SubscriptionGateway implements SubscriptionConnection {
    public interface Browser { void open(String url); }
    private static SubscriptionGateway instance;
    public static synchronized SubscriptionGateway get(Context context) {
        if (instance == null) instance = new SubscriptionGateway(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private volatile SubscriptionProbeClient client;
    private volatile boolean authorized;
    private volatile AgentSubscriptionBridge agentBridge;
    public synchronized String startAgentBridge() throws Exception {
        if (!authorized || isLoginRunning()) throw new SubscriptionProtocol.Failure("sign_in_required");
        if (agentBridge != null) return agentBridgeInfo();
        // Freeze the account model IDs that the user has explicitly fetched.
        final java.util.Set<String> allowed = new java.util.HashSet<>();
        final org.json.JSONArray models = new org.json.JSONArray();
        for (ModelCatalog.Entry model : catalog()) {
            allowed.add(model.id);
            models.put(new JSONObject().put("id", model.id).put("object", "model"));
        }
        if (allowed.isEmpty()) throw new SubscriptionProtocol.Failure("model_required");
        AgentSubscriptionBridge bridge = new AgentSubscriptionBridge(AgentSubscriptionBridge.DEFAULT_PORT, new AgentSubscriptionBridge.Backend() {
            public JSONObject models() throws Exception { return new JSONObject().put("object", "list").put("data", models); }
            public AgentSubscriptionBridge.Upstream responses(JSONObject request) throws Exception {
                synchronized (SubscriptionGateway.this) {
                    if (!authorized || agentBridge == null) throw new SubscriptionProtocol.Failure("sign_in_required");
                    if (!allowed.contains(request.optString("model"))) throw new SubscriptionProtocol.Failure("model_not_found");
                    if (request.has("tools")) {
                        org.json.JSONArray tools = request.getJSONArray("tools");
                        for (int i = 0; i < tools.length(); i++)
                            if (!"function".equals(tools.getJSONObject(i).optString("type")))
                                throw new SubscriptionProtocol.Failure("unsupported_tool_type");
                    }
                    client.beginOperation();
                    try { return client.openAgentResponse(request); }
                    finally { synchronized (state) { authorized = client.hasDirectPermission(); } }
                }
            }
        });
        bridge.start(15000, true); agentBridge = bridge;
        return agentBridgeInfo();
    }
    public String agentBridgeInfo() {
        AgentSubscriptionBridge bridge = agentBridge;
        return bridge == null ? "Agent 连接：已关闭" : "Agent 连接：本机 127.0.0.1:" + AgentSubscriptionBridge.DEFAULT_PORT
            + "\n临时连接密钥（仅当前进程有效）：\n" + bridge.password()
            + "\n电脑需通过 adb forward 连接。订阅令牌不会离开本机。";
    }
    public void stopAgentBridge() {
        AgentSubscriptionBridge old = agentBridge; agentBridge = null;
        if (old != null) old.stop();
    }
    private volatile List<ModelCatalog.Entry> catalog = java.util.Collections.emptyList();
    private final java.util.concurrent.ConcurrentHashMap<String, String> checks = new java.util.concurrent.ConcurrentHashMap<>();
    public List<ModelCatalog.Entry> cachedCatalog() { return catalog; }
    public String modelCheck(String model, boolean image) { return checks.getOrDefault(model + (image ? ":image" : ":text"), "未验证"); }
    public void recordCheck(String model, boolean image, String result) { checks.put(model + (image ? ":image" : ":text"), result); }
    public synchronized List<ModelCatalog.Entry> catalog() throws Exception {
        client.beginOperation();
        try { catalog = client.catalog(); return catalog; }
        finally { synchronized (state) { authorized = client.hasDirectPermission(); } }
    }
    public synchronized String refreshSession() throws Exception {
        client.beginOperation();
        try { return client.refresh(); }
        finally { synchronized (state) { authorized = client.hasDirectPermission(); } }
    }
    private final Object state = new Object();
    private final LoginOperation login = new LoginOperation();
    private final java.util.concurrent.ExecutorService authorization = java.util.concurrent.Executors.newSingleThreadExecutor();
    public boolean isLoginRunning() { return login.isRunning(); }
    public String loginResult() { return login.result(); }
    public long loginVersion() { return login.version(); }
    public String takeBrowserUrl() { return login.takeBrowserUrl(); }
    public void startSignIn() {
        final long ticket = login.start();
        if (ticket < 0) return;
        final SubscriptionProbeClient owner = client;
        authorization.execute(() -> {
            String result;
            try {
                if (!login.isCurrent(ticket)) return;
                result = signIn(owner, url -> login.browser(ticket, url));
            } catch (SubscriptionProtocol.Failure e) {
                result = "授权未完成：" + e.code + (e.status == 0 ? "" : "（HTTP " + e.status + "）");
            } catch (Exception e) { result = "授权未完成：" + e.getClass().getSimpleName(); }
            login.finish(ticket, result);
        });
    }
    private SubscriptionGateway(Context context) {
        this.context = context; client = new SubscriptionProbeClient(context);
        authorized = client.hasDirectPermission();
        if (authorized) login.record("已恢复本机保存的订阅登录。");
        else if (client.hasPendingIdentity()) startRestore();
        else if (!client.restoreIssue().isEmpty()) login.record("保存的登录需要处理：" + client.restoreIssue());
    }
    public synchronized String restoreSavedSession() throws Exception {
        client.beginOperation();
        try { return client.restoreSession(); }
        finally { synchronized (state) { authorized = client.hasDirectPermission(); } }
    }
    private void startRestore() {
        final long ticket = login.start(); final SubscriptionProbeClient owner = client;
        authorization.execute(() -> {
            String result;
            try {
                synchronized (this) {
                    if (!login.isCurrent(ticket) || owner != client) return;
                    result = restoreSavedSession();
                }
            } catch (SubscriptionProtocol.Failure e) { result = "恢复未完成：" + e.code + "。可点击恢复保存的登录重试身份校验。"; }
            catch (Exception e) { result = "恢复未完成，请重试或重新登录。"; }
            login.finish(ticket, result);
        });
    }
    public boolean isAuthorized() { return authorized; }
    public String diagnostics() { return client.diagnostics(); }
    public synchronized String signIn(Browser browser) throws Exception { return signIn(client, browser); }
    private synchronized String signIn(SubscriptionProbeClient owner, Browser browser) throws Exception {
        stopAgentBridge();
        if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
        authorized = false; catalog = java.util.Collections.emptyList(); checks.clear(); owner.beginOperation();
        String result = owner.signIn(browser::open);
        if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
        synchronized (state) {
            if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
            authorized = owner.hasDirectPermission();
        }
        return result;
    }
    public synchronized List<String> models() throws Exception {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (ModelCatalog.Entry model : catalog()) ids.add(model.id);
        return ids;
    }
    public synchronized String reply(String requestJson) throws Exception {
        client.beginOperation();
        if (!authorized) throw new SubscriptionProtocol.Failure("sign_in_required");
        SubscriptionProbeClient owner = client;
        try { return owner.reply(new JSONObject(requestJson)); }
        finally {
            synchronized (state) { if (owner == client) authorized = owner.hasDirectPermission(); }
        }
    }
    public synchronized String signOut() {
        stopAgentBridge();
        authorized = false; catalog = java.util.Collections.emptyList(); checks.clear();
        String result = client.signOut(); login.record(result); return result;
    }
    /** Cancels without waiting for an OAuth callback/network request to finish. */
    public void cancel() {
        stopAgentBridge();
        synchronized (state) {
            login.cancel();
            authorized = false; catalog = java.util.Collections.emptyList(); checks.clear();
            SubscriptionProbeClient old = client;
            old.close();
            boolean cleared = true;
            try { old.clearSavedSession(); }
            catch (Exception e) { cleared = false; login.record("取消后凭据清理未确认，请清除应用数据。"); }
            client = new SubscriptionProbeClient(context);
            if (!cleared) client.close();
        }
    }
}
