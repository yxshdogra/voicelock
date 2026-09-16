package com.houseoftech.voicelock

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.util.Log
import java.io.File

/**
 * [KeywordEngine] backed by Picovoice Porcupine.
 *
 * Kept behind the [KeywordEngine] seam so it can be A/B'd against, and later
 * replaced by, the free openWakeWord engine without touching [ListenService] or
 * anything downstream. Porcupine needs an AccessKey even to initialise, so
 * [buildOrNull] returns null when none is configured; the service then runs the
 * mic loop + clap detector alone, which is all Milestones 1-3 need.
 *
 * M0 sub-steps: with only an AccessKey it spots the built-in word "porcupine"
 * (pipeline check); if a trained `assets/keywords/custom.ppn` is present it uses
 * that instead (the real accuracy test).
 */
class PorcupineEngine private constructor(private val porcupine: Porcupine) : KeywordEngine {

    override fun accept(frame: ShortArray): Int =
        try {
            porcupine.process(frame)
        } catch (e: PorcupineException) {
            Log.w(TAG, "process", e)
            -1
        }

    override fun close() {
        try {
            porcupine.delete()
        } catch (e: PorcupineException) {
            Log.w(TAG, "teardown", e)
        }
    }

    companion object {
        private const val TAG = "PorcupineEngine"

        /**
         * Builds the engine, or returns null when there is no AccessKey (or init
         * fails). Logs which keyword path it took, for the M0 scoring trail.
         */
        fun buildOrNull(ctx: Context): PorcupineEngine? {
            val key = BuildConfig.PICOVOICE_ACCESS_KEY
            if (key.isBlank()) {
                SpikeLog.service(ctx, "start", "no PICOVOICE_ACCESS_KEY: mic loop + clap detector only (M0 needs the key)")
                return null
            }
            return try {
                val builder = Porcupine.Builder().setAccessKey(key)
                val custom = customKeywordPath(ctx)
                if (custom != null) {
                    builder.setKeywordPaths(arrayOf(custom))
                    SpikeLog.service(ctx, "start", "custom keyword: $custom")
                } else {
                    builder.setKeywords(arrayOf(Porcupine.BuiltInKeyword.PORCUPINE))
                    SpikeLog.service(ctx, "start", "built-in keyword: PORCUPINE (M0a); drop assets/keywords/custom.ppn for M0b")
                }
                val engine = builder.build(ctx)
                // The loop hardcodes Porcupine's fixed frame geometry so it can run
                // without the engine; make a mismatch loud rather than silent.
                check(engine.frameLength == ListenService.FRAME_LENGTH && engine.sampleRate == ListenService.SAMPLE_RATE) {
                    "Porcupine wants ${engine.frameLength}@${engine.sampleRate}, loop uses ${ListenService.FRAME_LENGTH}@${ListenService.SAMPLE_RATE}"
                }
                PorcupineEngine(engine)
            } catch (e: PorcupineException) {
                SpikeLog.service(ctx, "error", "porcupine init: ${e.message}")
                Log.e(TAG, "porcupine init failed; continuing without it", e)
                null
            }
        }

        /**
         * A custom .ppn shipped in assets is copied to internal storage once,
         * since Porcupine wants a real file path. Returns null when there is no
         * custom keyword yet (M0a).
         */
        private fun customKeywordPath(ctx: Context): String? {
            val out = File(ctx.filesDir, "custom.ppn")
            if (out.exists()) return out.absolutePath
            return try {
                ctx.assets.open("keywords/custom.ppn").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }
}
