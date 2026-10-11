package com.castbrowser.app.detect

import android.net.Uri

object StreamClassifier {
    private val SEGMENT_EXTENSIONS = setOf(
        ".ts", ".m4s", ".aac", ".vtt", ".srt", ".key"
    )

    fun isMediaSegment(url: String): Boolean {
        val cleanUrl = url.substringBefore("?").lowercase()
        return SEGMENT_EXTENSIONS.any { cleanUrl.endsWith(it) }
    }

    fun isLikelyAdOrPixel(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/pixel") ||
                lower.contains("/telemetry") ||
                lower.contains("/analytics") ||
                lower.contains("/ad_") ||
                lower.contains("/ads/") ||
                lower.contains("doubleclick.net") ||
                lower.contains("googlesyndication.com")
    }

    fun classifyStreamType(url: String, contentType: String? = null): StreamType {
        val cleanUrl = url.substringBefore("?").lowercase()
        val cType = contentType?.lowercase() ?: ""

        return when {
            cleanUrl.endsWith(".m3u8") ||
                    cType.contains("application/vnd.apple.mpegurl") ||
                    cType.contains("application/x-mpegurl") ||
                    cleanUrl.contains("/hls/") ||
                    cleanUrl.contains("playlist.m3u8") -> StreamType.HLS

            cleanUrl.endsWith(".mpd") ||
                    cType.contains("application/dash+xml") ||
                    cleanUrl.contains("/dash/") -> StreamType.DASH

            cleanUrl.endsWith(".mp4") ||
                    cType.contains("video/mp4") -> StreamType.MP4

            cleanUrl.endsWith(".webm") ||
                    cType.contains("video/webm") -> StreamType.WEBM

            cleanUrl.contains("videoplayback") -> StreamType.MP4

            else -> StreamType.UNKNOWN
        }
    }

    fun isPotentialStreamUrl(url: String): Boolean {
        if (url.startsWith("blob:") || url.startsWith("data:")) return false
        if (isMediaSegment(url) || isLikelyAdOrPixel(url)) return false

        val cleanUrl = url.substringBefore("?").lowercase()
        return cleanUrl.endsWith(".m3u8") ||
                cleanUrl.endsWith(".mp4") ||
                cleanUrl.endsWith(".mpd") ||
                cleanUrl.endsWith(".webm") ||
                cleanUrl.contains("videoplayback") ||
                cleanUrl.contains("/manifest") ||
                cleanUrl.contains("/master") ||
                cleanUrl.contains("/playlist")
    }
}
