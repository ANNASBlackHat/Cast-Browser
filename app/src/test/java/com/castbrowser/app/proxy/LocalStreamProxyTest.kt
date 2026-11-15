package com.castbrowser.app.proxy

import com.google.common.truth.Truth.assertThat
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket

class LocalStreamProxyTest {

    private var port: Int = 0
    private var proxy: LocalStreamProxy? = null

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    @Before
    fun setUp() {
        port = findFreePort()
        proxy = LocalStreamProxy(port = port)
    }

    @After
    fun tearDown() {
        try {
            proxy?.stop()
        } catch (_: Exception) {}
    }

    private class MockHttpSession(
        private val uriStr: String,
        private val parmsMap: Map<String, String> = emptyMap(),
        private val headersMap: Map<String, String> = emptyMap(),
        private val httpMethod: NanoHTTPD.Method = NanoHTTPD.Method.GET
    ) : NanoHTTPD.IHTTPSession {
        @Deprecated("Deprecated in Java")
        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = headersMap
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getMethod(): NanoHTTPD.Method = httpMethod
        override fun getParms(): Map<String, String> = parmsMap
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uriStr
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
        override fun getParameters(): Map<String, List<String>> = parmsMap.mapValues { listOf(it.value) }
    }

    @Test
    fun `serve rejects requests without valid token with 403 Forbidden`() {
        val session = MockHttpSession(
            uriStr = "/proxy",
            parmsMap = mapOf(
                "token" to "invalid-token",
                "url" to "https://cdn.example.com/video.mp4"
            )
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.FORBIDDEN)
    }

    @Test
    fun `serve rejects requests missing token with 403 Forbidden`() {
        val session = MockHttpSession(
            uriStr = "/proxy",
            parmsMap = mapOf(
                "url" to "https://cdn.example.com/video.mp4"
            )
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.FORBIDDEN)
    }

    @Test
    fun `serve rejects requests missing url with 400 Bad Request`() {
        val token = proxy!!.registerStream(emptyMap())
        val session = MockHttpSession(
            uriStr = "/proxy",
            parmsMap = mapOf("token" to token)
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.BAD_REQUEST)
    }

    @Test
    fun `registered stream can be unregistered`() {
        val token = proxy!!.registerStream(emptyMap())
        proxy!!.unregisterStream(token)
        val session = MockHttpSession(
            uriStr = "/proxy",
            parmsMap = mapOf(
                "token" to token,
                "url" to "https://cdn.example.com/video.mp4"
            )
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.FORBIDDEN)
    }

    @Test
    fun `OPTIONS request returns 200 with CORS headers`() {
        val session = MockHttpSession(
            uriStr = "/proxy",
            httpMethod = NanoHTTPD.Method.OPTIONS
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.OK)
        assertThat(response.getHeader("access-control-allow-origin")).isEqualTo("*")
    }

    @Test
    fun `subtitle endpoint returns 404 for unknown token`() {
        val session = MockHttpSession(
            uriStr = "/sub/nonexistent.srt"
        )
        val response = proxy!!.serve(session)
        assertThat(response.status).isEqualTo(NanoHTTPD.Response.Status.NOT_FOUND)
    }

    @Test
    fun `subtitle endpoint handles cached SRT`() {
        val token = proxy!!.registerStream(emptyMap())
        proxy!!.registerSubtitle(token, "https://example.com/sub.vtt")
        assertThat(proxy!!.hasSubtitle(token)).isTrue()
    }
}
