package com.castbrowser.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockEngineTest {

    @Test
    fun testBlockedAdDomains() {
        assertTrue(AdBlockEngine.isAdOrTracker("https://doubleclick.net/pagead/ads?client=ca-pub-123"))
        assertTrue(AdBlockEngine.isAdOrTracker("https://adservice.google.com/adsid/google/ui"))
        assertTrue(AdBlockEngine.isAdOrTracker("https://subdomain.popads.net/serve.js"))
    }

    @Test
    fun testAllowedContentDomains() {
        assertFalse(AdBlockEngine.isAdOrTracker("https://example.com/movie/stream"))
        assertFalse(AdBlockEngine.isAdOrTracker("https://myvideohost.net/master.m3u8"))
        assertFalse(AdBlockEngine.isAdOrTracker("https://en.wikipedia.org/wiki/Streaming"))
    }
}
