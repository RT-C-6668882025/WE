package com.opc.baize.subscription;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Real loopback sockets, synthetic callback values; never connects to OpenAI. */
public final class LoopbackChecks {
    static int checks;
    static void yes(boolean value) { checks++; if (!value) throw new AssertionError("Loopback check " + checks); }
    static String request(String url, String method, String host) throws Exception {
        URI uri = new URI(url);
        try (Socket socket = new Socket("127.0.0.1", uri.getPort())) {
            socket.setSoTimeout(3000);
            String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String authority = host == null ? "127.0.0.1:" + uri.getPort() : host;
            socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: " + authority + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    public static void main(String[] args) throws Exception { runAll(); System.out.println("PASS: " + checks + " live-loopback checks (synthetic credentials)"); }
    public static void runAll() throws Exception {
        checks = 0;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProbeDiagnostics d = new ProbeDiagnostics(); d.mark(ProbeDiagnostics.Stage.WAIT_CALLBACK);
            try (LoopbackReceiver receiver = new LoopbackReceiver()) {
                Future<Map<String, String>> pending = executor.submit(() -> receiver.await("expected", "", d));
                yes(request(receiver.healthUrl(), "GET", null).contains("MOLING_CALLBACK_OK"));
                yes(d.snapshot().contains("浏览器本机连通测试：已到达") && !pending.isDone());
                yes(request(receiver.callbackUrl() + "?state=wrong&code=test&client_id=test-client", "GET", null).startsWith("HTTP/1.1 400"));
                yes(!pending.isDone());
                yes(request(receiver.callbackUrl() + "?state=expected&code=test&client_id=test-client", "GET", "evil.test").startsWith("HTTP/1.1 400"));
                yes(!pending.isDone());
                yes(request(receiver.callbackUrl() + "?state=expected&code=test&client_id=test-client", "GET", null).startsWith("HTTP/1.1 200"));
                yes(pending.get(3, TimeUnit.SECONDS).get("code").equals("test"));
                yes(d.snapshot().contains("已收到并校验授权回调"));
            }
            try (LoopbackReceiver receiver = new LoopbackReceiver()) {
                Future<Map<String, String>> pending = executor.submit(() -> receiver.await("s", "", new ProbeDiagnostics()));
                receiver.close();
                try { pending.get(3, TimeUnit.SECONDS); throw new AssertionError("Expected cancellation"); }
                catch (ExecutionException e) { yes(e.getCause() instanceof Failure && ((Failure)e.getCause()).code.equals("cancelled")); }
            }
        } finally { executor.shutdownNow(); }
    }
}
