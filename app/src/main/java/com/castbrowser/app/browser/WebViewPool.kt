package com.castbrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.castbrowser.app.detect.DetectedStream
import com.castbrowser.app.detect.DetectedSubtitle
import com.castbrowser.app.detect.StreamProbeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.LinkedHashMap

class WebViewPool(
    private val context: Context,
    private val stateManager: TabStateManager,
    private val probeService: StreamProbeService,
    private val maxLiveWebViews: Int = 3
) {
    // LRU cache of live WebViews: tabId -> WebView
    private val activeViews = object : LinkedHashMap<String, WebView>(maxLiveWebViews, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebView>?): Boolean {
            if (size > maxLiveWebViews && eldest != null) {
                evictWebView(eldest.key, eldest.value)
                return true
            }
            return false
        }
    }

    private val tabPopupCallbacks = mutableMapOf<String, (String) -> Unit>()

    private val _tabStreams = MutableStateFlow<Map<String, List<DetectedStream>>>(emptyMap())
    val tabStreams: StateFlow<Map<String, List<DetectedStream>>> = _tabStreams.asStateFlow()

    private val _tabSubtitles = MutableStateFlow<Map<String, List<DetectedSubtitle>>>(emptyMap())
    val tabSubtitles: StateFlow<Map<String, List<DetectedSubtitle>>> = _tabSubtitles.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(
        tabId: String,
        initialUrl: String,
        isIncognito: Boolean,
        onTitleUpdate: (String) -> Unit,
        onUrlUpdate: (String) -> Unit,
        onPopupBlocked: ((String) -> Unit)? = null
    ): WebView {
        if (onPopupBlocked != null) {
            tabPopupCallbacks[tabId] = onPopupBlocked
        }

        activeViews[tabId]?.let { existing ->
            // C3 Fix: If a new URL was requested (e.g. from Home or History), load it
            if (initialUrl.isNotBlank() && existing.url != initialUrl && initialUrl != "about:blank") {
                existing.loadUrl(initialUrl)
            }
            return existing
        }

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                javaScriptCanOpenWindowsAutomatically = false // Block automated popups
                setSupportMultipleWindows(true) // Intercept window.open / target="_blank" popups in onCreateWindow
                userAgentString = userAgentString.replace("; wv", "") // Clean WebView token
            }

            val client = CastWebViewClient(
                context = context,
                tabId = tabId,
                isIncognito = isIncognito,
                probeService = probeService,
                onPageTitleChanged = onTitleUpdate,
                onUrlChanged = onUrlUpdate,
                onPageStartedCallback = { _ ->
                    clearStreamsForTab(tabId)
                },
                onStreamDiscovered = { stream ->
                    addDiscoveredStream(tabId, stream)
                },
                onSubtitleDiscovered = { subtitle ->
                    addDiscoveredSubtitle(tabId, subtitle)
                }
            )
            webViewClient = client
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (resultMsg == null) return false

                    // Headless temporary WebView to intercept and block popup window URL
                    val tempWebView = WebView(context).apply {
                        settings.javaScriptEnabled = false
                        webViewClient = object : WebViewClient() {
                            private var handled = false

                            private fun handleUrl(url: String?) {
                                if (handled || url.isNullOrBlank() || url == "about:blank") return
                                handled = true
                                tabPopupCallbacks[tabId]?.invoke(url)
                                post {
                                    try { destroy() } catch (_: Exception) {}
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                handleUrl(request?.url?.toString())
                                return true // Intercept: do not load popup content
                            }

                            override fun onPageStarted(
                                v: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                handleUrl(url)
                            }
                        }
                    }

                    // Auto-destroy failsafe in case the popup never loads a URL
                    tempWebView.postDelayed({
                        try { tempWebView.destroy() } catch (_: Exception) {}
                    }, 5000L)

                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    return if (transport != null) {
                        transport.webView = tempWebView
                        resultMsg.sendToTarget()
                        true
                    } else {
                        try { tempWebView.destroy() } catch (_: Exception) {}
                        false
                    }
                }
            }
            addJavascriptInterface(client, "CastBrowserBridge")
        }

        // Try restoring state from disk
        val savedBundle = if (!isIncognito) stateManager.restoreTabBundle(tabId) else null
        if (savedBundle != null) {
            webView.restoreState(savedBundle)
        } else if (initialUrl.isNotBlank()) {
            webView.loadUrl(initialUrl)
        }

        activeViews[tabId] = webView
        return webView
    }

    fun saveTabState(tabId: String) {
        val webView = activeViews[tabId] ?: return
        val bundle = Bundle()
        webView.saveState(bundle)
        stateManager.saveTabBundle(tabId, bundle)
    }

    fun removeTab(tabId: String) {
        val view = activeViews.remove(tabId)
        tabPopupCallbacks.remove(tabId)
        (view?.webViewClient as? CastWebViewClient)?.destroy()
        view?.destroy()
        stateManager.deleteTabFiles(tabId)

        _tabStreams.update { current ->
            current.toMutableMap().apply { remove(tabId) }
        }
        _tabSubtitles.update { current ->
            current.toMutableMap().apply { remove(tabId) }
        }
    }

    private fun evictWebView(tabId: String, webView: WebView) {
        val bundle = Bundle()
        webView.saveState(bundle)
        stateManager.saveTabBundle(tabId, bundle)
        (webView.webViewClient as? CastWebViewClient)?.destroy()
        webView.destroy()
    }

    fun clearStreamsForTab(tabId: String) {
        _tabStreams.update { current ->
            current.toMutableMap().apply { put(tabId, emptyList()) }
        }
        _tabSubtitles.update { current ->
            current.toMutableMap().apply { put(tabId, emptyList()) }
        }
    }

    private fun addDiscoveredStream(tabId: String, stream: DetectedStream) {
        _tabStreams.update { current ->
            if (!activeViews.containsKey(tabId)) return@update current
            val currentList = current[tabId] ?: emptyList()
            if (currentList.any { it.originalUrl == stream.originalUrl }) {
                current
            } else {
                val existingSubs = _tabSubtitles.value[tabId] ?: emptyList()
                val updatedStream = if (stream.subtitles.isEmpty() && existingSubs.isNotEmpty()) {
                    stream.copy(subtitles = existingSubs)
                } else stream

                current.toMutableMap().apply {
                    put(tabId, currentList + updatedStream)
                }
            }
        }
    }

    private fun addDiscoveredSubtitle(tabId: String, subtitle: DetectedSubtitle) {
        _tabSubtitles.update { current ->
            val list = current[tabId] ?: emptyList()
            if (list.any { it.url == subtitle.url }) current
            else current.toMutableMap().apply { put(tabId, list + subtitle) }
        }

        // Also update existing streams in the tab to carry this subtitle
        _tabStreams.update { current ->
            val streams = current[tabId] ?: return@update current
            val updated = streams.map { stream ->
                if (stream.subtitles.none { it.url == subtitle.url }) {
                    stream.copy(subtitles = stream.subtitles + subtitle)
                } else stream
            }
            current.toMutableMap().apply { put(tabId, updated) }
        }
    }

    fun clearAll() {
        activeViews.values.forEach { it.destroy() }
        activeViews.clear()
        tabPopupCallbacks.clear()
        _tabStreams.value = emptyMap()
        _tabSubtitles.value = emptyMap()
    }
}
