package com.castbrowser.app.proxy

import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistRewriterTest {

    @Test
    fun testRewriteRelativeSegments() {
        val samplePlaylist = """
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:9.009,
segment1.ts
#EXTINF:9.009,
segment2.ts
#EXT-X-ENDLIST
        """.trimIndent()

        val baseUrl = "https://cdn.example.com/hls/master.m3u8"
        val proxyHost = "192.168.1.50:8888"
        val token = "session-123"

        val rewritten = HlsPlaylistRewriter.rewritePlaylist(
            playlistContent = samplePlaylist,
            baseMediaUrl = baseUrl,
            proxyHostPort = proxyHost,
            sessionToken = token
        )

        assertTrue(rewritten.contains("http://192.168.1.50:8888/proxy?token=session-123&url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fsegment1.ts"))
        assertTrue(rewritten.contains("http://192.168.1.50:8888/proxy?token=session-123&url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fsegment2.ts"))
        assertTrue(rewritten.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun testResolveUri() {
        val base = "https://cdn.example.com/path/video.m3u8"
        val relative = "seg.ts"
        val resolved = HlsPlaylistRewriter.resolveUri(base, relative)
        assertTrue(resolved == "https://cdn.example.com/path/seg.ts")
    }
}
