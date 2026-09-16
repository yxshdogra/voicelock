package com.houseoftech.voicelock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * [KeywordEngine] backed by Vosk (Apache-2.0), run as a restricted-grammar
 * phrase spotter rather than a full transcriber.
 *
 * The grammar `["<phrase>", "[unk]"]` collapses Vosk's language model to a
 * two-way decision -- the phrase, or "unknown" -- which is both cheaper and more
 * accurate for a fixed wake phrase than transcribing everything and string
 * matching. The model (`vosk-model-small-en-in-0.4`, Indian-English) ships in
 * assets and is unpacked to internal storage once, since Vosk wants a real
 * directory path.
 *
 * Chosen over Porcupine (paid) and openWakeWord (its distributed models are
 * CC BY-NC-SA, non-commercial). The unlock phrase is just a grammar string, so
 * there is no model to train and no per-account key -- changing it is one line.
 */
class VoskEngine private constructor(
    private val model: Model,
    private val recognizer: Recognizer,
    private val phrase: String,
) : KeywordEngine {

    /** Fire once per utterance: latched on a match, cleared when the utterance ends. */
    private var firedThisUtterance = false
    private var lastFireMs = 0L

    override var lastMatchText: String? = null
        private set

    /** A completed non-matching utterance awaiting a log row; see [drainHeard]. */
    private var pendingHeard: String? = null

    override fun drainHeard(): String? {
        val heard = pendingHeard
        pendingHeard = null
        return heard
    }

    override fun accept(frame: ShortArray): Int {
        val utteranceEnded = try {
            recognizer.acceptWaveForm(frame, frame.size)
        } catch (e: Exception) {
            Log.w(TAG, "acceptWaveForm", e)
            return -1
        }

        val json = if (utteranceEnded) recognizer.result else recognizer.partialResult
        val text = extractRecognisedText(json).orEmpty()
        val matched = phraseMatched(json, phrase)
        var index = -1
        if (matched && !firedThisUtterance) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFireMs >= REFRACTORY_MS) {
                firedThisUtterance = true
                lastFireMs = now
                lastMatchText = text
                index = 0
            }
        }
        if (utteranceEnded) {
            // Something was said and understood, but it was not the phrase: the
            // near-miss record that makes the M0 false-positive rate readable.
            // `!matched` matters: a match fires on a PARTIAL, so by the time the
            // utterance ends the latch has made index -1 even though this text
            // IS the phrase -- logging that as a near-miss corrupts the score.
            if (!matched && text.isNotBlank()) pendingHeard = text
            // A finished utterance frees the latch so the next one can fire; the
            // refractory still debounces a match that spans the boundary.
            firedThisUtterance = false
        }
        return index
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "recognizer close", e)
        }
        try {
            model.close()
        } catch (e: Exception) {
            Log.w(TAG, "model close", e)
        }
    }

    companion object {
        private const val TAG = "VoskEngine"

        /** Asset dir holding the unpacked Vosk model (see .gitignore + README). */
        private const val MODEL_DIR = "model-en-in"

        /**
         * The wake phrase, as a grammar string. Every word must be in the model
         * vocabulary; these common words are. Changing the phrase is this line
         * plus retesting -- no retraining.
         */
        const val DEFAULT_PHRASE = "unlock my phone"

        /** Ignore repeat matches within this window, across utterance boundaries. */
        private const val REFRACTORY_MS = 2_000L

        /**
         * Builds the engine, or returns null when the model asset is missing (or
         * init fails). Null degrades gracefully: the mic loop + clap detector
         * still run, which is all Milestones 1-3 need. Call OFF the main thread --
         * unpacking + model load take hundreds of ms to seconds.
         */
        fun buildOrNull(ctx: Context): VoskEngine? {
            val path = ensureModelUnpacked(ctx)
            if (path == null) {
                SpikeLog.service(ctx, "start", "no Vosk model (assets/$MODEL_DIR): mic loop + clap detector only (M0 needs the model)")
                return null
            }
            return try {
                val model = Model(path)
                val grammar = "[\"$DEFAULT_PHRASE\", \"[unk]\"]"
                val recognizer = Recognizer(model, ListenService.SAMPLE_RATE.toFloat(), grammar)
                SpikeLog.service(ctx, "start", "vosk en-IN grammar spotter; phrase=\"$DEFAULT_PHRASE\"")
                VoskEngine(model, recognizer, DEFAULT_PHRASE)
            } catch (e: Exception) {
                SpikeLog.service(ctx, "error", "vosk init: ${e.message}")
                Log.e(TAG, "vosk init failed; continuing without it", e)
                null
            }
        }

        /**
         * True when a Vosk result/partial JSON's recognised text contains the
         * phrase. Pure (no Vosk, no Android) so it is unit-tested directly.
         * Vosk emits `{"partial" : "..."}` mid-utterance and `{"text" : "..."}`
         * at the end; both are handled.
         */
        internal fun phraseMatched(resultJson: String, phrase: String): Boolean {
            val text = extractRecognisedText(resultJson) ?: return false
            return normalise(text).contains(normalise(phrase))
        }

        private val TEXT_FIELD = Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"")

        private fun extractRecognisedText(json: String): String? =
            TEXT_FIELD.find(json)?.groupValues?.get(1)

        private fun normalise(s: String): String =
            s.lowercase().trim().replace(Regex("\\s+"), " ")

        /** Copies the asset model dir to filesDir once; returns the path or null. */
        private fun ensureModelUnpacked(ctx: Context): String? {
            val dest = File(ctx.filesDir, MODEL_DIR)
            val marker = File(dest, ".unpacked")
            if (marker.exists()) return dest.absolutePath
            return try {
                copyAssetDir(ctx, MODEL_DIR, dest)
                marker.writeText("ok")
                dest.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "model unpack failed", e)
                dest.deleteRecursively()
                null
            }
        }

        private fun copyAssetDir(ctx: Context, assetPath: String, dest: File) {
            val children = ctx.assets.list(assetPath) ?: emptyArray()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            } else {
                dest.mkdirs()
                for (child in children) copyAssetDir(ctx, "$assetPath/$child", File(dest, child))
            }
        }
    }
}
