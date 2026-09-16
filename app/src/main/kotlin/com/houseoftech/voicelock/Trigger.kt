package com.houseoftech.voicelock

/**
 * Everything that can make the app act, from any source.
 *
 * The recogniser (Porcupine), the clap detector and the debug panel all produce
 * these; nothing downstream knows or cares which. That is the point: every
 * mechanic in Milestones 1-3 is exercised on the emulator with a button long
 * before Milestone 0 proves the voice engine.
 */
sealed class Trigger {
    /** A trained phrase matched. [index] is the keyword's position in the list handed to Porcupine. */
    data class KeywordDetected(val index: Int) : Trigger()

    /** Two claps within the double-clap window. */
    data object DoubleClap : Trigger()

    /** Debug panel: behave as if the lock phrase was heard. */
    data object DebugLock : Trigger()

    /** The unlock phrase (or a debug button): take down our overlay. */
    data object DismissOverlay : Trigger()

    /** Debug panel: behave as if the find-phone phrase was heard. */
    data object DebugFindPhone : Trigger()

    /** Any touch, the "Found it" button, or the timeout. */
    data object StopFindPhone : Trigger()
}
