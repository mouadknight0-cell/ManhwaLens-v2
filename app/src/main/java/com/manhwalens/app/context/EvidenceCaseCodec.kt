package com.manhwalens.app.context

/**
 * Minimal reader for the evidence format.
 *
 * Hand-written for the same reason as the writers: it must be testable on a plain JVM, and it must
 * be strict. A capture is the only copy of a real observation, so a file that is partially
 * understandable is treated as damaged rather than silently half-loaded — a case missing its source
 * text would look like a case the engine had nothing to say about, which is a different and wrong
 * conclusion.
 *
 * The parser is deliberately small: it handles exactly what [EvidenceCaseEncoder] writes, including
 * Arabic emitted as raw UTF-8 and control characters as `\uXXXX`.
 */
internal object EvidenceCaseDecoder {

    data class Decoded(val ok: Boolean, val cases: List<EvidenceCase>, val reason: String = "")

    fun decode(text: String?, sessionId: String): Decoded {
        if (text.isNullOrBlank()) return Decoded(false, emptyList(), "empty")
        val root = runCatching { Parser(text).parseValue() as Map<*, *> }.getOrNull()
            ?: return Decoded(false, emptyList(), "not_an_object")
        if (root["formatVersion"] !is Number) return Decoded(false, emptyList(), "no_version")
        val cases = root["cases"] as? List<*> ?: return Decoded(false, emptyList(), "no_cases")

        val parsed = cases.mapNotNull { raw ->
            runCatching { parseCase(raw as Map<*, *>, sessionId) }.getOrNull()
        }
        // All-or-nothing: a single unreadable case means the file is not trustworthy as a whole.
        if (parsed.size != cases.size) return Decoded(false, emptyList(), "partial")
        return Decoded(true, parsed)
    }

    private fun parseCase(m: Map<*, *>, sessionId: String): EvidenceCase {
        val text = m["text"] as Map<*, *>
        val translation = m["translation"] as Map<*, *>
        val engine = m["engine"] as Map<*, *>
        val bridge = m["bridge"] as Map<*, *>
        val bounds = text["bounds"] as Map<*, *>

        return EvidenceCase(
            id = m["id"] as String,
            capturedAtMs = (m["capturedAtMs"] as Number).toLong(),
            sourceText = text["source"] as String,
            ocrText = text["ocr"] as String,
            ocrConfidence = (text["ocrConfidence"] as? Number)?.toDouble(),
            regionKind = text["regionKind"] as String,
            left = (bounds["left"] as Number).toInt(),
            top = (bounds["top"] as Number).toInt(),
            right = (bounds["right"] as Number).toInt(),
            bottom = (bounds["bottom"] as Number).toInt(),
            readingOrder = (text["readingOrder"] as Number).toInt(),
            panelIndex = (text["panelIndex"] as Number).toInt(),
            pageRegions = (text["pageRegions"] as List<*>).map { r ->
                val region = r as Map<*, *>
                EvidenceCase.PageRegion(
                    text = region["text"] as String,
                    left = (region["left"] as Number).toInt(),
                    top = (region["top"] as Number).toInt(),
                    right = (region["right"] as Number).toInt(),
                    bottom = (region["bottom"] as Number).toInt(),
                    readingOrder = (region["readingOrder"] as Number).toInt(),
                    kind = region["kind"] as String,
                    ocrConfidence = (region["ocrConfidence"] as? Number)?.toDouble(),
                )
            },
            regionsDetected = (text["regionsDetected"] as Number).toInt(),
            sessionId = (m["sessionId"] as? String).orEmpty().ifBlank { sessionId },
            pageId = (m["pageId"] as? String).orEmpty(),
            baselineArabic = translation["baseline"] as String,
            displayedArabic = translation["displayed"] as String,
            baselineMillis = (translation["baselineMs"] as? Number)?.toLong(),
            appVersionName = m["appVersionName"] as String,
            appVersionCode = (m["appVersionCode"] as Number).toInt(),
            gitRevision = m["gitRevision"] as String,
            mode = runCatching { ContextMode.valueOf(m["mode"] as String) }.getOrDefault(ContextMode.A_BASELINE),
            engineRan = engine["ran"] as Boolean,
            reachedLevel = runCatching { ContextLevel.valueOf(engine["reachedLevel"] as String) }
                .getOrDefault(ContextLevel.NONE),
            settledLevel = runCatching { ContextLevel.valueOf(engine["settledLevel"] as String) }
                .getOrDefault(ContextLevel.NONE),
            openQuestions = (engine["openQuestions"] as List<*>).map { it as String },
            unresolved = (engine["unresolved"] as List<*>).map { it as String },
            contextSources = (engine["contextSources"] as List<*>).map { it as String },
            contextText = engine["contextText"] as String,
            evidence = (engine["evidence"] as List<*>).map { e ->
                val record = e as Map<*, *>
                EvidenceCase.EvidenceRecord(
                    reason = record["reason"] as String,
                    kind = record["kind"] as String,
                    evidenceStrength = record["evidenceStrength"] as String,
                    confidence = (record["confidence"] as Number).toDouble(),
                    text = record["text"] as String,
                )
            },
            producedCandidate = translation["producedCandidate"] as Boolean,
            candidateArabic = translation["candidate"] as? String,
            candidateIsSemantic = translation["candidateIsSemantic"] as Boolean,
            bridgeCode = bridge["code"] as String,
            bridgeReasons = (bridge["reasons"] as List<*>).map { it as String },
            bridgeFacts = (bridge["facts"] as List<*>).map { f ->
                val fact = f as Map<*, *>
                EvidenceCase.BridgeFactRecord(
                    kind = fact["kind"] as String,
                    subject = fact["subject"] as String,
                    value = fact["value"] as String,
                    evidence = fact["evidence"] as String,
                    confidence = (fact["confidence"] as Number).toDouble(),
                    source = fact["source"] as String,
                    realizable = fact["realizable"] as Boolean,
                    blockedBy = fact["blockedBy"] as? String,
                )
            },
            rewrittenSource = bridge["rewrittenSource"] as? String,
            confidences = (engine["confidences"] as Map<*, *>).entries.associate { (k, v) ->
                (k as String) to (v as? Number)?.toDouble()
            },
            verdict = engine["verdict"] as String,
            error = engine["error"] as? String,
            memoryFacts = (m["memory"] as List<*>).map { f ->
                val fact = f as Map<*, *>
                EvidenceCase.MemoryFactRecord(
                    kind = fact["kind"] as String,
                    key = fact["key"] as String,
                    value = fact["value"] as String,
                    evidence = fact["evidence"] as String,
                    confidence = (fact["confidence"] as Number).toDouble(),
                    source = fact["source"] as String,
                    chapter = (fact["chapter"] as Number).toInt(),
                )
            },
            note = (m["note"] as? String).orEmpty(),
        )
    }

