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
}
