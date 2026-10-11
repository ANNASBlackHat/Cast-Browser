package com.castbrowser.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.castbrowser.app.detect.DetectedStream
import com.castbrowser.app.detect.StreamClassifier
import com.castbrowser.app.detect.StreamProbeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream

class CastWebViewClient(
    private val context: Context,
    private val tabId: String,
    private val isIncognito: Boolean,
    private val probeService: StreamProbeService,
    private val onPageTitleChanged: (String) -> Unit,
    private val onUrlChanged: (String) -> Unit,
    private val onPageStartedCallback: (String) -> Unit = {},
    private val onStreamDiscovered: (DetectedStream) -> Unit
) : WebViewClient() {

    private val clientScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val emptyResponse = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // 1. Check AdBlock
        if (AdBlockEngine.isAdOrTracker(url)) {
            return emptyResponse
        }

        // 2. Check Video Stream Candidate
        if (StreamClassifier.isPotentialStreamUrl(url)) {
            val headers = request.requestHeaders ?: emptyMap()
            val cookie = CookieManager.getInstance().getCookie(url)

            clientScope.launch {
                val probed = probeService.probeStream(url, headers, cookie)
                if (probed != null) {
                    onStreamDiscovered(probed)
                }
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        // Block external app redirects and known ad popups
        if (AdBlockEngine.isAdOrTracker(url)) {
            return true // Consumed: do not load popup/ad!
        }
        return false // Allow legitimate site navigation
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let {
            onUrlChanged(it)
            onPageStartedCallback(it)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.title?.let { onPageTitleChanged(it) }
        url?.let { onUrlChanged(it) }

        // Fallback script injection if document-start script is unsupported
        injectFallbackDetector(view)
    }

    private fun injectFallbackDetector(view: WebView?) {
        try {
            val script = context.assets.open("video_detector.js").bufferedReader().use { it.readText() }
            view?.evaluateJavascript(script, null)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun postMessage(jsonStr: String) {
        handleJsMessage(jsonStr)
    }

    fun handleJsMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            if (type == "STREAM_CANDIDATE") {
                val url = json.optString("url")
                if (StreamClassifier.isPotentialStreamUrl(url)) {
                    val cookie = CookieManager.getInstance().getCookie(url)
                    clientScope.launch {
                        val probed = probeService.probeStream(url, emptyMap(), cookie)
                        if (probed != null) {
                            onStreamDiscovered(probed)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
