package com.castbrowser.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaXmlHelperTest {

    @Test
    fun testBuildSetAvTransportUriXml() {
        val xml = DlnaXmlHelper.buildSetAvTransportUriXml(
            mediaUri = "http://192.168.1.10:8888/video.mp4",
            title = "Episode 1 & 2 <Special>",
            mimeType = "video/mp4"
        )
        assertTrue(xml.contains("<CurrentURI>http://192.168.1.10:8888/video.mp4</CurrentURI>"))
        assertTrue(xml.contains("Episode 1 &amp; 2 &lt;Special&gt;"))
        assertTrue(xml.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
    }

    @Test
    fun testRelTimeFormattingAndParsing() {
        val seconds = 3665L // 01:01:05
        val formatted = DlnaXmlHelper.formatRelTime(seconds)
        assertEquals("01:01:05", formatted)

        val parsed = DlnaXmlHelper.parseRelTimeToSeconds("01:01:05.120")
        assertEquals(3665L, parsed)
    }

    @Test
    fun testBuildSeekXml() {
        val seekXml = DlnaXmlHelper.buildSeekXml(120) // 2 minutes
        assertTrue(seekXml.contains("<Target>00:02:00</Target>"))
    }
}
