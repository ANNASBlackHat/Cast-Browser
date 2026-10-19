package com.castbrowser.app.proxy

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalStreamProxy(
    private val port: Int = 8888,
    private val client: OkHttpClient = OkHttpClient()
) : NanoHTTPD(port) {

    private val activeStreams = ConcurrentHashMap<String, Map<String, String>>() // token -> headers

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

    fun hasStream(token: String): Boolean = activeStreams.containsKey(token)

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

            // H2 Fix: Enforce token presence and validity to prevent SSRF
            if (token.isNullOrBlank() || !activeStreams.containsKey(token)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid or missing token")
            }

            if (rawTargetUrl.isNullOrBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing target URL")
            }

            // C4 Fix: Do NOT call URLDecoder.decode! NanoHTTPD already percent-decodes session.parms.
            val targetUrl = rawTargetUrl
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

                // M4 Fix: Case-insensitive check for HLS playlist to rewrite URLs
                if (contentType.contains("vnd.apple.mpegurl", ignoreCase = true) ||
                    contentType.contains("x-mpegurl", ignoreCase = true) ||
                    targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
                ) {
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
                    responseHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
                    return resp
                }

                // Handle binary media stream (with Range 206 support)
                val status = if (upstreamResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val stream: InputStream = upstreamBody.byteStream()

                // M3 Fix: Do not add Content-Length to chunked response (violates RFC 7230 section 3.3.2)
                val resp = newChunkedResponse(status, contentType, stream)
                responseHeaders.forEach { (k, v) -> resp.addHeader(k, v) }

                upstreamResponse.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
                resp.addHeader("Accept-Ranges", "bytes")

                resp
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }
}
