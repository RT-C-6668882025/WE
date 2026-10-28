package com.opc.baize.subscription;

import org.junit.Test;

public class SubscriptionProtocolTest {
    @Test public void rejectsInvalidAuthorizationAndIncompleteStreams() throws Exception {
        ProtocolChecks.runAll();
    }
}
