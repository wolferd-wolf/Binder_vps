package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge's JSON layer.
 *
 * Everything crossing the bridge is parsed by hand because `org.json` is a stub in JVM
 * unit tests, so these cases are what stands between the page and a silently malformed
 * payload.
 */
class JsonTest {

    @Test
    fun `parses the flat object the bridge receives`() {
        val parsed = Json.parseObject("""{"query":"chrome","context":null}""")
        assertEquals("chrome", parsed.stringOrNull("query"))
        assertNull(parsed["context"])
    }

    @Test
    fun `parses nested objects and arrays`() {
        val parsed = Json.parse("""{"settings":{"soundEnabled":false,"items":["a","b"]}}""")
        val settings = (parsed as Map<*, *>)["settings"] as Map<*, *>
        assertEquals(false, settings["soundEnabled"])
        assertEquals(listOf("a", "b"), settings["items"])
    }

    @Test
    fun `parses escapes in both directions`() {
        val parsed = Json.parseObject("""{"query":"say \"hi\"\nnow\\","unicode":"é"}""")
        assertEquals("say \"hi\"\nnow\\", parsed["query"])
        assertEquals("\u00e9", parsed["unicode"])
    }

    @Test
    fun `numbers arrive as doubles regardless of how they were written`() {
        val parsed = Json.parseObject("""{"a":1,"b":1.5,"c":-2e2,"d":"3"}""")
        assertEquals(1.0, parsed.doubleOrNull("a")!!, 1e-9)
        assertEquals(1.5, parsed.doubleOrNull("b")!!, 1e-9)
        assertEquals(-200.0, parsed.doubleOrNull("c")!!, 1e-9)
        // A numeric string is tolerated: some callers send numbers as strings.
        assertEquals(3.0, parsed.doubleOrNull("d")!!, 1e-9)
        assertNull(parsed.doubleOrNull("missing"))
    }

    @Test
    fun `booleans are read strictly, not coerced from any string`() {
        val parsed = Json.parseObject("""{"yes":true,"no":false,"maybe":"true"}""")
        assertEquals(true, parsed.boolOrNull("yes"))
        assertEquals(false, parsed.boolOrNull("no"))
        assertNull(parsed.boolOrNull("maybe"))
    }

    @Test
    fun `missing objects read as empty rather than throwing`() {
        assertEquals(emptyMap<String, Any?>(), Json.parseObject("""{"other":1}""").objectOrEmpty("settings"))
        assertEquals(emptyMap<String, Any?>(), Json.parseObject("""{"settings":3}""").objectOrEmpty("settings"))
    }

    @Test
    fun `malformed input yields null instead of an exception`() {
        for (text in listOf("", "   ", "{", """{"a":}""", """{"a":1}trailing""", "nope")) {
            assertNull("must reject '$text'", Json.parse(text))
        }
        assertEquals(emptyMap<String, Any?>(), Json.parseObject("nope"))
    }

    @Test
    fun `writes whole numbers without a decimal point`() {
        assertEquals("15", Json.write(15.0))
        assertEquals("0.12", Json.write(0.12))
        assertEquals("null", Json.write(null))
    }

    @Test
    fun `quotes control characters so the payload stays valid json`() {
        assertEquals("\"a\\nb\"", Json.quote("a\nb"))
        assertEquals("\"\\u0007\"", Json.quote("\u0007"))
        assertEquals("\"\\\"q\\\"\"", Json.quote("\"q\""))
    }

    @Test
    fun `write round trips through parse`() {
        val original = mapOf(
            "text" to "line1\nline2 \"quoted\"",
            "nested" to mapOf("flag" to true, "count" to 3.0)
        )
        val parsed = Json.parseObject(Json.write(original))
        assertEquals(original["text"], parsed["text"])
        val nested = parsed["nested"] as Map<*, *>
        assertEquals(true, nested["flag"])
        assertEquals(3.0, nested["count"])
    }
}