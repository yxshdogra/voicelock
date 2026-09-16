package com.houseoftech.voicelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the detector with synthetic 16 kHz PCM: a quiet floor with transient
 * frames injected at chosen times. Each frame is 512 samples = 32 ms, so a
 * spike "at 500 ms" is frame index 500/32 ≈ 15.
 */
class ClapDetectorTest {

    private val frameLen = 512
    private val frameMs = frameLen * 1000L / 16_000  // 32

    private fun quiet(rms: Int = 100): ShortArray = ShortArray(frameLen) { i -> (if (i % 2 == 0) rms else -rms).toShort() }
    private fun bang(rms: Int = 20_000): ShortArray = ShortArray(frameLen) { i -> (if (i % 2 == 0) rms else -rms).toShort() }
    private fun bang(rms: Double): ShortArray = bang(rms.toInt())

    /** Feeds [totalMs] of quiet audio with bangs at [bangsAtMs]; returns frame indices that fired. */
    private fun run(det: ClapDetector, totalMs: Long, bangsAtMs: List<Long>): List<Long> {
        val fired = mutableListOf<Long>()
        val bangFrames = bangsAtMs.map { it / frameMs }.toSet()
        var t = 0L
        var idx = 0L
        while (t <= totalMs) {
            val frame = if (idx in bangFrames) bang() else quiet()
            if (det.onFrame(frame) != null) fired += t
            t += frameMs; idx++
        }
        return fired
    }

    @Test
    fun `two claps 500ms apart fire once, on the second`() {
        val fired = run(ClapDetector(), totalMs = 3_000, bangsAtMs = listOf(1_500, 2_000))
        assertEquals(1, fired.size)
        // Fires on the frame carrying the SECOND clap (2000 ms), not the first.
        assertTrue("fired at ${fired[0]}", fired[0] in 1_984..2_016)
    }

    @Test
    fun `two claps 900ms apart do not fire`() {
        // Outside the 300-800 ms window: reads as two separate single bangs.
        val fired = run(ClapDetector(), totalMs = 3_500, bangsAtMs = listOf(1_500, 2_400))
        assertEquals(emptyList<Long>(), fired)
    }

    @Test
    fun `a single bang never fires`() {
        // A door slam.
        val fired = run(ClapDetector(), totalMs = 3_000, bangsAtMs = listOf(1_500))
        assertEquals(emptyList<Long>(), fired)
    }

    @Test
    fun `keyboard clatter under 300ms apart does not fire`() {
        // Three transients 96 ms apart: inside the refractory / below the min gap.
        val fired = run(ClapDetector(), totalMs = 3_000, bangsAtMs = listOf(1_500, 1_596, 1_692))
        assertEquals(emptyList<Long>(), fired)
    }

    @Test
    fun `nothing fires during warm-up even on a loud first frame`() {
        // Without warm-up the floor would seed at ~0 and the first quiet frame
        // would read as a 40 dB spike. A bang on frame 0 must not fire either.
        val det = ClapDetector()
        assertNull(det.onFrame(bang()))
        repeat(40) { assertNull(det.onFrame(quiet())) }
    }

    @Test
    fun `each spike records its dB for tuning`() {
        val det = ClapDetector()
        run(det, totalMs = 2_000, bangsAtMs = listOf(1_500))
        val spike = det.lastSpike
        assertNotNull(spike)
        assertTrue("db=${spike!!.db}", spike.db > 12.0)
        assertTrue("rms=${spike.rms}", spike.rms > 10_000)
    }

    /** Feeds quiet audio with SUSTAINED loud runs (speech-like) at the given starts. */
    private fun runSustained(det: ClapDetector, totalMs: Long, startsMs: List<Long>, lenFrames: Int): List<Long> {
        val fired = mutableListOf<Long>()
        val loud = startsMs.flatMap { s -> (0 until lenFrames).map { s / frameMs + it } }.toSet()
        var t = 0L
        var idx = 0L
        while (t <= totalMs) {
            val frame = if (idx in loud) bang() else quiet()
            if (det.onFrame(frame) != null) fired += t
            t += frameMs; idx++
        }
        return fired
    }

    @Test
    fun `two sustained words 500ms apart do not fire`() {
        // The device test caught this: talking set off the alarm. Two spoken
        // words are two loud RUNS, not two impulses.
        // 8 frames = ~256 ms, a realistic word. This length matters: the decay
        // window must be SHORTER than a spoken word (or the word's trailing
        // quiet reads as decay) and LONGER than a clap's tail. Both numbers are
        // provisional until the B2c envelope capture measures them on device.
        val fired = runSustained(ClapDetector(), totalMs = 3_000, startsMs = listOf(1_500, 2_000), lenFrames = 8)
        assertEquals(emptyList<Long>(), fired)
    }

