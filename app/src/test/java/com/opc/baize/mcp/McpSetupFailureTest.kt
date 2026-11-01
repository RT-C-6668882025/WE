package com.opc.baize.mcp
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.security.KeyStoreException
class McpSetupFailureTest {
    @Test fun diagnosticKeepsStageAndSuppressesExceptionText() {
        val secret="private-pairing-value"
        val errors=listOf(UnknownHostException(secret),SSLHandshakeException(secret),KeyStoreException(secret),IllegalArgumentException(secret))
        errors.forEach {e ->
            val text=try {McpSetupFailure.at("保存配对凭据") {throw e}} catch(f:McpSetupFailure) {McpSetupFailure.describe(f)}
            assertTrue(text.startsWith("保存配对凭据："));assertFalse(text.contains(secret))
        }
    }
    @Test fun httpErrorIsNotReducedToGenericNetworkFailure() {
        assertEquals("建立配对：HTTP 503",McpSetupFailure.describe(McpSetupFailure("建立配对","HTTP 503")))
    }
}
