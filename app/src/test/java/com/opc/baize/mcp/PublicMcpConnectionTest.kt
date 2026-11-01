package com.opc.baize.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PublicMcpConnectionTest {
    private fun identity(base:String=PublicMcpConnection.DEFAULT_RELAY)=JSONObject().put("base",base)
        .put("agentToken","a".repeat(64)).put("deviceToken","b".repeat(64))
    @Test fun firstCopyPairsOnceAndRecreationKeepsTheSameAddress() {
        var saved:JSONObject?=null;var pairs=0
        fun setup()=PublicMcpConnection({saved},{saved=JSONObject(it.toString())},{url->
            assertEquals("https://we.wordrelay.dpdns.org",url);pairs++;identity()
        })
        val first=PublicMcpConnection.address(setup().ensure())
        val second=PublicMcpConnection.address(setup().ensure())
        assertEquals(1,pairs);assertEquals(first,second)
        assertEquals(PublicMcpConnection.DEFAULT_RELAY+"/mcp/"+"a".repeat(64),first)
    }
    @Test fun concurrentOpenDoesNotProduceTwoPairings() {
        var saved:JSONObject?=null;val pairs=AtomicInteger();val gate=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val futures=(0..1).map {executor.submit<String> {
                gate.await()
                val setup=PublicMcpConnection({saved},{saved=it},{pairs.incrementAndGet();identity()})
                PublicMcpConnection.address(setup.ensure())
            }}
            gate.countDown();assertEquals(futures[0].get(5,TimeUnit.SECONDS),futures[1].get(5,TimeUnit.SECONDS))
            assertEquals(1,pairs.get())
        } finally {executor.shutdownNow()}
    }
    @Test fun networkFailureCanRetryWithoutSavingAnIncompleteIdentity() {
        var saved:JSONObject?=null;var fails=true
        val setup=PublicMcpConnection({saved},{saved=it},{if(fails) error("offline") else identity()})
        try {setup.ensure();fail()} catch(_:McpSetupFailure){}
        assertNull(saved);fails=false;assertNotNull(setup.ensure());assertNotNull(saved)
    }
    @Test fun existingPairingAndFailedRevokeArePreserved() {
        var saved:JSONObject?=identity("https://existing.example")
        val setup=PublicMcpConnection({saved},{saved=it},{error("Must not pair again")},{saved=null})
        assertTrue(PublicMcpConnection.address(setup.ensure()).startsWith("https://existing.example/mcp/"))
        try {setup.revoke {error("offline")};fail()} catch(_:IllegalStateException){}
        assertNotNull(saved)
        setup.revoke {};assertNull(saved)
    }
    @Test fun invalidSavedConfigurationIsNotSilentlyReplaced() {
        val invalid=identity().put("agentToken","bad")
        var called=false
        try {PublicMcpConnection({invalid},{},{called=true;identity()}).ensure();fail()} catch(_:McpSetupFailure){}
        assertFalse(called)
    }
}
