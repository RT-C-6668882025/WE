package com.opc.baize.subscription;

import org.json.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static com.opc.baize.subscription.ProtocolChecks.*;

public final class IdentityKeyCacheChecks {
    static int count;
    static void check(boolean value) { count++; if (!value) throw new AssertionError("Key cache check " + count); }
    static JSONObject keys(KeyPair pair, String kid) throws Exception {
        RSAPublicKey k = (RSAPublicKey) pair.getPublic();
        return new JSONObject().put("keys", new JSONArray().put(new JSONObject().put("kid", kid).put("kty", "RSA")
            .put("n", unsigned(k.getModulus())).put("e", unsigned(k.getPublicExponent()))));
    }
    public static void main(String[] args) throws Exception { runAll(); System.out.println("PASS: " + count + " identity key cache checks"); }
    public static void runAll() throws Exception {
        count = 0;
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair a = g.generateKeyPair(), b = g.generateKeyPair();
        JSONObject claims = new JSONObject().put("iss", SubscriptionProtocol.ISSUER).put("aud", "client")
            .put("sub", "subject").put("nonce", "nonce").put("iat", 1400).put("exp", 2000);
        JSONObject head = new JSONObject().put("alg", "RS256").put("kid", "a");
        String token = jwt(head, claims, a.getPrivate());
        AtomicInteger loads = new AtomicInteger(); AtomicLong clock = new AtomicLong();
        AtomicReference<JSONObject> remote = new AtomicReference<>(keys(a, "a"));
        IdentityKeyCache cache = new IdentityKeyCache(() -> { loads.incrementAndGet(); return remote.get(); }, clock::get);
        cache.prefetch(); check(loads.get() == 1);
        check(cache.verify(token, "client", "nonce", null, 1500).getString("sub").equals("subject"));
        check(loads.get() == 1); // No post-consent network request for a cached key.
        clock.set(TimeUnit.MINUTES.toNanos(11)); cache.prefetch(); check(loads.get() == 2);
        remote.set(keys(b, "b"));
        String rotated = jwt(new JSONObject().put("alg", "RS256").put("kid", "b"), claims, b.getPrivate());
        check(cache.verify(rotated, "client", "nonce", null, 1500) != null); check(loads.get() == 3);
        String invalid = jwt(new JSONObject().put("alg", "RS256").put("kid", "b"), claims, a.getPrivate());
        try { cache.verify(invalid, "client", "nonce", null, 1500); throw new AssertionError("Invalid signature accepted"); }
        catch (SubscriptionProtocol.Failure e) { check(e.code.equals("invalid_id_token_signature")); }
        check(loads.get() == 3); // Signature failure never triggers a key refresh/bypass.
        try { cache.verify(rotated, "other", "nonce", null, 1500); throw new AssertionError("Wrong audience accepted"); }
        catch (SubscriptionProtocol.Failure e) { check(e.code.equals("invalid_id_token_audience")); }
        check(loads.get() == 3);
        cache.clear(); remote.set(new JSONObject().put("keys", new JSONArray()));
        try { cache.prefetch(); throw new AssertionError("Empty keys accepted"); }
        catch (SubscriptionProtocol.Failure e) { check(e.code.equals("identity_keys_unavailable")); }
        remote.set(keys(b, "b")); cache.prefetch(); check(loads.get() == 5); // Failure not cached.
    }
}
