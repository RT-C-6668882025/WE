package com.opc.baize.subscription;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two separate instrumentation processes, separated by am force-stop in CI. Synthetic credentials only. */
@RunWith(AndroidJUnit4.class)
public class PersistentSessionDeviceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    @Test public void seedEncryptedVerifiedSession() throws Exception {
        Context context = context();
        CredentialStore store = new SecureCredentialStore(context); store.clear();
        context.getSharedPreferences("subscription_probe_registration", 0).edit()
            .putString("client_id", "synthetic-client").putString("subject", "synthetic-subject")
            .putInt("seed_process_id", android.os.Process.myPid()).commit();
        JSONObject tokens = new JSONObject().put("access_token", "synthetic_access_marker")
            .put("refresh_token", "synthetic_refresh_marker").put("token_type", "Bearer").put("expires_in", 3600)
            .put("scope", SubscriptionProtocol.SCOPES);
        new SessionJournal(store).verified("synthetic-client", "synthetic-subject", tokens, System.currentTimeMillis() + 3600000);
        byte[] encrypted = Files.readAllBytes(new File(context.getNoBackupFilesDir(), "subscription-session-v1.enc").toPath());
        String raw = new String(encrypted, StandardCharsets.UTF_8);
        assertFalse(raw.contains("synthetic_access_marker")); assertFalse(raw.contains("synthetic_refresh_marker"));
        SubscriptionProbeClient client = new SubscriptionProbeClient(context);
        assertTrue(client.hasDirectPermission()); client.close();
        // Closing an in-memory client must never delete the durable session.
        assertNotNull(new SessionJournal(new SecureCredentialStore(context)).read("synthetic-client", "synthetic-subject"));
    }
    @Test public void restoresAfterForceStopAndClearPreventsResurrection() throws Exception {
        Context context = context();
        assertNotEquals("Must run in a new process", context.getSharedPreferences("subscription_probe_registration", 0).getInt("seed_process_id", -1), android.os.Process.myPid());
        SubscriptionProbeClient restored = new SubscriptionProbeClient(context);
        assertTrue("Verified session must survive force-stop and a new process", restored.hasDirectPermission());
        assertTrue(restored.restoreSession().contains("恢复"));
        restored.close(); restored.clearSavedSession();
        SubscriptionProbeClient loggedOut = new SubscriptionProbeClient(context);
        assertFalse("Deleted credentials must never restore", loggedOut.hasDirectPermission()); loggedOut.close();
    }
    @Test public void rejectsTamperedCiphertextAndKeepsExpiredRefreshState() throws Exception {
        Context context = context(); CredentialStore store = new SecureCredentialStore(context); store.clear();
        JSONObject tokens = new JSONObject().put("access_token", "synthetic_expired_access")
            .put("refresh_token", "synthetic_renewable_refresh").put("token_type", "Bearer").put("expires_in", 3600)
            .put("scope", SubscriptionProtocol.SCOPES);
        SessionJournal journal = new SessionJournal(store);
        journal.verified("synthetic-client", "synthetic-subject", tokens, System.currentTimeMillis() - 1000);
        assertTrue(journal.read("synthetic-client", "synthetic-subject").getLong("expires_at") < System.currentTimeMillis());
        File file = new File(context.getNoBackupFilesDir(), "subscription-session-v1.enc");
        byte[] bytes = Files.readAllBytes(file.toPath()); bytes[bytes.length - 1] ^= 1; Files.write(file.toPath(), bytes);
        boolean rejected = false;
        try { store.read(); } catch (Exception expected) { rejected = true; }
        assertTrue("Authentication tag must reject modified ciphertext", rejected);
        store.clear();
    }
}
