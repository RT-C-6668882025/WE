package com.opc.baize.mcp

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WordTokTransportTest {
    @Test fun devicePollingAndReceiptsUseWordTokBearerTransport() {
        MockWebServer().use { server ->
            server.start()
            val transport=WordTokTransport()
            try {
                server.enqueue(MockResponse().setBody("[]"))
                transport.request(server.url("/device/poll").toString(),"test-device-secret")
                val poll=server.takeRequest(1,TimeUnit.SECONDS)!!
                assertEquals("GET",poll.method)
                assertEquals("Bearer test-device-secret",poll.getHeader("Authorization"))
                server.enqueue(MockResponse().setBody("{}"))
                transport.request(server.url("/device/result").toString(),"test-device-secret",JSONObject().put("id","request-1"))
                val receipt=server.takeRequest(1,TimeUnit.SECONDS)!!
                assertEquals("POST",receipt.method)
                assertEquals("application/json",receipt.getHeader("Content-Type"))
                assertEquals("request-1",JSONObject(receipt.body.readUtf8()).getString("id"))
            } finally {transport.close()}
        }
    }
    @Test fun redirectsAreNotFollowedAndErrorBodiesAreNotExposed() {
        MockWebServer().use { server ->
            server.start()
            val transport=WordTokTransport()
            try {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",server.url("/other")).setBody("private-secret"))
                val response=transport.request(server.url("/health").toString())
                assertEquals(302,response.code);assertEquals("",response.body)
                assertEquals(1,server.requestCount)
                server.enqueue(MockResponse().setResponseCode(401).setBody("private-secret"))
                val revoked=transport.request(server.url("/device/poll").toString(),"test-device-secret")
                assertEquals(401,revoked.code);assertEquals("",revoked.body)
            } finally {transport.close()}
        }
    }
    @Test fun closedTransportCannotIssueFurtherRequests() {
        val transport=WordTokTransport();transport.close()
        try {transport.request("http://127.0.0.1:1/health");fail("closed transport used")}
        catch(_:IllegalStateException) {}
    }
}
