package com.example.opendash.dash.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashWallpaperPlaybackPolicyTest {
    @Test
    fun videoWallpaperIsCappedAtEightFramesPerSecond() {
        assertEquals(8, DashWallpaperPlaybackPolicy.MAX_VIDEO_FPS)
        assertEquals(125L, DashWallpaperPlaybackPolicy.MIN_VIDEO_FRAME_INTERVAL_MS)
        assertTrue(DashWallpaperPlaybackPolicy.shouldDecodeVideoFrame(0L, 1_000L))
        assertFalse(DashWallpaperPlaybackPolicy.shouldDecodeVideoFrame(1_000L, 1_124L))
        assertTrue(DashWallpaperPlaybackPolicy.shouldDecodeVideoFrame(1_000L, 1_125L))
    }
}
