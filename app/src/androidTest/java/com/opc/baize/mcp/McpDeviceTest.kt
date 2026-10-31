package com.opc.baize.mcp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class McpDeviceTest {
    @Test fun nativeHttpWorkspaceInkAndContinuedPages() = McpContract.verify(InstrumentationRegistry.getInstrumentation().targetContext, true)
    @Test fun foregroundEndpointStartsAndStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startForegroundService(android.content.Intent(context, McpService::class.java))
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (!McpService.running && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25)
            org.junit.Assert.assertTrue(McpService.status, McpService.running)
            val client = okhttp3.OkHttpClient()
            try {
                client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:49327/health")
                    .header("Authorization", "Bearer " + McpService.localToken).build()).execute().use { org.junit.Assert.assertEquals(200, it.code) }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } finally {
            context.stopService(android.content.Intent(context, McpService::class.java))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (McpService.running && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25)
        }
        org.junit.Assert.assertFalse(McpService.running)
        org.junit.Assert.assertEquals("", McpService.localToken)
    }
    @Test fun pairingIsEncryptedAndSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = McpPairing(context)
        org.junit.Assume.assumeTrue(store.read() == null)
        val token = "pairing-fixture-${java.util.UUID.randomUUID()}"
        try {
            store.save(org.json.JSONObject().put("deviceToken", token).put("base", "https://fixture.example"))
            org.junit.Assert.assertEquals(token, McpPairing(context).read()!!.getString("deviceToken"))
            val bytes = java.io.File(context.noBackupFilesDir, "we-mcp-pairing.enc").readBytes()
            org.junit.Assert.assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(token))
        } finally { store.clear() }
        org.junit.Assert.assertNull(McpPairing(context).read())
    }
}
