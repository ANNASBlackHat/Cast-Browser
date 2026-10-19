package com.castbrowser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeTitleParserTest {

    @Test
    fun testParseStandardSXXEXX() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "Severance S02E05 - Watch Online Free HD",
            url = "https://example.com/watch/severance-s02e05"
        )
        assertEquals("Severance", result.seriesKey)
        assertEquals(2, result.season)
        assertEquals(5, result.episode)
    }

    @Test
    fun testParseNxMFormat() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "Breaking Bad 5x14 Ozymandias | Stream Site",
            url = "https://example.com/breaking-bad-5x14"
        )
        assertEquals("Breaking Bad", result.seriesKey)
        assertEquals(5, result.season)
        assertEquals(14, result.episode)
    }

    @Test
    fun testParseEpisodeText() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "House of the Dragon - Episode 8 - Full Movie HD",
            url = "https://example.com/hotd-ep8"
        )
        assertEquals("House of the Dragon", result.seriesKey)
        assertEquals(1, result.season)
        assertEquals(8, result.episode)
    }

    @Test
    fun testParseUrlEpisodeWhenTitleDoesNotContainIt() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "Attack on Titan Final Season",
            url = "https://anime.tv/watch/aot/episode-28/"
        )
        assertEquals("Attack on Titan Final Season", result.seriesKey)
        assertEquals(1, result.season)
        assertEquals(28, result.episode)
    }

    @Test
    fun testParseStandaloneMovie() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "Inception (2010) - Watch Free",
            url = "https://example.com/movies/inception-2010"
        )
        assertNull(result.seriesKey)
        assertNull(result.season)
        assertNull(result.episode)
        assertEquals("Inception (2010)", result.title)
    }

    @Test
    fun testParsePrefixEpisodeFormatDoesNotLeakLastCharIntoSuffix() {
        // e.g. S01E01 - Pilot Episode
        val result = EpisodeTitleParser.parse(
            rawTitle = "S01E01 - Pilot Episode - Watch Online Free HD",
            url = "https://example.com/s01e01"
        )
        assertEquals("Pilot Episode", result.seriesKey)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
    }

    @Test
    fun testParseTitleWithMultipleJunkTagsAndPrefixes() {
        val result = EpisodeTitleParser.parse(
            rawTitle = "The Bear 2x04 Honeydew | Stream HD",
            url = "https://example.com/watch/the-bear-2x04"
        )
        assertEquals("The Bear", result.seriesKey)
        assertEquals(2, result.season)
        assertEquals(4, result.episode)
    }
}
