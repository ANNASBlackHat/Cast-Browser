package com.castbrowser.app.detect

import org.junit.Assert.*
import org.junit.Test

class StreamClassifierTest {

    @Test
    fun testClassifyM3u8() {
        val type = StreamClassifier.classifyStreamType("https://video.cdn.com/master.m3u8?token=xyz")
        assertEquals(StreamType.HLS, type)
    }

    @Test
    fun testClassifyMp4() {
        val type = StreamClassifier.classifyStreamType("https://cdn.site.com/media/clip.mp4")
        assertEquals(StreamType.MP4, type)
    }

    @Test
    fun testClassifyDash() {
        val type = StreamClassifier.classifyStreamType("https://stream.server.org/live/manifest.mpd")
        assertEquals(StreamType.DASH, type)
    }

    @Test
    fun testIdentifyMediaSegment() {
        assertTrue(StreamClassifier.isMediaSegment("https://cdn.com/hls/segment_001.ts"))
        assertTrue(StreamClassifier.isMediaSegment("https://cdn.com/dash/chunk_01.m4s"))
        assertFalse(StreamClassifier.isMediaSegment("https://cdn.com/master.m3u8"))
        assertFalse(StreamClassifier.isMediaSegment("https://cdn.com/video.mp4"))
    }

    @Test
    fun testFilterOutAds() {
        assertTrue(StreamClassifier.isLikelyAdOrPixel("https://doubleclick.net/ad_tag_stream"))
        assertTrue(StreamClassifier.isLikelyAdOrPixel("https://stats.com/pixel.gif?track=1"))
        assertFalse(StreamClassifier.isLikelyAdOrPixel("https://cdn.movie.com/stream/playlist.m3u8"))
    }
}