    @Test
    fun `a sustained burst never registers a spike at all`() {
        val det = ClapDetector()
        runSustained(det, totalMs = 2_500, startsMs = listOf(1_500), lenFrames = 8)
        // Not merely "did not fire" -- it must not even count as one clap, or it
        // would pair with a later genuine clap.
        assertNull(det.lastSpike)
    }

    @Test
    fun `an impulse still registers next to sustained noise`() {
        // Guard against over-correcting: a real clap must survive the new test.
        val det = ClapDetector()
        run(det, totalMs = 2_000, bangsAtMs = listOf(1_500))
        assertNotNull(det.lastSpike)
    }

    /**
     * Replays a REAL envelope measured on the device: a quiet floor, then frames
     * whose RMS follows [curve] (multiples of [peak]). Synthetic bangs were what
     * let two wrong fixes pass their tests and fail on the phone, so the
     * discriminating cases below are driven by captured curves instead.
     */
    private fun runCurve(det: ClapDetector, curve: List<Double>, peak: Int = 6_000, leadMs: Long = 1_500): Boolean {
        var idx = 0L
        var fired = false
        val start = leadMs / frameMs
        val total = start + curve.size + 20
        while (idx < total) {
            val frame = when {
                idx < start -> quiet()
                idx - start < curve.size -> bang((peak * curve[(idx - start).toInt()]).toInt())
                else -> quiet()
            }
            if (det.onFrame(frame) != null) fired = true
            idx++
        }
        return fired
    }

    @Test
    fun `measured speech curve that rises after onset never registers`() {
        // Captured on device: [1.0, 1.44, 1.43, 0.99, 0.48]. It RISES to 1.44x
        // and then falls below 0.5x, so a decay-only test accepted it -- this is
        // the curve that rang the alarm while the user was talking.
        val det = ClapDetector()
        runCurve(det, listOf(1.0, 1.44, 1.43, 0.99, 0.48))
        assertNull("a rising envelope is a voice, not a clap", det.lastSpike)
    }

    @Test
    fun `measured sustained speech curve never registers`() {
        val det = ClapDetector()
        runCurve(det, listOf(1.0, 1.63, 1.70, 1.82, 1.73))
        assertNull(det.lastSpike)
    }

    @Test
    fun `measured clap curve registers`() {
        // Captured on device: a clean collapse, peak at the onset frame.
        val det = ClapDetector()
        runCurve(det, listOf(1.0, 0.68, 0.37))
        assertNotNull("a monotone collapse is a clap", det.lastSpike)
    }

    @Test
    fun `two measured clap curves 500ms apart fire`() {
        // End to end on real shapes: the gesture must still work.
        val det = ClapDetector()
        var fired = false
        var idx = 0L
        val curve = listOf(1.0, 0.68, 0.37)
        val firstAt = 1_500L / frameMs
        val secondAt = 2_000L / frameMs
        while (idx < secondAt + 20) {
            val inFirst = idx - firstAt
            val inSecond = idx - secondAt
            val frame = when {
                inFirst in 0 until curve.size.toLong() -> bang((6_000 * curve[inFirst.toInt()]).toInt())
                inSecond in 0 until curve.size.toLong() -> bang((6_000 * curve[inSecond.toInt()]).toInt())
                else -> quiet()
            }
            if (det.onFrame(frame) != null) fired = true
            idx++
        }
        assertTrue("two real clap envelopes 500ms apart must fire", fired)
    }

    @Test
    fun `a quiet speech onset is too soft to be a clap`() {
        // Measured on device: this exact shape at rms 666 rang the alarm once the
        // shape tests were in. Speech onsets sit at rms 136-929, claps at 3584+.
        val det = ClapDetector()
        runCurve(det, listOf(1.0, 0.68, 0.37), peak = 666)
        assertNull("rms 666 is speech, not a clap", det.lastSpike)
    }

    @Test
    fun `a loud clap of the same shape does register`() {
        // Same envelope, real clap loudness: the shape is fine, energy decides.
        val det = ClapDetector()
        runCurve(det, listOf(1.0, 0.68, 0.37), peak = 5_000)
        assertNotNull("rms 5000 with a clean decay is a clap", det.lastSpike)
    }

    @Test
    fun `a loud room raises the bar rather than firing`() {
        // Continuous rms 5000 is loud but steady: no transient, no trigger.
        val det = ClapDetector()
        var fired = 0
        repeat(200) { if (det.onFrame(quiet(rms = 5_000)) != null) fired++ }
        assertEquals(0, fired)
    }
}
