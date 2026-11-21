package com.manhwalens.app.overlay

/** No incomplete/stale request may attach a mask. Roll back every attached window
 * if attachment fails or request identity changes during commit.
 */
object ReplacementCommit {
    data class Owner(val result:String,val scene:Long)
    enum class Coverage { NONE, PARTIAL, COMPLETE }
    fun coverage(owner:Owner,current:Owner?,expected:Set<String>,visible:Map<String,Owner>,final:Boolean,failed:Boolean):Coverage {
        if(owner!=current || expected.isEmpty())return Coverage.NONE
        val n=expected.count { visible[it]==owner }
        return when { n==0 -> Coverage.NONE; final && !failed && n==expected.size -> Coverage.COMPLETE; else -> Coverage.PARTIAL }
    }
    fun eligible(id: String, activeId: String?, complete: Boolean) = id == activeId
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
