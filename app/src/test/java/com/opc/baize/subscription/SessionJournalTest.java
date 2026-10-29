package com.opc.baize.subscription;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionJournalTest {
    static final class Store implements CredentialStore {
        String value;
        public String read() { return value; }
        public void write(String value) { this.value = value; }
        public void clear() { value = null; }
    }
    JSONObject tokens() throws Exception {
        return new JSONObject().put("access_token", "synthetic-access").put("refresh_token", "synthetic-refresh")
            .put("expires_in", 3600).put("token_type", "Bearer").put("scope", SubscriptionProtocol.SCOPES);
    }
    @Test public void verifiedStateAndAbsoluteExpirySurviveNewJournal() throws Exception {
        Store store = new Store(); new SessionJournal(store).verified("client", "subject", tokens(), 1000);
        JSONObject restored = new SessionJournal(store).read("client", "subject");
        assertEquals("verified", restored.getString("state")); assertEquals(1000, restored.getLong("expires_at"));
        assertEquals("synthetic-refresh", restored.getJSONObject("tokens").getString("refresh_token"));
    }
    @Test public void checkpointRecoversRotatedResponseWithoutOldGrant() throws Exception {
        Store store = new Store(); SessionJournal first = new SessionJournal(store);
        first.verified("client", "subject", tokens(), 1000); first.refreshInFlight("client", "subject");
        JSONObject flight = new SessionJournal(store).read("client", "subject");
        assertEquals("refresh_in_flight", flight.getString("state")); assertFalse(flight.has("tokens"));
        JSONObject next = tokens().put("refresh_token", "new-rotated-refresh");
        first.pending("client", "subject", next, null, true, 1000);
        JSONObject pending = new SessionJournal(store).read("client", "subject");
        assertEquals("pending", pending.getString("state")); assertEquals(1000, pending.getLong("received_at"));
        assertEquals("new-rotated-refresh", pending.getJSONObject("tokens").getString("refresh_token"));
        first.verified("client", "subject", next, 3601000);
        assertEquals("verified", new SessionJournal(store).read("client", "subject").getString("state"));
    }
    @Test(expected = SubscriptionProtocol.Failure.class) public void rejectsDifferentRegistration() throws Exception {
        Store store = new Store(); new SessionJournal(store).verified("client", "subject", tokens(), 1000);
        new SessionJournal(store).read("another-client", "subject");
    }
    @Test public void signOutClearsVerifiedAndPendingCheckpoints() throws Exception {
        Store store = new Store(); SessionJournal journal = new SessionJournal(store);
        journal.pending("client", "subject", tokens(), "nonce", false, 1000); journal.clear();
        assertNull(new SessionJournal(store).read("client", "subject"));
    }
}
