package com.opc.baize.subscription;

/** Only fixed stage labels and counters are retained. No URLs, codes, tokens or identity. */
final class ProbeDiagnostics {
    enum Stage {
        IDLE("待验证"), DISCOVERY("读取官方授权配置"), PREFETCH_KEYS("授权前预取身份公钥"), WAIT_CALLBACK("等待浏览器授权回调"),
        CALLBACK_RECEIVED("已收到并校验授权回调"), TOKEN_EXCHANGE("向官方服务换取令牌"),
        VERIFY_IDENTITY("使用公钥验证身份"), AUTHORIZED("身份与授权检查结束"),
        MODELS("请求账号模型列表"), INFERENCE("请求模型并等待完成事件"),
        REFRESH("刷新令牌"), REVOKE("撤销会话");
        final String label;
        Stage(String label) { this.label = label; }
    }
    private String network = "尚未开始 HTTP 请求";
    private Stage stage = Stage.IDLE;
    private long started = System.nanoTime();
    private int callbacks, healthChecks, rejected;
    private final java.util.List<String> trace = new java.util.ArrayList<>();
    synchronized void reset() {
        started = System.nanoTime(); callbacks = healthChecks = rejected = 0;
        trace.clear(); stage = Stage.IDLE; network = "尚未开始 HTTP 请求";
    }
    synchronized void mark(Stage next) {
        stage = next;
        if (trace.size() >= 28) trace.remove(0);
        trace.add(seconds() + "s · " + next.label);
    }
    synchronized void transport(String event) {
        network = event;
        if (trace.size() == 28) trace.remove(0);
        trace.add(seconds() + "s · 网络：" + event);
    }
    synchronized void callbackSeen() { callbacks++; }
    synchronized void healthSeen() { healthChecks++; }
    synchronized void rejected() { rejected++; }
    private long seconds() { return (System.nanoTime() - started) / 1_000_000_000L; }
    synchronized String snapshot() {
        return "当前阶段：" + stage.label + "（" + seconds() + " 秒）"
            + "\n网络阶段：" + network
            + "\n浏览器本机连通测试：" + (healthChecks > 0 ? "已到达" : "尚未到达")
            + "\n授权回调到达：" + callbacks + " 次；被拒绝请求：" + rejected + " 次"
            + "\n" + String.join("\n", trace);
    }
}
