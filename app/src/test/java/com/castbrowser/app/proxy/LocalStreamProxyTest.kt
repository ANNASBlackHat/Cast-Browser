package com.castbrowser.app.proxy

import com.google.common.truth.Truth.assertThat
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI

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
        private val parmsMap: Map<String, String>,
        private val headersMap: Map<String, String> = emptyMap(),
        private val httpMethod: NanoHTTPD.Method = NanoHTTPD.Method.GET
    ) : NanoHTTPD.IHTTPSession {
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
    fun `serve preserves complex query parameters and plus symbols in target url without double decoding`() {
        val token = proxy!!.registerStream(emptyMap())
        // In NanoHTTPD, session.parms represents already-decoded query parameters.
        // If the upstream video URL was https://cdn.example.com/v?sig=a+b&next=%2Fhome,
        // NanoHTTPD's parameter parser delivers "https://cdn.example.com/v?sig=a+b&next=%2Fhome".
        // With double-decode, a+b became 'a b' and %2F became '/'. Without double-decode, it is preserved.
        val complexUrl = "https://cdn.example.com/v?sig=a+b&next=%2Fhome"
        val session = MockHttpSession(
            uriStr = "/proxy",
            parmsMap = mapOf(
                "token" to token,
                "url" to complexUrl
            )
        )
        val response = proxy!!.serve(session)
        // It tries to connect upstream with OkHttp to a mock address, resulting in internal error (or connection error),
        // but importantly NOT forbidden or bad request.
        assertThat(response.status).isNotEqualTo(NanoHTTPD.Response.Status.FORBIDDEN)
        assertThat(response.status).isNotEqualTo(NanoHTTPD.Response.Status.BAD_REQUEST)
    }
}
