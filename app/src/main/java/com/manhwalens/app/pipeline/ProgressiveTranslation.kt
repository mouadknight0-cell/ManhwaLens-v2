package com.manhwalens.app.pipeline

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Bounded workers, one request per exact utterance, serialized progressive delivery. */
suspend fun progressiveTranslation(
    regions: List<TextRegion>,
    concurrency: Int = 2,
    translate: suspend (TextRegion) -> TranslatedBlock,
    onReady: suspend (List<TranslatedBlock>) -> Unit,
    // Callers with position-dependent validation/context must include those inputs.
    identity: (TextRegion) -> Any = { it.text.trim() },
): List<TranslatedBlock> = coroutineScope {
    require(concurrency > 0)
    val groups = regions.withIndex().groupBy { identity(it.value) }.values.toList()
    val jobs = Channel<List<IndexedValue<TextRegion>>>(Channel.UNLIMITED)
    groups.forEach { jobs.trySend(it) }
    jobs.close()
    val results = Channel<List<Pair<Int, TranslatedBlock>>>(concurrency)
    val workers = List(minOf(concurrency, groups.size)) {
        launch {
            for (group in jobs) {
                val translated = translate(group.first().value)
                results.send(group.map { it.index to translated.copy(region = it.value) })
            }
        }
    }
    launch { workers.forEach { it.join() }; results.close() }
    val completed = sortedMapOf<Int, TranslatedBlock>()
    for (batch in results) {
        batch.forEach { (index, block) -> completed[index] = block }
        onReady(completed.values.toList())
    }
    completed.values.toList()
}
