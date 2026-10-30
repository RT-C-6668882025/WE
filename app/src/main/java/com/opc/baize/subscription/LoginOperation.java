package com.opc.baize.subscription;

/** Application-owned login status. Detaching a screen never cancels or consumes the grant. */
final class LoginOperation {
    private long generation;
    private boolean running;
    private String result = "";
    private String browserUrl;
    synchronized long start() {
        if (running) return -1;
        running = true; result = ""; browserUrl = null; return ++generation;
    }
    synchronized boolean isCurrent(long ticket) { return running && generation == ticket; }
    synchronized void browser(long ticket, String url) { if (isCurrent(ticket)) browserUrl = url; }
    synchronized String takeBrowserUrl() { String url = browserUrl; browserUrl = null; return url; }
    synchronized void finish(long ticket, String value) {
        if (!isCurrent(ticket)) return;
        running = false; browserUrl = null; result = value;
    }
    synchronized void cancel() { generation++; running = false; browserUrl = null; result = "已取消授权；可重新登录。"; }
    synchronized void record(String value) { generation++; running = false; browserUrl = null; result = value; }
    synchronized boolean isRunning() { return running; }
    synchronized String result() { return result; }
    synchronized long version() { return generation; }
}
