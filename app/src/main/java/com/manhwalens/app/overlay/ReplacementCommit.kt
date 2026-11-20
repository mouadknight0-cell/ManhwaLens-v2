package com.manhwalens.app.overlay

/** No incomplete/stale request may attach a mask. Roll back every attached window
 * if attachment fails or request identity changes during commit.
 */
object ReplacementCommit {
    fun eligible(id: String, activeId: String?, complete: Boolean) = complete && id == activeId
    fun <T> commit(items: List<T>, current: () -> Boolean, attach: (T) -> Unit, remove: (T) -> Unit): Boolean {
        if (!current()) return false
        val attempted = mutableListOf<T>()
        return try {
            for (item in items) {
                check(current())
                attempted.add(item)
                attach(item)
            }
            check(current())
            true
        } catch (failure: Exception) {
            attempted.asReversed().forEach { runCatching { remove(it) } }
            false
        }
    }
}
