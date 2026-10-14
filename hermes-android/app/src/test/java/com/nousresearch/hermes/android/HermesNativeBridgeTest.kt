package com.nousresearch.hermes.android

import org.junit.Assert.assertEquals
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
        val trimmed = raw.trim()
        val normalized = if (trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
        assertEquals("http://127.0.0.1:9119", normalized)
    }
}
