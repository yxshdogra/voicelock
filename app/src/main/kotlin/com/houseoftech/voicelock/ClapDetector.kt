package com.houseoftech.voicelock

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Double-clap detection on raw PCM frames. Pure Kotlin, no Android imports, so
 * it is unit-tested with synthetic audio and shares the recogniser's frames at
 * runtime with no second AudioRecord.
 *
 * A clap is a short broadband transient: one frame whose RMS jumps well above
 * the recent noise floor. Two of them the right distance apart is the trigger.
 * Everything else is designed to say no:
 *  - A door slam is one bang with no partner in the window -> no trigger.
 *  - Keyboard clatter is denser than [doubleClapMinMs]; the refractory period
 *    absorbs it, and any survivors land outside the window.
 *  - The floor tracks the room slowly, so a loud room raises the bar rather
 *    than firing on every sound.
 *
 * Thresholds are STARTING values. Each spike is reported with its dB so the
 * real numbers can be tuned from a room full of real claps, slams and typing.
 */
class ClapDetector(
    private val frameLength: Int = 512,
    private val sampleRate: Int = 16_000,
    /** How far above the noise floor a frame must be to count as a transient. */
    private val spikeThresholdDb: Double = 12.0,
    /** Ignore further spikes this long after one -- debounces a single bang's tail. */
    private val refractoryMs: Long = 150,
    private val doubleClapMinMs: Long = 300,
    private val doubleClapMaxMs: Long = 800,
    /** Exponential smoothing for the noise floor; small = slow to adapt. */
    private val noiseFloorAlpha: Double = 0.05,
    /** Frames to observe before any spike may fire, so the floor has settled. */
    private val warmupFrames: Int = 30,
) {
    /** What the detector saw on the frame that produced a spike; for tuning. */
    data class Spike(val atMs: Long, val db: Double, val rms: Double)

    private var noiseFloor = 0.0
    private var frameIdx = 0L
    // Not Long.MIN_VALUE: `ms - lastSpikeMs` would overflow negative on the very
    // first spike and read as "inside the refractory period", swallowing it.
    private var lastSpikeMs = -1_000_000L
    private var firstClapMs: Long? = null

    /** The most recent spike, whether or not it completed a double clap. */
    var lastSpike: Spike? = null
        private set

    private val frameMs get() = frameLength * 1000L / sampleRate

    /**
     * Feed one frame. Returns [Trigger.DoubleClap] on the frame that completes a
     * pair, otherwise null. Never allocates on the hot path beyond [Spike].
     */
    fun onFrame(frame: ShortArray): Trigger.DoubleClap? {
        val ms = frameIdx * frameMs
        frameIdx++

        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size)

        // Warm-up: seed the floor from the first frames and stay silent.
        if (frameIdx <= warmupFrames) {
            noiseFloor = if (frameIdx == 1L) rms else lerp(noiseFloor, rms)
            return null
        }

        val db = 20 * log10((rms + 1.0) / (noiseFloor + 1.0))
        val isSpike = db > spikeThresholdDb

        if (!isSpike) {
            // Only quiet frames move the floor, so a clap does not raise the bar
            // against its own partner.
            noiseFloor = lerp(noiseFloor, rms)
            return null
        }
        if (ms - lastSpikeMs <= refractoryMs) return null

        lastSpikeMs = ms
        lastSpike = Spike(ms, db, rms)

        val first = firstClapMs
        return when {
            first == null -> { firstClapMs = ms; null }
            ms - first in doubleClapMinMs..doubleClapMaxMs -> { firstClapMs = null; Trigger.DoubleClap }
            // Too late (or too early past refractory): this spike starts a new pair.
            else -> { firstClapMs = ms; null }
        }
    }

    private fun lerp(current: Double, sample: Double) = current * (1 - noiseFloorAlpha) + sample * noiseFloorAlpha
}
