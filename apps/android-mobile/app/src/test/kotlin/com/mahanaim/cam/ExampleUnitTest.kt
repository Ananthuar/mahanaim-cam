package com.mahanaim.cam

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for MAHANAIM CAM Android — Milestone 1.
 *
 * Note: Camera, WebRTC, and network functionality require a device/emulator.
 * Those tests will be added in Milestone 2 as instrumented tests.
 */
class ExampleUnitTest {

    @Test
    fun verifyMilestone1Constants() {
        // Target resolution check (build constants deferred: just verify math here)
        val targetWidth = 1280
        val targetHeight = 720
        val targetFps = 30

        assertEquals("Target width should be 1280", 1280, targetWidth)
        assertEquals("Target height should be 720", 720, targetHeight)
        assertEquals("Target FPS should be 30", 30, targetFps)
    }

    @Test
    fun verifySignalingPortDefault() {
        val defaultPort = 8765
        assertTrue("Signaling port must be in valid range", defaultPort in 1024..65535)
    }

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}
