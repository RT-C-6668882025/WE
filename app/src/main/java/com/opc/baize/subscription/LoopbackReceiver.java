package com.opc.baize.subscription;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** One short-lived OAuth receiver, bound exclusively to IPv4 loopback. */
final class LoopbackReceiver implements Closeable {
    private final ServerSocket server = new ServerSocket();
    private volatile boolean closed;
    interface Responder { void send(Socket socket, int status, String message) throws IOException; }
    private final Responder responder;
    private final java.util.concurrent.ArrayBlockingQueue<String> recovered = new java.util.concurrent.ArrayBlockingQueue<>(1);
    LoopbackReceiver() throws IOException { this(LoopbackReceiver::respond); }
    LoopbackReceiver(Responder responder) throws IOException {
        this.responder = responder;
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        server.setSoTimeout(1000);
    }
    String callbackUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback"; }
    String healthUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/probe-health"; }
    boolean recover(String url) {
        if (closed || url == null || url.length() > 16384) return false;
        try {
            URI uri = new URI(url.trim());
            if (!"http".equals(uri.getScheme()) || !hostMatches(uri.getRawAuthority())
                || !"/auth/callback".equals(uri.getRawPath()) || uri.getRawQuery() == null || uri.getRawFragment() != null) return false;
            return recovered.offer(uri.getRawPath() + "?" + uri.getRawQuery());
        } catch (Exception e) { return false; }
    }
    Map<String, String> await(String state, String clientId, ProbeDiagnostics diagnostics) throws Exception {
        long deadline = System.nanoTime() + 300_000_000_000L;
        while (!closed && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            String retry = recovered.poll();
            if (retry != null) {
                diagnostics.callbackSeen();
                try {
                    Map<String, String> result = callback(retry, state, clientId);
                    diagnostics.mark(ProbeDiagnostics.Stage.CALLBACK_RECEIVED);
                    return result;
                } catch (Failure e) {
                    diagnostics.rejected();
                    if (e.code.equals("access_denied") || e.code.equals("oauth_callback_error")) throw e;
                } catch (Exception e) { diagnostics.rejected(); }
            }
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                String first = line(in, 16384);
                if (first == null) continue;
                String[] request = first.split(" ");
                String host = null; int headerBytes = 0; boolean headersValid = true;
                String header;
                while ((header = line(in, 4096)) != null && !header.isEmpty()) {
                    headerBytes += header.length();
                    if (headerBytes > 16384) { headersValid = false; break; }
                    int colon = header.indexOf(':');
                    if (colon < 1) { headersValid = false; break; }
                    if (header.substring(0, colon).equalsIgnoreCase("Host")) {
                        if (host != null) { headersValid = false; break; }
                        host = header.substring(colon + 1).trim();
                    }
                }
                if (!headersValid || header == null || !hostMatches(host) || request.length != 3
                    || !"GET".equals(request[0]) || !request[2].startsWith("HTTP/1.")) {
                    diagnostics.rejected(); responder.send(socket, 400, "Invalid local request."); continue;
                }
                if (request[1].equals("/probe-health")) {
                    diagnostics.healthSeen(); responder.send(socket, 200, "MOLING_CALLBACK_OK\nBrowser can reach Moling on this device. Return to Moling or the original authorization tab.");
                    continue;
                }
                if (request[1].startsWith("/auth/callback?")) diagnostics.callbackSeen();
                Map<String, String> result;
                try { result = callback(request[1], state, clientId); }
                catch (Failure e) {
                    diagnostics.rejected(); responder.send(socket, 400, "Callback rejected. Return to Moling.");
                    if (e.code.equals("access_denied") || e.code.equals("oauth_callback_error")) throw e;
                    continue;
                } catch (Exception e) { diagnostics.rejected(); responder.send(socket, 400, "Invalid callback. Return to Moling."); continue; }
                diagnostics.mark(ProbeDiagnostics.Stage.CALLBACK_RECEIVED);
                // The validated grant is independent of the browser's ability to read this receipt.
                try { responder.send(socket, 200, "Authorization received. Return to WE for verification results."); }
                catch (IOException ignored) { /* Browser/VPN network transition must not discard the grant. */ }
                return result;
            } catch (SocketTimeoutException ignored) { /* Overall deadline still applies. */ }
              catch (SocketException e) { if (closed) throw new Failure("cancelled"); /* One client disconnect is not cancellation. */ }
              catch (IOException ignored) { /* A broken client does not end the authorization listener. */ }
              catch (Failure e) { if (!e.code.equals("line_too_large")) throw e; diagnostics.rejected(); }
        }
        throw new Failure(closed || Thread.currentThread().isInterrupted() ? "cancelled" : "authorization_timeout");
    }
    private boolean hostMatches(String host) { return ("127.0.0.1:" + server.getLocalPort()).equals(host); }
    private static void respond(Socket socket, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
            + "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
            + "\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: "
            + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
    static String line(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (out.size() >= limit) throw new Failure("line_too_large"); out.write(b);
        }
        if (b == -1 && out.size() == 0) return null;
        String s = out.toString("UTF-8"); return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
    @Override public void close() throws IOException { closed = true; server.close(); }
}
