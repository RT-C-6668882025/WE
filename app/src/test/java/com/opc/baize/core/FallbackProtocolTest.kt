package com.opc.baize.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mac 降级通道契约（server.py /api/ask） */
class FallbackProtocolTest {

    @Test fun requestMatchesServerContract() {
        val body = JSONObject(FallbackProtocol.buildRequestBody(
            "data:image/png;base64,AAAA", "default", 7))
        assertEquals("data:image/png;base64,AAAA", body.getString("image"))
        assertEquals("default", body.getString("session"))
        assertEquals(7, body.getInt("strokes"))
    }

    @Test fun parseNormalReply() {
        val r = FallbackProtocol.parseResponse(
            """{"ok":true,"recognized":"养生之道","reply":"起居有常。","elapsed_ms":4200,"channel":"vision+ds-flash"}""")
        assertTrue(r.ok)
        assertFalse(r.ignored)
        assertNull(r.command)
        assertEquals("养生之道", r.recognized)
        assertEquals("起居有常。", r.reply)
    }

    @Test fun parseIgnored() {
        val r = FallbackProtocol.parseResponse(
            """{"ok":true,"ignored":true,"recognized":"","elapsed_ms":300}""")
        assertTrue(r.ok)
        assertTrue(r.ignored)
    }

    @Test fun parseNewCommand() {
        val r = FallbackProtocol.parseResponse(
            """{"ok":true,"command":"new","recognized":"/new","elapsed_ms":900}""")
        assertEquals("new", r.command)
    }

    @Test fun parseError() {
        val r = FallbackProtocol.parseResponse(
            """{"ok":false,"error":"直连与 CLI 兜底均失败"}""")
        assertFalse(r.ok)
        assertEquals("直连与 CLI 兜底均失败", r.error)
    }
}
