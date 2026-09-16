package com.houseoftech.voicelock

/**
 * A wake-word / keyword-spotting engine, behind one seam so the rest of the app
 * never depends on which one is running.
 *
 * [ListenService] owns the single AudioRecord and hands every 16 kHz mono PCM16
 * frame to [accept]; the return value is the matched keyword's index, or a
 * negative number for "no match this frame". The service turns a match into a
 * [Trigger.KeywordDetected], so an engine needs no app dependencies of its own.
 *
 * This exists to make the Porcupine -> openWakeWord move a one-line factory
 * swap: an engine may consume the loop's frame size directly (Porcupine's fixed
 * 512@16 kHz) or accumulate frames into its own window (openWakeWord's 80 ms
 * chunks). The loop does not care.
 */
interface KeywordEngine {
    /** Feed one frame; returns the matched keyword index, or a negative for none. */
    fun accept(frame: ShortArray): Int

    /** Release native resources. Safe to call once, at teardown. */
    fun close()
}
