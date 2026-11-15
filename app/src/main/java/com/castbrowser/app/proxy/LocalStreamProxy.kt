package com.castbrowser.app.proxy

import com.castbrowser.app.subtitle.SubtitleConverter
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class StreamRegistration(
    val headers: Map<String, String>,
    val cookies: String? = null,
    val subtitleUrl: String? = null
)

data class SubtitleRegistration(
    val subtitleUrl: String,
    val headers: Map<String, String> = emptyMap(),
    @Volatile var cachedSrt: String? = null
)

class LocalStreamProxy(
    private val port: Int = 8888,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : NanoHTTPD(port) {

    private val activeStreams = ConcurrentHashMap<String, StreamRegistration>()
    private val activeSubtitles = ConcurrentHashMap<String, SubtitleRegistration>()

    fun registerStream(
        headers: Map<String, String>,
        cookies: String? = null,
        subtitleUrl: String? = null
    ): String {
        val token = UUID.randomUUID().toString()
        val finalHeaders = headers.toMutableMap()
        if (!cookies.isNullOrBlank()) {
            finalHeaders["Cookie"] = cookies
        }
        activeStreams[token] = StreamRegistration(finalHeaders, cookies, subtitleUrl)
        if (!subtitleUrl.isNullOrBlank()) {
            activeSubtitles[token] = SubtitleRegistration(subtitleUrl, finalHeaders)
        }
        return token
    }

    fun registerSubtitle(token: String, subtitleUrl: String, headers: Map<String, String> = emptyMap()) {
        activeSubtitles[token] = SubtitleRegistration(subtitleUrl, headers)
        // Also update stream if already registered
        activeStreams[token]?.let { stream ->
            activeStreams[token] = stream.copy(subtitleUrl = subtitleUrl)
        }
    }

    fun unregisterStream(token: String) {
        activeStreams.remove(token)
        activeSubtitles.remove(token)
    }

    fun hasStream(token: String): Boolean = activeStreams.containsKey(token)

    fun hasSubtitle(token: String): Boolean = activeSubtitles.containsKey(token)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms
        val method = session.method

        val clientRange = session.headers["range"]
        val getCaption = session.headers["getcaptioninfo.sec"]
        val getContentFeat = session.headers["getcontentfeatures.dlna.org"]

        println("[LocalStreamProxy] $method $uri (Range=$clientRange, getcaptioninfo.sec=$getCaption, getcontentfeatures=$getContentFeat)")

        // Global CORS headers
        val corsHeaders = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        if (method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
            return resp
        }

        // Subtitle endpoint: /sub/<token>.srt
        if (uri.startsWith("/sub/") && uri.endsWith(".srt")) {
            return handleSubtitleRequest(session, uri, corsHeaders)
        }

        // Video / media endpoint: /proxy
        if (uri == "/proxy") {
            val token = params["token"]
            val rawTargetUrl = params["url"]

            if (token.isNullOrBlank() || !activeStreams.containsKey(token)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid or missing token")
            }

            if (rawTargetUrl.isNullOrBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing target URL")
            }

            val targetUrl = rawTargetUrl
            val streamReg = activeStreams[token] ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            val storedHeaders = streamReg.headers

            return try {
                val okReqBuilder = Request.Builder().url(targetUrl)
                storedHeaders.forEach { (k, v) -> okReqBuilder.header(k, v) }

                if (method == Method.HEAD) {
                    okReqBuilder.head()
                } else if (clientRange != null) {
                    okReqBuilder.header("Range", clientRange)
                }

                val upstreamResponse = client.newCall(okReqBuilder.build()).execute()
                val contentType = upstreamResponse.header("Content-Type") ?: "video/mp4"

                val isHls = contentType.contains("vnd.apple.mpegurl", ignoreCase = true) ||
                        contentType.contains("x-mpegurl", ignoreCase = true) ||
                        targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)

                val dlnaFlags = if (isHls) {
                    "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                } else {
                    "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                }

                // Handle HEAD requests
                if (method == Method.HEAD) {
                    val resp = newFixedLengthResponse(Response.Status.OK, contentType, "")
                    corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
                    upstreamResponse.header("Content-Length")?.let { resp.addHeader("Content-Length", it) }
                    resp.addHeader("Accept-Ranges", "bytes")
                    resp.addHeader("transferMode.dlna.org", "Streaming")

                    if (getContentFeat == "1") {
                        resp.addHeader("contentFeatures.dlna.org", dlnaFlags)
                    }
                    if (getCaption == "1" && streamReg.subtitleUrl != null) {
                        val host = session.headers["host"] ?: "127.0.0.1:$port"
                        val subProxyUrl = "http://$host/sub/$token.srt"
                        resp.addHeader("CaptionInfo.sec", subProxyUrl)
                    }
                    upstreamResponse.close()
                    return resp
                }

                val upstreamBody = upstreamResponse.body
                if (upstreamBody == null) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream body")
                }

                // Handle HLS playlist rewriting
                if (isHls) {
                    val playlistText = upstreamBody.string()
                    val hostPort = session.headers["host"] ?: "127.0.0.1:$port"
                    val rewritten = HlsPlaylistRewriter.rewritePlaylist(
                        playlistContent = playlistText,
                        baseMediaUrl = targetUrl,
                        proxyHostPort = hostPort,
                        sessionToken = token
                    )
                    val resp = newFixedLengthResponse(
                        Response.Status.OK,
                        "application/vnd.apple.mpegurl",
                        rewritten
                    )
                    corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
                    resp.addHeader("transferMode.dlna.org", "Streaming")
                    if (getContentFeat == "1") {
                        resp.addHeader("contentFeatures.dlna.org", dlnaFlags)
                    }
                    return resp
                }

                // Handle binary media stream (with Range 206 support)
                val status = if (upstreamResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val stream: InputStream = upstreamBody.byteStream()

                val resp = newChunkedResponse(status, contentType, stream)
                corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }

                upstreamResponse.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("transferMode.dlna.org", "Streaming")

                if (getContentFeat == "1") {
                    resp.addHeader("contentFeatures.dlna.org", dlnaFlags)
                }

                // Add Samsung CaptionInfo.sec header if requested (B6)
                if (getCaption == "1" && streamReg.subtitleUrl != null) {
                    val host = session.headers["host"] ?: "127.0.0.1:$port"
                    val subProxyUrl = "http://$host/sub/$token.srt"
                    resp.addHeader("CaptionInfo.sec", subProxyUrl)
                }

                resp
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleSubtitleRequest(
        session: IHTTPSession,
        uri: String,
        corsHeaders: Map<String, String>
    ): Response {
        val token = uri.removePrefix("/sub/").removeSuffix(".srt")
        val subReg = activeSubtitles[token] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Subtitle not found")

        try {
            var srtContent = subReg.cachedSrt
            if (srtContent == null) {
                // Fetch upstream subtitle file
                val okReq = Request.Builder()
                    .url(subReg.subtitleUrl)
                    .apply {
                        subReg.headers.forEach { (k, v) -> header(k, v) }
                    }
                    .build()

                val upstreamResp = client.newCall(okReq).execute()
                if (!upstreamResp.isSuccessful) {
                    upstreamResp.close()
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to fetch subtitle upstream: ${upstreamResp.code}")
                }

                val bytes = upstreamResp.body?.bytes() ?: ByteArray(0)
                upstreamResp.close()

                srtContent = SubtitleConverter.convertToSrt(bytes, subReg.subtitleUrl)
                subReg.cachedSrt = srtContent
            }

            val srtBytes = srtContent.toByteArray(Charsets.UTF_8)

            if (session.method == Method.HEAD) {
                val resp = newFixedLengthResponse(Response.Status.OK, "text/srt; charset=utf-8", "")
                corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
                resp.addHeader("Content-Length", srtBytes.size.toString())
                resp.addHeader("Accept-Ranges", "bytes")
                return resp
            }

            val stream = ByteArrayInputStream(srtBytes)
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "text/srt; charset=utf-8",
                stream,
                srtBytes.size.toLong()
            )
            corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Subtitle error: ${e.message}")
        }
    }
}
