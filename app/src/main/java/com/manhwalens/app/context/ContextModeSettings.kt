package com.manhwalens.app.context

/**
 * Where the active [ContextMode] comes from, kept behind an interface so the pipeline never reaches
 * into settings.
 *
 * The default is [ContextMode.A_BASELINE] on every path, including a missing or unreadable
 * preference: a corrupted setting must not silently enable a translation change. Baseline is also
 * the honest default while no real page has been through the engine — a default that has not been
 * validated is a default that has been guessed.
 */
interface ContextModeSource {
    fun current(): ContextMode

    /**
     * The production default until a UI exists to change the mode.
     *
     * Shadow rather than baseline: the engine runs and records, and the reader is unaffected. That
     * is what makes evidence collection possible without asking anyone to trust an unvalidated
     * change. Gated mode must be chosen deliberately, from the capture screen.
     */
    object Fixed : ContextModeSource {
        override fun current(): ContextMode = ContextMode.A_BASELINE
    }
}
