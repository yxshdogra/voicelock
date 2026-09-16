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

    @Test
    fun `a loud room raises the bar rather than firing`() {
        // Continuous rms 5000 is loud but steady: no transient, no trigger.
        val det = ClapDetector()
        var fired = 0
        repeat(200) { if (det.onFrame(quiet(rms = 5_000)) != null) fired++ }
        assertEquals(0, fired)
    }
}
