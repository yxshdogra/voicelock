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
    /**
     * A clap is an IMPULSE: near-silence, one loud frame, near-silence. Speech is
     * sustained. The frame before a spike must be this much quieter than it
     * (attack) and the frame after must fall this far below it (decay); 0.5 is
     * -6 dB. Both are required, and both are needed: decay alone would still
     * accept the trailing edge of a spoken word, so two words would read as a
     * double clap -- which is exactly what the device test caught.
     */
    private val attackRatio: Double = 0.5,
    private val decayRatio: Double = 0.5,
    /**
     * How many frames the decay may take. Requiring the VERY NEXT frame to have
     * collapsed rejected every real clap on device: a clap spans several 32 ms
     * frames once mic AGC and room reverb are involved. Speech stays loud for
     * much longer, so a short window still separates them.
     */
    private val decayWindowFrames: Int = 4,
    /**
     * How far a later frame may exceed the onset before the candidate is
     * rejected. A clap's peak IS its onset: an impulse injects all its energy at
     * once, so energy can only fall afterwards. Speech keeps pumping energy in --
     * measured envelopes rose to 1.2x, 1.4x, even 3.2x after onset and then fell,
     * which passed a decay-only test and rang the alarm while the user talked.
     * This is the rule that separates them, read off real device envelopes.
     */
    private val riseTolerance: Double = 1.1,
    /**
     * Absolute energy floor for a clap, independent of the noise floor.
     * Measured on device: speech onsets land at rms 136-929, real claps at
     * 3584-7321. A quiet speech onset (rms 666) still paired into a false alarm
     * once the shape tests were in, so raw loudness is the remaining separator.
     *
     * This is deliberately ABSOLUTE rather than more dB-over-floor: dB drifts
     * with the room, which is why the same gesture measured 12-17 dB in one
     * session and 34-36 dB in another. A clap is physically loud, full stop.
     */
    private val minSpikeRms: Double = 2_000.0,
) {
    /** What the detector saw on the frame that produced a spike; for tuning. */
    data class Spike(val atMs: Long, val db: Double, val rms: Double)

    private var noiseFloor = 0.0
    private var frameIdx = 0L
    // Not Long.MIN_VALUE: `ms - lastSpikeMs` would overflow negative on the very
    // first spike and read as "inside the refractory period", swallowing it.
    private var lastSpikeMs = -1_000_000L
    private var firstClapMs: Long? = null
    /** Previous frame's energy, for the attack test. */
    private var prevRms = 0.0
    /** A spike candidate awaiting next frame's decay test; confirmed one frame late. */
    private var pending: Spike? = null

    /**
     * TEMPORARY tuning instrument (B2c). After a sharp loud onset, the next
     * frames' RMS are collected so the REAL decay envelope of a clap can be
     * compared against speech. Guessing these thresholds failed twice; this
     * measures them. Remove once the decay window is set from data.
     */
    private var envelope: MutableList<Double>? = null
    private var pendingFramesLeft = 0
    /** Drained by the caller and written to the log; null when none is ready. */
    var lastEnvelope: List<Double>? = null
        private set
    /** Whether the drained envelope was accepted as a transient. */
    var lastEnvelopeConfirmed: Boolean = false
        private set

    fun drainEnvelope(): List<Double>? {
        val e = lastEnvelope
        lastEnvelope = null
        return e
    }

    private fun finishEnvelope(confirmed: Boolean) {
        lastEnvelope = envelope?.toList()
        lastEnvelopeConfirmed = confirmed
        envelope = null
    }

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
            prevRms = rms
            return null
        }

        // Resolve the candidate held from last frame: a clap's energy collapses,
        // speech carries on. Timing uses the spike's ORIGINAL frame, so holding
        // it does not shift the double-clap window.
        var completed: Trigger.DoubleClap? = null
        pending?.let { held ->
            envelope?.add(rms)
            pendingFramesLeft--
            if (rms > held.rms * riseTolerance) {
                // Energy went UP after the onset: something is still being driven
                // (a voice), so this was never an impulse. Reject immediately --
                // waiting would let it "decay" at the end of the word and pass.
                finishEnvelope(confirmed = false)
                pending = null
            } else if (rms <= held.rms * decayRatio) {
                // Energy collapsed inside the window: a real transient.
                finishEnvelope(confirmed = true)
                pending = null
                completed = register(held)
            } else if (pendingFramesLeft <= 0) {
                // Still loud after the whole window: sustained sound, not a clap.
                finishEnvelope(confirmed = false)
                pending = null
            }
        }

        val db = 20 * log10((rms + 1.0) / (noiseFloor + 1.0))
        // Loud RELATIVE to the room and loud in ABSOLUTE terms. The first keeps a
        // noisy room from firing constantly; the second keeps quiet speech from
        // qualifying at all.
        val isSpike = db > spikeThresholdDb && rms >= minSpikeRms
        val sharpAttack = prevRms <= rms * attackRatio
        prevRms = rms

        if (!isSpike) {
            // Only quiet frames move the floor, so a clap does not raise the bar
            // against its own partner.
            noiseFloor = lerp(noiseFloor, rms)
            return completed
        }
        // Loud, but is it an impulse? Only a sharp onset is worth holding; a
        // frame continuing an already-loud sound is speech, not a clap.
        if (sharpAttack && pending == null) {
            pending = Spike(ms, db, rms)
            pendingFramesLeft = decayWindowFrames
            envelope = mutableListOf(rms)
        }
        return completed
    }

    /** A confirmed transient: apply the refractory and the double-clap pairing. */
    private fun register(spike: Spike): Trigger.DoubleClap? {
        if (spike.atMs - lastSpikeMs <= refractoryMs) return null

        lastSpikeMs = spike.atMs
        lastSpike = spike

        val first = firstClapMs
        return when {
            first == null -> { firstClapMs = spike.atMs; null }
            spike.atMs - first in doubleClapMinMs..doubleClapMaxMs -> { firstClapMs = null; Trigger.DoubleClap }
            // Too late (or too early past refractory): this spike starts a new pair.
            else -> { firstClapMs = spike.atMs; null }
        }
    }

    private fun lerp(current: Double, sample: Double) = current * (1 - noiseFloorAlpha) + sample * noiseFloorAlpha
}
