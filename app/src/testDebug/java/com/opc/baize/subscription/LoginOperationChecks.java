package com.opc.baize.subscription;

public final class LoginOperationChecks {
    static int checks;
    static void yes(boolean value) { checks++; if (!value) throw new AssertionError("Login operation check " + checks); }
    public static void main(String[] args) { runAll(); System.out.println("PASS: " + checks + " application-owned login checks"); }
    static void runAll() {
        checks = 0; LoginOperation state = new LoginOperation();
        long first = state.start(); yes(state.isRunning()); yes(state.start() == -1);
        state.browser(first, "synthetic-browser-url");
        // A recreated Activity accesses the same app-owned state; no screen cancellation call.
        LoginOperation recreatedScreen = state;
        yes(recreatedScreen.takeBrowserUrl().equals("synthetic-browser-url"));
        yes(recreatedScreen.takeBrowserUrl() == null); yes(recreatedScreen.isCurrent(first));
        state.finish(first, "authorized"); yes(!state.isRunning()); yes(state.result().equals("authorized"));
        // Model discovery is deliberately a different operation and cannot rewrite login status.
        yes(recreatedScreen.result().equals("authorized"));
        long cancelled = state.start(); state.cancel(); long next = state.start();
        state.browser(cancelled, "stale-url"); state.finish(cancelled, "stale-success");
        yes(state.isCurrent(next)); yes(state.takeBrowserUrl() == null); yes(state.result().isEmpty());
        state.finish(next, "new-success"); yes(state.result().equals("new-success"));
        long logout = state.start(); state.record("revoked"); state.finish(logout, "stale-authorized");
        yes(!state.isRunning()); yes(state.result().equals("revoked"));
    }
}
