package com.opc.baize.subscription;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** AEAD ciphertext in no-backup storage. The non-exportable AES key lives in Android Keystore. */
final class SecureCredentialStore implements CredentialStore {
    private static final String ALIAS = "moling.subscription.session.v1";
    private final AtomicFile file;
    private final byte[] binding;
    SecureCredentialStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "subscription-session-v1.enc"));
        binding = (context.getPackageName() + "|subscription-session-v1").getBytes(StandardCharsets.UTF_8);
    }
    private javax.crypto.SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new Failure("saved_session_key_unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey(ALIAS, null);
    }
    @Override public synchronized String read() throws Exception {
        byte[] bytes;
        try (InputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = in.read(chunk)) != -1) {
                if (out.size() + count > 3 * 1024 * 1024) throw new Failure("saved_session_invalid");
                out.write(chunk, 0, count);
            }
            bytes = out.toByteArray();
        } catch (FileNotFoundException e) { return null; }
        if (bytes.length < 30 || bytes[0] != 1) throw new Failure("saved_session_invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, java.util.Arrays.copyOfRange(bytes, 1, 13)));
        cipher.updateAAD(binding);
        return new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8);
    }
    @Override public synchronized void write(String value) throws Exception {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        if (plain.length > 2 * 1024 * 1024) throw new Failure("saved_session_too_large");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(binding); byte[] encrypted = cipher.doFinal(plain);
        if (cipher.getIV().length != 12) throw new Failure("saved_session_cipher_unavailable");
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(1); stream.write(cipher.getIV()); stream.write(encrypted); file.finishWrite(stream);
        } catch (Exception e) { file.failWrite(stream); throw e; }
    }
    @Override public synchronized void clear() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists())
            throw new Failure("saved_session_clear_failed");
    }
}
