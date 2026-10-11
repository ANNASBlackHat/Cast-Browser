package com.castbrowser.app.proxy

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID

class LocalStreamProxy(
    private val port: Int = 8888,
    private val client: OkHttpClient = OkHttpClient()
) : NanoHTTPD(port) {

    private var activeSessionToken: String = UUID.randomUUID().toString()
    private val activeStreams = mutableMapOf<String, Map<String, String>>() // token -> headers

    fun registerStream(headers: Map<String, String>, cookies: String? = null): String {
        val token = UUID.randomUUID().toString()
        val finalHeaders = headers.toMutableMap()
        if (!cookies.isNullOrBlank()) {
            finalHeaders["Cookie"] = cookies
        }
        activeStreams[token] = finalHeaders
        return token
    }

    fun unregisterStream(token: String) {
        activeStreams.remove(token)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        // CORS headers
        val responseHeaders = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        if (session.method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            responseHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
            return resp
        }

        if (uri == "/proxy") {
            val token = params["token"]
            val rawTargetUrl = params["url"]

            if (rawTargetUrl == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing target URL")
            }

            val targetUrl = try {
                URLDecoder.decode(rawTargetUrl, "UTF-8")
            } catch (_: Exception) {
                rawTargetUrl
            }

            val storedHeaders = activeStreams[token] ?: emptyMap()

            return try {
                val okReqBuilder = Request.Builder().url(targetUrl)
                storedHeaders.forEach { (k, v) -> okReqBuilder.header(k, v) }

                // Pass Range request header if present
                val clientRange = session.headers["range"]
                if (clientRange != null) {
                    okReqBuilder.header("Range", clientRange)
                }

                val upstreamResponse = client.newCall(okReqBuilder.build()).execute()
                val upstreamBody = upstreamResponse.body

                if (upstreamBody == null) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream body")
                }

                val contentType = upstreamResponse.header("Content-Type") ?: "video/mp4"

                // Check if HLS playlist to rewrite URLs
                if (contentType.contains("vnd.apple.mpegurl") ||
                    contentType.contains("x-mpegurl") ||
                    targetUrl.substringBefore("?").endsWith(".m3u8")
                ) {
                    val playlistText = upstreamBody.string()
                    val hostPort = session.headers["host"] ?: "127.0.0.1:$port"
                    val rewritten = HlsPlaylistRewriter.rewritePlaylist(
                        playlistContent = playlistText,
                        baseMediaUrl = targetUrl,
                        proxyHostPort = hostPort,
                        sessionToken = token ?: ""
                    )

                    val resp = newFixedLengthResponse(
                        Response.Status.OK,
                        "application/vnd.apple.mpegurl",
                        rewritten
                    )
                    responseHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
                    return resp
                }

                // Handle binary media stream (with Range 206 support)
                val status = if (upstreamResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val contentLength = upstreamBody.contentLength()
                val stream: InputStream = upstreamBody.byteStream()

                val resp = newChunkedResponse(status, contentType, stream)
                responseHeaders.forEach { (k, v) -> resp.addHeader(k, v) }

                upstreamResponse.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
                if (contentLength > 0) {
                    resp.addHeader("Content-Length", contentLength.toString())
                }
                resp.addHeader("Accept-Ranges", "bytes")

                resp
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }
}
