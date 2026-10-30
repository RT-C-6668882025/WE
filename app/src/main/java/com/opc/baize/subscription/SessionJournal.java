package com.opc.baize.subscription;

import org.json.JSONObject;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Durable rotation journal. A spent/ambiguous refresh grant can never become a verified checkpoint. */
final class SessionJournal {
    final CredentialStore store;
    SessionJournal(CredentialStore store) { this.store = store; }
    JSONObject read(String client, String subject) throws Exception {
        String encoded = store.read(); if (encoded == null) return null;
        JSONObject record = new JSONObject(encoded);
        if (record.getInt("schema") != 1 || !client.equals(record.getString("client_id"))) throw new Failure("saved_session_binding_mismatch");
        String identity = record.optString("subject", "");
        if (subject != null && !identity.isEmpty() && !subject.equals(identity)) throw new Failure("saved_session_binding_mismatch");
        String state = record.getString("state");
        if (!state.equals("verified") && !state.equals("pending") && !state.equals("refresh_in_flight")) throw new Failure("saved_session_invalid");
        if (state.equals("verified")) {
            if (identity.isEmpty() || record.getLong("expires_at") <= 0) throw new Failure("saved_session_invalid");
            validateTokenFields(record.getJSONObject("tokens"), null, false);
        }
        return record;
    }
    private JSONObject base(String client, String subject, String state) throws Exception {
        return new JSONObject().put("schema", 1).put("client_id", client).put("subject", subject == null ? "" : subject).put("state", state);
    }
    void verified(String client, String subject, JSONObject tokens, long expiresAt) throws Exception {
        store.write(base(client, subject, "verified").put("tokens", tokens).put("expires_at", expiresAt).toString());
    }
    void pending(String client, String subject, JSONObject tokens, String nonce, boolean refreshing, long receivedAt) throws Exception {
        store.write(base(client, subject, "pending").put("tokens", tokens).put("nonce", nonce == null ? JSONObject.NULL : nonce)
            .put("refreshing", refreshing).put("received_at", receivedAt).toString());
    }
    void refreshInFlight(String client, String subject) throws Exception {
        // Do not retain the old refresh token in a recoverable state after the request may be sent.
        store.write(base(client, subject, "refresh_in_flight").toString());
    }
    void clear() throws Exception { store.clear(); }
}
