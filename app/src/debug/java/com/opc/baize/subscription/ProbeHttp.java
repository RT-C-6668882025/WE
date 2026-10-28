package com.opc.baize.subscription;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Debug transport: normal certificate validation, no redirects, no automatic code replay. */
final class ProbeHttp implements java.io.Closeable {
    private final ProbeDiagnostics diagnostics;
    private final OkHttpClient client;
    private volatile Call active;
    private volatile boolean closed;
    ProbeHttp(ProbeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            // No connection survives the browser-consent pause or a completed request.
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .eventListener(new okhttp3.EventListener() {
                @Override public void dnsStart(Call c, String name) { note("DNS 查询开始"); }
                @Override public void dnsEnd(Call c, String name, List<InetAddress> addresses) { note("DNS 查询完成"); }
                @Override public void connectStart(Call c, InetSocketAddress address, Proxy proxy) { note("TCP / 代理连接开始"); }
                @Override public void secureConnectStart(Call c) { note("TLS 握手开始"); }
                @Override public void secureConnectEnd(Call c, Handshake h) { note("TLS 握手及证书校验通过"); }
                @Override public void requestHeadersStart(Call c) { note("开始发送 HTTP 请求头"); }
                @Override public void requestBodyStart(Call c) { note("开始发送请求体"); }
                @Override public void requestBodyEnd(Call c, long count) { note("请求体已写出（不代表服务已处理）"); }
                @Override public void responseHeadersStart(Call c) { note("等待 HTTP 响应头"); }
                @Override public void responseHeadersEnd(Call c, Response r) { note("收到 HTTP " + r.code()); }
                @Override public void callFailed(Call c, IOException e) { note(NetworkErrors.code(e, closed)); }
            }).build();
    }
    private void note(String message) { diagnostics.transport(message); }
    Response execute(String method, String url, String body, String type, String bearer, String accept) throws Exception {
        if (closed) throw new Failure("cancelled");
        diagnostics.transport("新连接请求开始");
        Request.Builder request = new Request.Builder().url(url).header("Accept", accept);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        RequestBody bytes = body == null ? null : RequestBody.create(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.get(type));
        request.method(method, bytes);
        Call call = client.newCall(request.build()); active = call;
        if (closed) call.cancel();
        try { return call.execute(); }
        catch (IOException e) { throw failure(e); }
    }
    Failure failure(IOException error) { return new Failure(NetworkErrors.code(error, closed)); }
    @Override public void close() {
        closed = true;
        Call call = active; if (call != null) call.cancel();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
