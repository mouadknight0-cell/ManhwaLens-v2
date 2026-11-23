package com.manhwalens.app.engine

/**
 * Builds the chat message sent to Qwen3 for a whole screen of dialogue.
 *
 * The prompt is the entire reason this engine exists. Translating bubble-by-bubble throws away the
 * relationships that make manhwa dialogue readable — a pronoun whose referent is in the previous
 * bubble, a threat that only lands because of the line before it, sarcasm that inverts a literal
 * reading. So the prompt:
 *
 *  - presents every bubble of the screen numbered by its **stable ID**, never by array position;
 *  - supplies optional rolling context from earlier on the same page, clearly separated;
 *  - states the translation contract once, in the system message;
 *  - demands a strict JSON object and nothing else, so the reply is machine-checkable.
 *
 * It also constrains the model on the things a 0.6B model gets wrong most often: omitting lines,
 * emitting commentary, and drifting into formal Arabic for casual speech.
 */
object QwenPrompt {

    /** Kept short: a 0.6B model spends attention budget on every sentence it must also read. */
    val SYSTEM: String = """
        You translate English manhwa dialogue into natural Arabic.

        Rules:
        - Translate every numbered line. Return exactly one translation per ID. Never merge, split, reorder, or omit an ID.
        - Read all lines together first. Resolve pronouns and references from the surrounding lines when the dialogue supports it.
        - Preserve meaning, emotional tone, sarcasm, threats, humour and dramatic impact. Translate intent, not words.
        - Use natural, conversational Arabic. Do not use formal or classical Arabic for casual speech.
        - Keep character names and established terms as they are, written in Arabic letters.
        - Never invent story details, names, or events that are not in the text.
        - Never add explanations, notes, romanisation, or alternative translations.
        - OCR text may contain punctuation or spacing errors. Translate the intended line.

        Reply with JSON only, in exactly this shape:
        {"translations":[{"id":1,"text":"..."}]}
    """.trimIndent()

    /**
     * Renders the user turn for a batch.
     *
     * Context is emitted as plain pairs under a heading that tells the model what it is for, and is
     * explicitly marked as *earlier* dialogue so it is never mistaken for lines to translate.
     */
    fun userMessage(batch: TranslationBatch): String = buildString {
        if (batch.context.isNotEmpty()) {
            appendLine("Earlier dialogue on this page, for reference only. Do NOT translate these:")
            batch.context.forEach { turn ->
                appendLine("EN: ${sanitize(turn.source)}")
                appendLine("AR: ${sanitize(turn.translation)}")
            }
            appendLine()
        }
        appendLine("Translate these ${batch.items.size} numbered lines into Arabic.")
        batch.items.forEach { item ->
            appendLine("${item.id}: ${sanitize(item.text)}")
        }
        append("Return JSON only.")
    }

    /**
     * Replaces characters that would break the flat "ID: text" structure or impersonate the chat
     * template. Newlines become spaces so a multi-line bubble stays one numbered line; the template
     * markers are stripped so OCR text cannot inject a role change.
     */
    fun sanitize(text: String): String = text
        .replace("<|im_start|>", " ")
        .replace("<|im_end|>", " ")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Approximate prompt cost in tokens, used to keep a batch inside the model's context window.
     *
     * English runs ~1.3 tokens/word and Arabic output roughly 2–3× that in tokens; the estimate is
     * deliberately pessimistic because overflowing the context silently truncates the prompt, which
     * would look like "the model forgot line 5".
     */
    fun estimateTokens(batch: TranslationBatch): Int {
        val promptWords = batch.items.sumOf { it.text.split(' ').size } +
            batch.context.sumOf { it.source.split(' ').size + it.translation.split(' ').size }
        return (promptWords * 4) + 256
    }
}
