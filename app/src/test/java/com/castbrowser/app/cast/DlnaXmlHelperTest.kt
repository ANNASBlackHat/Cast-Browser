package com.castbrowser.app.cast

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class DlnaXmlHelperTest {

    @Test
    fun testBuildSetAvTransportUriXml() {
        val xml = DlnaXmlHelper.buildSetAvTransportUriXml(
            mediaUri = "http://192.168.1.10:8888/video.mp4",
            title = "Episode 1 & 2 <Special>",
            mimeType = "video/mp4"
        )
        assertThat(xml).contains("<CurrentURI>http://192.168.1.10:8888/video.mp4</CurrentURI>")
        // Title double-escaped inside CurrentURIMetaData
        assertThat(xml).contains("Episode 1 &amp;amp; 2 &amp;lt;Special&amp;gt;")
        assertThat(xml).contains("DLNA.ORG_OP=01")
    }

    @Test
    fun testBuildSetAvTransportUriXmlWithSubtitles() {
        val xml = DlnaXmlHelper.buildSetAvTransportUriXml(
            mediaUri = "http://192.168.1.10:8888/video.mp4",
            title = "Movie",
            mimeType = "video/mp4",
            subtitleUrl = "http://192.168.1.10:8888/sub/token123.srt"
        )
        // Check all vendor variants are present in the escaped metadata
        assertThat(xml).contains("http://192.168.1.10:8888/sub/token123.srt")
        assertThat(xml).contains("pv:subtitleFileType=&apos;srt&apos;")
        assertThat(xml).contains("&lt;res protocolInfo=&apos;http-get:*:text/srt:*&apos;&gt;")
        assertThat(xml).contains("&lt;sec:CaptionInfoEx sec:type=&apos;srt&apos;&gt;")
        assertThat(xml).contains("&lt;sec:CaptionInfo sec:type=&apos;srt&apos;&gt;")
        assertThat(xml).contains("xmlns:sec=&apos;http://www.sec.co.kr/&apos;")
        assertThat(xml).contains("xmlns:pv=&apos;http://www.pv.com/pvns/&apos;")
    }

    @Test
    fun testDidlParsesAsValidXml() {
        // Section B5: Parse finished unescaped DIDL with an XML parser to ensure no malformed XML
        val didl = DlnaXmlHelper.buildDidl(
            title = "Movie & Series <Special>",
            videoUrl = "http://192.168.1.10:8888/video.mp4",
            mimeType = "video/mp4",
            subtitleUrl = "http://192.168.1.10:8888/sub/token.srt"
        )
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(org.xml.sax.InputSource(StringReader(didl)))
        assertThat(doc.documentElement.nodeName).isEqualTo("DIDL-Lite")
    }

    @Test
    fun testUpnpTimeParsingAndFormatting() {
        // Standard formats
        assertEquals(3665_000L, DlnaXmlHelper.parseUpnpTime("01:01:05"))
        assertEquals(3665_120L, DlnaXmlHelper.parseUpnpTime("01:01:05.120"))
        assertEquals(130_000L, DlnaXmlHelper.parseUpnpTime("2:10"))
        assertEquals(null, DlnaXmlHelper.parseUpnpTime("NOT_IMPLEMENTED"))
        assertEquals(null, DlnaXmlHelper.parseUpnpTime(""))
        assertEquals(null, DlnaXmlHelper.parseUpnpTime(null))

        assertEquals("01:01:05", DlnaXmlHelper.formatUpnpTime(3665_000L))
        assertEquals("00:02:10", DlnaXmlHelper.formatUpnpTime(130_000L))
    }

    @Test
    fun testBuildSeekXml() {
        val relSeekXml = DlnaXmlHelper.buildSeekXml(120_000L, "REL_TIME")
        assertThat(relSeekXml).contains("<Unit>REL_TIME</Unit>")
        assertThat(relSeekXml).contains("<Target>00:02:00</Target>")

        val absSeekXml = DlnaXmlHelper.buildSeekXml(3600_000L, "ABS_TIME")
        assertThat(absSeekXml).contains("<Unit>ABS_TIME</Unit>")
        assertThat(absSeekXml).contains("<Target>01:00:00</Target>")
    }

    @Test
    fun testBuildTransportXmls() {
        val transportInfo = DlnaXmlHelper.buildGetTransportInfoXml()
        assertThat(transportInfo).contains("<u:GetTransportInfo")

        val transportActions = DlnaXmlHelper.buildGetTransportActionsXml()
        assertThat(transportActions).contains("<u:GetCurrentTransportActions")
    }
}
