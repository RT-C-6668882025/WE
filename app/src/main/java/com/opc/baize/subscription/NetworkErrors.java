package com.opc.baize.subscription;

import java.io.*;
import java.net.*;
import java.util.Locale;
import javax.net.ssl.*;

/** Classifies causes without returning raw messages, which may contain request data. */
final class NetworkErrors {
    static String code(Throwable error, boolean cancelled) {
        if (cancelled) return "cancelled";
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof SSLPeerUnverifiedException || e instanceof java.security.cert.CertificateException)
                return "tls_certificate_rejected";
        }
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof UnknownHostException) return "network_dns_failed";
            if (e instanceof SocketTimeoutException) return "network_timeout";
            if (e instanceof ConnectException) return "network_connect_failed";
            String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
            if (message.contains("ECONNRESET") || message.contains("CONNECTION RESET")) return "network_connection_reset";
            if (message.contains("EPIPE") || message.contains("BROKEN PIPE")) return "network_broken_pipe";
            if (message.contains("SOCKET CLOSED") || message.contains("SOCKET IS CLOSED")) return "network_socket_closed";
            if (e instanceof EOFException) return "network_unexpected_eof";
            if (e instanceof SSLHandshakeException) return "tls_handshake_failed";
            if (e instanceof SSLException) return "tls_io_failed";
            if (e instanceof InterruptedIOException) return "network_interrupted_or_deadline";
        }
        return "network_io_failed";
    }
}
