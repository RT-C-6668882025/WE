package com.opc.baize.subscription;

/** Minimal boundary used by model adapters and contract tests; never exposes credentials. */
public interface SubscriptionConnection {
    boolean isAuthorized();
    String reply(String requestJson) throws Exception;
}
