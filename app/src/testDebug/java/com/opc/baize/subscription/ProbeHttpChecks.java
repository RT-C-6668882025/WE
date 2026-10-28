package com.opc.baize.subscription;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;

/** Synthetic HTTP peers exercise actual OkHttp transport, not a mock of its API. */
public final class ProbeHttpChecks {
    static int checks;
    static void yes(boolean ok) { checks++; if (!ok) throw new AssertionError("HTTP transport check " + checks); }
    static void consume(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        LoopbackReceiver.line(in, 4096);
        int length = 0; String line;
        while ((line = LoopbackReceiver.line(in, 4096)) != null && !line.isEmpty()) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        for (int i = 0; i < length; i++) if (in.read() < 0) throw new EOFException();
    }
    static void reply(Socket socket) throws IOException {
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    public static void main(String[] args) throws Exception { runAll(); System.out.println("PASS: " + checks + " actual HTTP transport checks"); }
    public static void runAll() throws Exception {
        checks = 0; ExecutorService workers = Executors.newCachedThreadPool();
        try {
            AtomicInteger connections = new AtomicInteger();
            ProbeDiagnostics d = new ProbeDiagnostics();
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); ProbeHttp http = new ProbeHttp(d)) {
                server.setSoTimeout(3000);
                Future<?> peer = workers.submit(() -> {
                    try (Socket first = server.accept()) {
                        first.setSoTimeout(3000); connections.incrementAndGet(); consume(first); reply(first);
                        // Keep first connection alive. The next request must still open another socket.
                        try (Socket second = server.accept()) {
                            second.setSoTimeout(3000); connections.incrementAndGet(); consume(second); reply(second);
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                Future<?> requests = workers.submit(() -> {
                    try {
                        for (int i = 0; i < 2; i++) try (Response response = http.execute("POST", "http://127.0.0.1:" + server.getLocalPort() + "/token", "synthetic=1", "application/x-www-form-urlencoded", null, "application/json")) {
                            yes(response.code() == 200 && response.body().string().equals("{}"));
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                requests.get(6, TimeUnit.SECONDS); peer.get(6, TimeUnit.SECONDS);
                yes(connections.get() == 2);
                yes(d.snapshot().contains("收到 HTTP 200"));
            }
            AtomicInteger received = new AtomicInteger();
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); ProbeHttp http = new ProbeHttp(new ProbeDiagnostics())) {
                server.setSoTimeout(1000);
                Future<?> peer = workers.submit(() -> {
                    try {
                        try (Socket socket = server.accept()) { socket.setSoTimeout(3000); received.incrementAndGet(); consume(socket); }
                        try (Socket duplicate = server.accept()) { received.incrementAndGet(); reply(duplicate); }
                        catch (SocketTimeoutException expected) { /* No replay is correct. */ }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Response ignored = http.execute("POST", "http://127.0.0.1:" + server.getLocalPort() + "/token", "code=synthetic", "application/x-www-form-urlencoded", null, "application/json")) {
                    throw new AssertionError("Must report interrupted one-use token exchange");
                } catch (SubscriptionProtocol.Failure e) { yes(e.code.startsWith("network_")); }
                peer.get(5, TimeUnit.SECONDS); yes(received.get() == 1);
            }
        } finally { workers.shutdownNow(); }
    }
}
