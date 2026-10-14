package com.nousresearch.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesNativeBridgeTest {

    @Test
    fun testDefaultGatewayUrl() {
        assertEquals("http://127.0.0.1:9119", HermesNativeBridge.DEFAULT_GATEWAY_URL)
        assertEquals("HermesAndroid", HermesNativeBridge.BRIDGE_NAME)
    }

    @Test
    fun testUrlNormalizationLogic() {
        val raw = "http://127.0.0.1:9119/ "
        val trimmed = raw.trim().removeSuffix("/")
        val normalized = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
        assertEquals("http://127.0.0.1:9119", normalized)

        val rawWithoutScheme = "127.0.0.1:9119"
        val normalizedWithoutScheme = if (!rawWithoutScheme.startsWith("http://") && !rawWithoutScheme.startsWith("https://")) {
            "http://$rawWithoutScheme"
        } else {
            rawWithoutScheme
        }
        assertEquals("http://127.0.0.1:9119", normalizedWithoutScheme)
    }

    @Test
    fun testPackageConfiguration() {
        val expectedPackage = "com.nousresearch.hermes.android"
        assertEquals(expectedPackage, MainActivity::class.java.`package`?.name)
    }

    @Test
    fun testParseSessionTokenWithDoubleQuotes() {
        val html = """<script>window.__HERMES_SESSION_TOKEN__="c6d7e8f9a0b1c2d3";</script>"""
        val token = HermesNativeBridge.parseSessionToken(html)
        assertEquals("c6d7e8f9a0b1c2d3", token)
    }

    @Test
    fun testParseSessionTokenWithSingleQuotesAndSpaces() {
        val html = """<script>window.__HERMES_SESSION_TOKEN__ = 'secret_token_abc_123';</script>"""
        val token = HermesNativeBridge.parseSessionToken(html)
        assertEquals("secret_token_abc_123", token)
    }

    @Test
    fun testParseSessionTokenInFullHtmlDocument() {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Hermes Dashboard</title>
                <script>
                    window.__HERMES_SESSION_TOKEN__="sess_xyz_789456";
                    window.__HERMES_GATEWAY_URL__="http://127.0.0.1:9119";
                </script>
            </head>
            <body>
                <div id="root">Dashboard</div>
            </body>
            </html>
        """.trimIndent()
        val token = HermesNativeBridge.parseSessionToken(html)
        assertEquals("sess_xyz_789456", token)
    }

    @Test
    fun testParseSessionTokenMissingReturnsNull() {
        val html = """<!DOCTYPE html><html><body>No token here</body></html>"""
        val token = HermesNativeBridge.parseSessionToken(html)
        assertEquals(null, token)
    }

    @Test
    fun testParseSessionTokenEmptyStringReturnsNull() {
        val html = """<script>window.__HERMES_SESSION_TOKEN__="";</script>"""
        val token = HermesNativeBridge.parseSessionToken(html)
        assertEquals(null, token)
    }
}

