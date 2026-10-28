package com.opc.baize.subscription;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

final class IdentityVerifier {
    static JSONObject verify(String jwt, JSONObject jwks, String clientId, String nonce, String subject, long now) throws Exception {
        if (jwt.length() > 32768) throw new Failure("invalid_id_token");
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new Failure("invalid_id_token");
        JSONObject head = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(head.optString("alg")) || head.has("crit")) throw new Failure("invalid_id_token_algorithm");
        String kid = head.getString("kid");
        JSONArray keys = jwks.getJSONArray("keys");
        JSONObject chosen = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject k = keys.getJSONObject(i);
            if (kid.equals(k.optString("kid")) && "RSA".equals(k.optString("kty"))
                && "sig".equals(k.optString("use", "sig")) && "RS256".equals(k.optString("alg", "RS256"))) {
                if (chosen != null) throw new Failure("ambiguous_signing_key");
                chosen = k;
            }
        }
        if (chosen == null) throw new Failure("signing_key_missing");
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(chosen.getString("n")));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(chosen.getString("e")));
        if (n.bitLength() < 2048 || n.bitLength() > 8192) throw new Failure("invalid_signing_key");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new Failure("invalid_id_token_signature");
        JSONObject claims = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        if (!ISSUER.equals(claims.optString("iss"))) throw new Failure("invalid_id_token_issuer");
        Object aud = claims.opt("aud"); boolean matched = clientId.equals(aud); int audiences = 1;
        if (aud instanceof JSONArray) {
            JSONArray a = (JSONArray) aud; audiences = a.length();
            for (int i = 0; i < a.length(); i++) if (clientId.equals(a.get(i))) matched = true;
        }
        if (!matched || (audiences > 1 && !clientId.equals(claims.optString("azp")))
            || (claims.has("azp") && !clientId.equals(claims.getString("azp")))) throw new Failure("invalid_id_token_audience");
        if (!(claims.opt("exp") instanceof Number) || !(claims.opt("iat") instanceof Number)
            || claims.getLong("exp") <= now - 5 || claims.getLong("iat") > now + 5
            || (claims.has("nbf") && claims.getLong("nbf") > now + 5)) throw new Failure("expired_or_invalid_id_token");
        if (nonce != null && !nonce.equals(claims.optString("nonce"))) throw new Failure("nonce_mismatch");
        if (claims.optString("sub").isEmpty()) throw new Failure("invalid_id_token_subject");
        if (subject != null && !subject.equals(claims.getString("sub"))) throw new Failure("account_mismatch");
        return claims;
    }
}