    /** A small recursive-descent JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private class Parser(private val text: String) {
        private var at = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (at >= text.length) fail("unexpected end")
            return when (val c = text[at]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected '$c'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect("{")
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { at++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(":")
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return map }
                    else -> fail("expected , or }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect("[")
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { at++; return list }
            while (true) {
                list += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return list }
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) fail("unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val esc = text.getOrNull(at++) ?: fail("bad escape")) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(at, at + 4)
                                at += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> fail("bad escape '$esc'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val raw = text.substring(start, at)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun expect(token: String) {
            if (!text.startsWith(token, at)) fail("expected '$token'")
            at += token.length
        }

        private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end")

        private fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}

/**
 * Writes captures in the same shape as [EvidenceCaseJson]'s bundle, but as a persisted session file.
 *
 * Kept separate from the export bundle so the stored form and the shared form cannot drift into two
 * different formats by accident: both are produced from the same case encoder.
 */
internal object EvidenceCaseEncoder {

    fun encodeAll(cases: List<EvidenceCase>): String = buildString {
        append('{')
        append("\"formatVersion\":").append(EvidenceCaseJson.FORMAT_VERSION).append(',')
        append("\"cases\":[")
        append(cases.joinToString(",") { single(it) })
        append("]}")
    }

    /** Reuses the exporter's per-case encoder so stored and exported cases are identical. */
    private fun single(case: EvidenceCase): String =
        EvidenceCaseJson.encode(
            EvidenceCaseJson.Bundle(
                sessionId = case.sessionId,
                exportedAtMs = 0,
                appVersionName = case.appVersionName,
                appVersionCode = case.appVersionCode,
                gitRevision = case.gitRevision,
                cases = listOf(case),
            ),
        ).let { bundle ->
            // Extract the single case object rather than duplicating the encoder. The bundle always
            // writes `"cases":[{...}]`, so the substring between the array brackets is the case.
            val marker = "\"cases\":["
            val start = bundle.indexOf(marker) + marker.length
            val end = bundle.lastIndexOf(']')
            bundle.substring(start, end)
        }
}
