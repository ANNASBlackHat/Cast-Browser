package com.castbrowser.app.data

data class ParsedMediaInfo(
    val title: String,
    val seriesKey: String?,
    val season: Int?,
    val episode: Int?
)

object EpisodeTitleParser {
    // Regex patterns for seasons and episodes
    // S02E05, S2E5, S02.E05, S2.E5
    private val S_E_REGEX = Regex("""(?i)\bS(\d{1,2})[\s._-]*E(\d{1,3})\b""")
    // 2x05, 02x05
    private val X_REGEX = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
    // Season 2 Episode 5, Season 02, Episode 05
    private val SEASON_EPISODE_TEXT_REGEX = Regex("""(?i)\bSeason\s*(\d{1,2})\s*[,._-]?\s*Episode\s*(\d{1,3})\b""")
    // Episode 5, Ep. 5, Ep 05, /episode-5/
    private val EPISODE_ONLY_REGEX = Regex("""(?i)(?:\b(?:Episode|Ep\.?)\s*(\d{1,3})\b|/episode-(\d{1,3})(?:/|\.|$))""")

    // Common junk site suffixes
    private val JUNK_SUFFIXES = listOf(
        Regex("""(?i)\s*[-|–—]\s*(?:Watch\s+(?:Online|Free)|Watch\s+.*|Free\s+Streaming|Full\s+Movie|Stream|HD|1080p|720p|Putlocker|Fmovies|123movies|Goku).*$"""),
        Regex("""(?i)\s*\|\s*.*$"""),
        Regex("""(?i)\s*[-–—]\s*Official\s+Site.*$""")
    )

    fun parse(rawTitle: String, url: String): ParsedMediaInfo {
        var cleanTitle = rawTitle.trim()
        for (pattern in JUNK_SUFFIXES) {
            cleanTitle = cleanTitle.replace(pattern, "").trim()
        }

        // Try S02E05
        S_E_REGEX.find(cleanTitle)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            val seriesTitle = extractSeriesTitle(cleanTitle, match.range)
            return ParsedMediaInfo(
                title = cleanTitle,
                seriesKey = seriesTitle,
                season = season,
                episode = episode
            )
        }

        // Try 2x05
        X_REGEX.find(cleanTitle)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            val seriesTitle = extractSeriesTitle(cleanTitle, match.range)
            return ParsedMediaInfo(
                title = cleanTitle,
                seriesKey = seriesTitle,
                season = season,
                episode = episode
            )
        }

        // Try Season X Episode Y
        SEASON_EPISODE_TEXT_REGEX.find(cleanTitle)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            val seriesTitle = extractSeriesTitle(cleanTitle, match.range)
            return ParsedMediaInfo(
                title = cleanTitle,
                seriesKey = seriesTitle,
                season = season,
                episode = episode
            )
        }

        // Try Episode only from title or URL
        val epMatch = EPISODE_ONLY_REGEX.find(cleanTitle) ?: EPISODE_ONLY_REGEX.find(url)
        if (epMatch != null) {
            val epStr = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[2] }
            val episode = epStr.toIntOrNull()
            val seriesTitle = if (cleanTitle.contains(epMatch.value)) {
                extractSeriesTitle(cleanTitle, epMatch.range)
            } else {
                cleanTitle
            }
            return ParsedMediaInfo(
                title = cleanTitle,
                seriesKey = seriesTitle.ifBlank { null },
                season = 1, // Default season 1 if only episode is found
                episode = episode
            )
        }

        // Standalone movie or unparsed video
        return ParsedMediaInfo(
            title = cleanTitle.ifBlank { "Untitled Video" },
            seriesKey = null,
            season = null,
            episode = null
        )
    }

    private fun extractSeriesTitle(cleanTitle: String, matchRange: IntRange): String {
        val prefix = cleanTitle.substring(0, matchRange.first.coerceAtMost(cleanTitle.length))
            .trim(' ', '-', '–', ':', '|', ',')
        if (prefix.isNotBlank()) return prefix
        val suffixStartIndex = (matchRange.last + 1).coerceAtMost(cleanTitle.length)
        val suffix = cleanTitle.substring(suffixStartIndex)
            .trim(' ', '-', '–', ':', '|', ',')
        return suffix.ifBlank { cleanTitle }
    }
}
