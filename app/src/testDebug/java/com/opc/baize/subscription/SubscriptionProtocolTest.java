package com.opc.baize.subscription;

import org.junit.Test;

public class SubscriptionProtocolTest {
    @Test public void cachesKeysWithoutSkippingIdentityVerification() throws Exception { IdentityKeyCacheChecks.runAll(); }
    @Test public void classifiesNetworkFailuresSafely() { NetworkChecks.runAll(); }
    @Test public void usesFreshConnectionsWithoutReplayingCodes() throws Exception { ProbeHttpChecks.runAll(); }
    @Test public void verifiesLoopbackHealthAndCallback() throws Exception {
        LoopbackChecks.runAll();
    }
    @Test public void rejectsInvalidAuthorizationAndIncompleteStreams() throws Exception {
        ProtocolChecks.runAll();
    }
}
