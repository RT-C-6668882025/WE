package com.opc.baize.subscription;

import android.content.Context;
import java.util.List;
import org.json.JSONObject;

/** One application-scoped session; all grants and requests serialized. Credentials stay in RAM. */
public final class SubscriptionGateway {
    public interface Browser { void open(String url); }
    private static SubscriptionGateway instance;
    public static synchronized SubscriptionGateway get(Context context) {
        if (instance == null) instance = new SubscriptionGateway(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private volatile SubscriptionProbeClient client;
    private volatile boolean authorized;
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
    private SubscriptionGateway(Context context) { this.context = context; client = new SubscriptionProbeClient(context); }
    public boolean isAuthorized() { return authorized; }
    public String diagnostics() { return client.diagnostics(); }
    public synchronized String signIn(Browser browser) throws Exception { return signIn(client, browser); }
    private synchronized String signIn(SubscriptionProbeClient owner, Browser browser) throws Exception {
        if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
        authorized = false; owner.beginOperation();
        String result = owner.signIn(browser::open);
        if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
        synchronized (state) {
            if (owner != client) throw new SubscriptionProtocol.Failure("cancelled");
            authorized = owner.hasDirectPermission();
        }
        return result;
    }
    public synchronized List<String> models() throws Exception {
        client.beginOperation(); return client.models();
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
        authorized = false; return client.signOut();
    }
    /** Cancels without waiting for an OAuth callback/network request to finish. */
    public void cancel() {
        synchronized (state) {
            login.cancel();
            authorized = false;
            SubscriptionProbeClient old = client;
            client = new SubscriptionProbeClient(context);
            old.close();
        }
    }
}
