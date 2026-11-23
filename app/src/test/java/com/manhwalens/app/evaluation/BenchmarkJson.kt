package com.manhwalens.app.evaluation

/**
 * A purpose-built reader for the RC9 benchmark asset.
 *
 * Deliberately tiny and separate from [com.manhwalens.app.evaluation.EvaluationCorpus], which uses
 * `org.json` and therefore needs an Android runtime. This one has to run on a plain JVM so the
 * metric can be measured in CI without a device. It understands exactly the shape the checked-in
 * asset uses — objects, arrays, strings, numbers, booleans, null — and nothing else.
 *
 * It returns the parsed tree unchanged and exposes typed accessors, rather than flattening cases
 * into strings. An earlier version did flatten, and it lost data twice: a fragment text containing
 * the record separator, and a nested object whose fields collided with the outer separator. Typed
 * access removes the class of bug instead of patching instances of it.
 */
/**
 * A parsed case. Declared at file scope: a `typealias` nested inside an object is rejected by the
 * project's Kotlin version even though newer compilers accept it, so it must not live in the object.
 */
internal typealias BenchCase = Map<String, Any?>

internal object BenchmarkJson {

    data class Fragment(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    fun parseCases(json: String): List<BenchCase> {
        val root = Parser(json).parseDocument() as? BenchCase ?: error("benchmark root must be an object")
        val cases = root["cases"] as? List<*> ?: error("benchmark must carry `cases`")
        return cases.map { entry -> entry as? BenchCase ?: error("each case must be an object") }
    }

    fun str(case: BenchCase, key: String): String? = case[key] as? String

    fun strOrBlank(case: BenchCase, key: String): String = str(case, key) ?: ""

    fun strings(case: BenchCase, key: String): List<String> =
        (case[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    fun fragments(case: BenchCase): List<Fragment> =
        (case["fragments"] as? List<*>).orEmpty().map { raw ->
            val map = raw as? BenchCase ?: error("fragment must be an object")
            Fragment(
                text = str(map, "text") ?: error("fragment needs text"),
                left = int(map, "left"),
                top = int(map, "top"),
                right = int(map, "right"),
                bottom = int(map, "bottom"),
            )
        }

    private fun int(map: BenchCase, key: String): Int =
        (map[key] as? String)?.toIntOrNull()
            ?: (map[key] as? Number)?.toInt()
            ?: error("fragment field '$key' must be a number, got ${map[key]}")

    private class Parser(private val text: String) {
        private var at = 0

        fun parseDocument(): Any? {
            val value = readValue()
            skipWhitespace()
            require(at == text.length) { "trailing content at $at" }
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            require(at < text.length) { "unexpected end of input" }
            return when (val c = text[at]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c' at $at")
            }
        }

        private fun readObject(): Map<String, Any?> {
            require(text[at] == '{')
            at++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (at < text.length && text[at] == '}') { at++; return out }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(text[at] == ':') { "expected ':' at $at" }
                at++
                out[key] = readValue()
                skipWhitespace()
                when {
                    at < text.length && text[at] == ',' -> at++
                    at < text.length && text[at] == '}' -> { at++; return out }
                    else -> error("expected ',' or '}' at $at")
                }
            }
        }

        private fun readArray(): List<Any?> {
            require(text[at] == '[')
            at++
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (at < text.length && text[at] == ']') { at++; return out }
            while (true) {
                out += readValue()
                skipWhitespace()
                when {
                    at < text.length && text[at] == ',' -> at++
                    at < text.length && text[at] == ']' -> { at++; return out }
                    else -> error("expected ',' or ']' at $at")
                }
            }
        }

        private fun readString(): String {
            require(text[at] == '"') { "expected string at $at" }
            at++
            val out = StringBuilder()
            while (true) {
                require(at < text.length) { "unterminated string" }
                when (val c = text[at]) {
                    '"' -> { at++; return out.toString() }
                    '\\' -> {
                        at++
                        when (val esc = text[at]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(text.substring(at + 1, at + 5).toInt(16).toChar())
                                at += 4
                            }
                            else -> error("bad escape \\$esc at $at")
                        }
                        at++
                    }
                    else -> { out.append(c); at++ }
                }
            }
        }

        /** Numbers are returned as their literal text; callers convert with an explicit accessor. */
        private fun readNumber(): String {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at)
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(text.startsWith(literal, at)) { "expected $literal at $at" }
            at += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
