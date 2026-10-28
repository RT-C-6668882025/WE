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
    private SubscriptionGateway(Context context) { this.context = context; client = new SubscriptionProbeClient(context); }
    public boolean isAuthorized() { return authorized; }
    public String diagnostics() { return client.diagnostics(); }
    public synchronized String signIn(Browser browser) throws Exception {
        SubscriptionProbeClient owner = client;
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
            authorized = false;
            SubscriptionProbeClient old = client;
            client = new SubscriptionProbeClient(context);
            old.close();
        }
    }
}
