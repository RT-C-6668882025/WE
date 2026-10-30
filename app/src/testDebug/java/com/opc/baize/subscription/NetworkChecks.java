package com.opc.baize.subscription;

import java.io.*;
import java.net.*;
import javax.net.ssl.*;

public final class NetworkChecks {
    static int checks;
    static void yes(boolean ok) { checks++; if (!ok) throw new AssertionError("Network check " + checks); }
    public static void main(String[] args) { runAll(); System.out.println("PASS: " + checks + " safe network-error checks"); }
    public static void runAll() {
        checks = 0;
        yes(NetworkErrors.code(new SocketException("Connection reset by peer"), false).equals("network_connection_reset"));
        yes(NetworkErrors.code(new SocketException("Broken pipe"), false).equals("network_broken_pipe"));
        yes(NetworkErrors.code(new SocketException("Socket closed"), false).equals("network_socket_closed"));
        yes(NetworkErrors.code(new UnknownHostException("auth.openai.com"), false).equals("network_dns_failed"));
        yes(NetworkErrors.code(new SocketTimeoutException(), false).equals("network_timeout"));
        yes(NetworkErrors.code(new SSLHandshakeException("sensitive-diagnostic"), false).equals("tls_handshake_failed"));
        yes(NetworkErrors.code(new SSLPeerUnverifiedException("sensitive-certificate"), false).equals("tls_certificate_rejected"));
        yes(NetworkErrors.code(new IOException("access_token=synthetic-secret"), false).equals("network_io_failed"));
        yes(NetworkErrors.code(new SocketException("Connection reset"), true).equals("cancelled"));
        yes(NetworkErrors.code(new IOException("outer", new EOFException()), false).equals("network_unexpected_eof"));
    }
}
