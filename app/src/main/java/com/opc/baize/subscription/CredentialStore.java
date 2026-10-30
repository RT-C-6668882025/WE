package com.opc.baize.subscription;

/** Only encrypted implementations are used by the application. */
interface CredentialStore {
    String read() throws Exception;
    void write(String value) throws Exception;
    void clear() throws Exception;
}
