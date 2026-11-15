package com.castbrowser.app.subtitle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.Charset

class SubtitleConverterTest {

    @Test
    fun `test decodeSubtitle with UTF-8 BOM`() {
        val originalText = "Hello world!"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + originalText.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleConverter.decodeSubtitle(bytes)
        assertThat(decoded).isEqualTo("Hello world!")
    }

    @Test
    fun `test decodeSubtitle with Windows-1252 ANSI encoding`() {
        // Windows-1252 encoded string with special accent characters
        val win1252 = Charset.forName("windows-1252")
        val sample = "Café & naïve señor"
        val bytes = sample.toByteArray(win1252)

        val decoded = SubtitleConverter.decodeSubtitle(bytes)
        assertThat(decoded).contains("Café")
    }

    @Test
    fun `test webVttToSrt converts timestamps and strips formatting`() {
        val vtt = """
            WEBVTT - Sample File
            
            NOTE This is a note block that should be ignored
            
            00:01.000 --> 00:04.500
            <v Roger>Hello <b>world</b>!</v>
            
            01:10:05.120 --> 01:10:08.340
            <c.yellow>Second line of text</c>
        """.trimIndent()

        val srt = SubtitleConverter.webVttToSrt(vtt)

        assertThat(srt).contains("1\n00:00:01,000 --> 00:00:04,500\nHello world!")
        assertThat(srt).contains("2\n01:10:05,120 --> 01:10:08,340\nSecond line of text")
        assertThat(srt).doesNotContain("WEBVTT")
        assertThat(srt).doesNotContain("NOTE")
        assertThat(srt).doesNotContain("<v Roger>")
        assertThat(srt).doesNotContain("<c.yellow>")
    }

    @Test
    fun `test assToSrt converts dialogue and strips override tags`() {
        val ass = """
            [Script Info]
            Title: Sample ASS
            
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:23.45,0:01:28.90,Default,,0,0,0,,{\pos(100,200)}Hello{\an8} world!\NNext line.
        """.trimIndent()

        val srt = SubtitleConverter.assToSrt(ass)

        assertThat(srt).isEqualTo("1\n00:01:23,450 --> 00:01:28,900\nHello world!\nNext line.")
    }

    @Test
    fun `test convertToSrt routes by content and url`() {
        val vttBytes = "WEBVTT\n\n00:01.000 --> 00:02.000\nTest".toByteArray(Charsets.UTF_8)
        val srt = SubtitleConverter.convertToSrt(vttBytes, "https://example.com/subs.vtt")
        assertThat(srt).contains("00:00:01,000 --> 00:00:02,000")
    }
}
