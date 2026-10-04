package com.coucou.android

/**
 * A minimal JSON reader/writer.
 *
 * The island bridge has to read the frontend's Tauri payloads and answer them, and
 * `org.json` is a stub in JVM unit tests (every call throws "not mocked"), so the
 * handful of shapes that actually cross the bridge are parsed here instead. That keeps
 * the whole command layer testable on the JVM, like [CoucouCharacterEngine].
 *
 * Deliberately small: objects, arrays, strings, numbers, booleans, null. Values are
 * `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` or `null`. Integers
 * are not preserved (JSON has one number type) — [asDouble] and [asInt] are the
 * accessors callers should use.
 */
internal object Json {

    /** Parses [text], returning null when it is not valid JSON. */
    fun parse(text: String): Any? = try {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (reader.atEnd()) value else null
    } catch (_: Exception) {
        null
    }

    /** Parses [text] as an object, or returns an empty map. */
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: emptyMap()

    /** Serialises [value]; unknown types are written as null rather than throwing. */
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Double -> writeNumber(value)
        is Int -> value.toString()
        is Long -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { entry ->
            "${quote(entry.key.toString())}:${write(entry.value)}"
        }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> "null"
    }

    /** JSON string literal for [value], quotes and control characters included. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun writeNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private class Reader(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            check(index < text.length) { "unexpected end of JSON" }
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) readNumber() else error("unexpected '$char'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (val char = next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> error("unexpected '$char' in object")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWhitespace()
                when (val char = next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> error("unexpected '$char' in array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val char = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val escape = next()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            val hex = text.substring(index, index + 4)
                            index += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape '\\$escape'")
                    }
                    else -> out.append(char)
                }
            }
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return text.substring(start, index).toDouble()
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) error("bad literal at $index")
            index += literal.length
            return value
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun next(): Char {
            check(index < text.length) { "unexpected end of JSON" }
            return text[index++]
        }

        private fun expect(char: Char) {
            check(next() == char) { "expected '$char' at ${index - 1}" }
        }
    }
}

/** Reads [key] as a string, or null when absent or of another type. */
internal fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String

/** Reads [key] as a number, tolerating the numeric strings some callers send. */
internal fun Map<String, Any?>.doubleOrNull(key: String): Double? = when (val value = this[key]) {
    is Double -> value
    is String -> value.toDoubleOrNull()
    else -> null
}

/** Reads [key] as a boolean. Only a real JSON boolean counts — a string is not truthy. */
internal fun Map<String, Any?>.boolOrNull(key: String): Boolean? = this[key] as? Boolean

/** Reads [key] as a nested object, or an empty map. */
internal fun Map<String, Any?>.objectOrEmpty(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: emptyMap()