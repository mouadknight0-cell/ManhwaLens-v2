package com.manhwalens.app.pipeline

/** Frame-local immutable inputs. Never share a result on text alone or across frames.
 * All inputs that affect translateBlock/validation belong in Key. Glossary and engine
 * are fixed for the entire frame by the caller. Region geometry stays on each result.
 */
object FrameTranslationPlan {
    data class Input(val region: TextRegion, val kind: RegionKind)
    data class Key(val text: String, val issue: String?, val previous: String?, val preserveSoundEffect: Boolean, val lowConfidence: Boolean = false)
    data class Work(val input: Input, val key: Key)

    fun requiresModel(work: List<Work>): Boolean = work.any {
        it.key.issue == null && !it.key.preserveSoundEffect && !OcrInputQuality.isNumericContent(it.key.text)
    }

    fun build(inputs: List<Input>, contentBottom: Int): List<Work> {
        val issues = inputs.map { OcrInputQuality.issue(it.region, contentBottom) }
        return inputs.mapIndexed { index, input ->
            val previous = inputs.getOrNull(index - 1)?.takeIf {
                it.kind != RegionKind.SOUND_EFFECT && issues[index - 1] == null
            }?.region?.text
            Work(input, Key(input.region.text, issues[index], previous, input.kind == RegionKind.SOUND_EFFECT, input.region.confidence.isFinite() && input.region.confidence < .5f))
        }
    }
}
