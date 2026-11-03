package com.miauto.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the canvas ordering rules and the re-negotiation decision.
 *
 * Both matter more than they look: the canvas is fixed at `init()` time, so getting the order
 * wrong makes the phone encode for a shape the screen does not have, and getting
 * [ScreenSizePolicy.needsRenegotiation] wrong either leaves a stale canvas in place forever or
 * tears the SDK down on every single connect.
 */
class ScreenSizePolicyTest {

    private val portrait = ScreenSizePolicy.PORTRAIT
    private val portraitReverse = ScreenSizePolicy.PORTRAIT_REVERSE
    private val landscape = ScreenSizePolicy.LANDSCAPE
    private val landscapeReverse = ScreenSizePolicy.LANDSCAPE_REVERSE
    private val system = 0
    private val auto = 1

    @Test
    fun `landscape setting puts the long side first`() {
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1080, 1920, landscape))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, landscape))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, landscapeReverse))
    }

    @Test
    fun `portrait setting puts the short side first`() {
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1920, 1080, portrait))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, portrait))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, portraitReverse))
    }

    @Test
    fun `system and auto pass the window through untouched`() {
        // This is the case a head unit in portrait (or a tablet) used to get wrong: the old code
        // reported long-side-first unconditionally, so the picture came out stretched.
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, system))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, system))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, auto))
    }

    @Test
    fun `invalid measurements are reported as zero-zero so callers retry`() {
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(0, 1080, landscape))
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(1920, 0, landscape))
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(-1, -1, system))
    }

    @Test
    fun `no renegotiation when the canvas is unchanged`() {
        assertFalse(ScreenSizePolicy.needsRenegotiation(1920, 1080, 1920, 1080))
    }

    @Test
    fun `renegotiation when the canvas changed`() {
        assertTrue(ScreenSizePolicy.needsRenegotiation(1080, 1920, 1920, 1080))
        assertTrue(ScreenSizePolicy.needsRenegotiation(1280, 720, 1920, 1080))
    }

    @Test
    fun `an unmeasured canvas never triggers a teardown`() {
        // Without a valid current size, re-init would only produce the 1x1 fallback canvas —
        // strictly worse than re-advertising with the old one.
        assertFalse(ScreenSizePolicy.needsRenegotiation(0, 0, 1920, 1080))
        assertFalse(ScreenSizePolicy.needsRenegotiation(1920, 0, 1920, 1080))
    }

    @Test
    fun `an init canvas that was never recorded is not trusted`() {
        // First launch records 0 only if the measurement failed at init time; that config is junk.
        assertTrue(ScreenSizePolicy.needsRenegotiation(1920, 1080, 0, 0))
    }
}
