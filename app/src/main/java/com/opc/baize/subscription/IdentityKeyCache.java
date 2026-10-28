package com.opc.baize.subscription;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Public keys only, scoped to one client and pinned discovery endpoint. No credentials. */
final class IdentityKeyCache {
    interface Loader { JSONObject load() throws Exception; }
    private final Loader loader;
    private final LongSupplier clock;
    private JSONObject keys;
    private long loadedAt;
    IdentityKeyCache(Loader loader) { this(loader, System::nanoTime); }
    IdentityKeyCache(Loader loader, LongSupplier clock) { this.loader = loader; this.clock = clock; }
    void prefetch() throws Exception { current(); }
    private JSONObject current() throws Exception {
        if (keys == null || clock.getAsLong() - loadedAt >= TimeUnit.MINUTES.toNanos(10)) reload();
        return keys;
    }
    private void reload() throws Exception {
        JSONObject next = loader.load();
        if (next.optJSONArray("keys") == null || next.getJSONArray("keys").length() == 0)
            throw new Failure("identity_keys_unavailable");
        keys = next; loadedAt = clock.getAsLong();
    }
    JSONObject verify(String jwt, String client, String nonce, String subject, long now) throws Exception {
        try { return IdentityVerifier.verify(jwt, current(), client, nonce, subject, now); }
        catch (Failure e) {
            // A key rotation is recoverable; an invalid signature or claims never are.
            if (!"signing_key_missing".equals(e.code)) throw e;
            reload();
            return IdentityVerifier.verify(jwt, keys, client, nonce, subject, now);
        }
    }
    void clear() { keys = null; }
}
