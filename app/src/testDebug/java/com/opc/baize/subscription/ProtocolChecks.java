package com.opc.baize.subscription;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.util.*;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Runs under both plain JDK and the existing Android JUnit task. No live service or credentials. */
public final class ProtocolChecks {
    interface Check { void run() throws Exception; }
    static int checks;
    static void yes(boolean value) { checks++; if (!value) throw new AssertionError("Protocol check failed: " + checks); }
    static void fails(String code, Check body) throws Exception {
        try { body.run(); } catch (Failure e) { yes(code.equals(e.code)); return; }
        throw new AssertionError("Expected " + code);
    }
    static String enc(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String unsigned(BigInteger n) { byte[] b=n.toByteArray(); return enc(b[0] == 0 ? Arrays.copyOfRange(b,1,b.length) : b); }
    static String jwt(JSONObject header, JSONObject claims, PrivateKey key) throws Exception {
        String data=enc(header.toString().getBytes(StandardCharsets.UTF_8))+"."+enc(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature s=Signature.getInstance("SHA256withRSA"); s.initSign(key); s.update(data.getBytes(StandardCharsets.US_ASCII));
        return data+"."+enc(s.sign());
    }
    static void event(Stream s, String json) throws Exception { s.line("data: "+json); s.line(""); }
    public static void main(String[] args) throws Exception { runAll(); System.out.println("PASS: " + checks + " protocol checks (offline; no Android/OAuth validation)"); }
    public static void runAll() throws Exception {
        checks=0;
        yes("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")));
        String uri=authorize(ISSUER+"/api/accounts/authorize","http://127.0.0.1:54321/auth/callback","urn:uuid:test","","state","nonce","verifier");
        yes(uri.contains("client_id=dynamic_agent_client") && uri.contains("agent_name_hint=Moling+AI+Paper") && uri.contains("ext_agent_host_id="));
        String returning=authorize(ISSUER+"/api/accounts/authorize","http://127.0.0.1:54322/auth/callback","urn:uuid:test","oaiapp_test","state","nonce","verifier");
        yes(!returning.contains("agent_name_hint") && returning.contains("client_id=oaiapp_test"));
        yes(random().length()==43 && !random().equals(random()));
        yes("oaiapp_test".equals(callback("/auth/callback?state=s&code=c&client_id=oaiapp_test","s","").get("client_id")));
        yes("oaiapp_test".equals(callback("/auth/callback?state=s&code=c","s","oaiapp_test").get("client_id")));
        fails("state_mismatch",()->callback("/auth/callback?state=wrong&code=c&client_id=x","s",""));
        fails("duplicate_callback_parameter",()->callback("/auth/callback?state=s&state=s","s",""));
        fails("invalid_callback_path",()->callback("http://evil.test/auth/callback?state=s","s",""));
        fails("invalid_callback_path",()->callback("//evil.test/auth/callback?state=s","s",""));
        fails("missing_issued_client_id",()->callback("/auth/callback?state=s&code=c&client_id=dynamic_agent_client","s",""));
        fails("client_id_mismatch",()->callback("/auth/callback?state=s&code=c&client_id=b","s","a"));
        fails("access_denied",()->callback("/auth/callback?state=s&error=access_denied","s",""));
        fails("state_mismatch",()->callback("/auth/callback?state=x&error=access_denied","s",""));
        yes(hasScope("openid "+DIRECT_SCOPE,DIRECT_SCOPE) && !hasScope(DIRECT_SCOPE+".evil",DIRECT_SCOPE));
        JSONObject request=request("account-model",null);
        yes(!request.getBoolean("store") && request.getBoolean("stream") && request.get("input") instanceof JSONArray);
        yes(!request.has("max_output_tokens") && !request.has("previous_response_id") && !request.has("temperature"));
        yes(request("account-model","data:image/png;base64,AAAA").getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type").equals("input_image"));
        fails("invalid_probe_image",()->request("model","https://example.test/image.png"));
        Stream stream=new Stream();
        stream.line(": keepalive"); stream.line("");
        event(stream,"{\"type\":\"response.output_text.delta\",\"delta\":\"MOLING_OK\"}");
        event(stream,"{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        yes("MOLING_OK".equals(stream.finish()));
        Stream cut=new Stream(); event(cut,"{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}");
        fails("stream_interrupted",cut::finish);
        Stream done=new Stream(); event(done,"[DONE]"); fails("stream_interrupted",done::finish);
        Stream failed=new Stream();
        fails("subscription_sharing_usage_limit_exceeded",()->event(failed,"{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}"));
        fails("response_incomplete",()->event(new Stream(),"{\"type\":\"response.incomplete\"}"));
        fails("invalid_completed_event",()->event(new Stream(),"{\"type\":\"response.completed\",\"response\":{\"status\":\"failed\"}}"));
        Stream multi=new Stream(); multi.line("data: {\"type\":\"response.output_text.delta\","); multi.line("data: \"delta\":\"你好\"}"); multi.line("");
        event(multi,"{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"); yes("你好".equals(multi.finish()));
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); KeyPair pair=gen.generateKeyPair();
        RSAPublicKey publicKey=(RSAPublicKey)pair.getPublic();
        JSONObject jwks=new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","test").put("kty","RSA")
            .put("n",unsigned(publicKey.getModulus())).put("e",unsigned(publicKey.getPublicExponent()))));
        JSONObject head=new JSONObject().put("kid","test").put("alg","RS256");
        JSONObject claims=new JSONObject().put("iss",ISSUER).put("aud","client").put("sub","subject").put("nonce","nonce").put("iat",1000).put("exp",2000);
        String valid=jwt(head,claims,pair.getPrivate());
        yes("subject".equals(IdentityVerifier.verify(valid,jwks,"client","nonce",null,1500).getString("sub")));
        fails("nonce_mismatch",()->IdentityVerifier.verify(valid,jwks,"client","wrong",null,1500));
        fails("invalid_id_token_audience",()->IdentityVerifier.verify(valid,jwks,"other","nonce",null,1500));
        fails("account_mismatch",()->IdentityVerifier.verify(valid,jwks,"client","nonce","other",1500));
        fails("expired_or_invalid_id_token",()->IdentityVerifier.verify(valid,jwks,"client","nonce",null,2100));
        claims.put("aud",new JSONArray().put("client").put("other"));
        String multiAud=jwt(head,claims,pair.getPrivate());
        fails("invalid_id_token_audience",()->IdentityVerifier.verify(multiAud,jwks,"client","nonce",null,1500));
        claims.put("azp","client");
        yes(IdentityVerifier.verify(jwt(head,claims,pair.getPrivate()),jwks,"client","nonce",null,1500)!=null);
        head.put("alg","none"); String wrongAlg=jwt(head,claims,pair.getPrivate());
        fails("invalid_id_token_algorithm",()->IdentityVerifier.verify(wrongAlg,jwks,"client","nonce",null,1500));
        String[] p=valid.split("\\."); byte[] sig=Base64.getUrlDecoder().decode(p[2]); sig[0]^=1;
        fails("invalid_id_token_signature",()->IdentityVerifier.verify(p[0]+"."+p[1]+"."+enc(sig),jwks,"client","nonce",null,1500));
        yes("response_failed".equals(errorCode(new JSONObject().put("code","secret=value"))));
    }
}
