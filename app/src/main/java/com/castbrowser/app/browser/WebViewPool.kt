package com.castbrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import com.castbrowser.app.detect.DetectedStream
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

    private val _tabStreams = MutableStateFlow<Map<String, List<DetectedStream>>>(emptyMap())
    val tabStreams: StateFlow<Map<String, List<DetectedStream>>> = _tabStreams.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(
        tabId: String,
        initialUrl: String,
        isIncognito: Boolean,
        onTitleUpdate: (String) -> Unit,
        onUrlUpdate: (String) -> Unit
    ): WebView {
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
                setSupportMultipleWindows(false) // Block target="_blank" window popups
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
                }
            )
            webViewClient = client
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    // Refuse popup window creation from ad clicks!
                    return false
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
        (view?.webViewClient as? CastWebViewClient)?.destroy()
        view?.destroy()
        stateManager.deleteTabFiles(tabId)

        _tabStreams.update { current ->
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
    }

    private fun addDiscoveredStream(tabId: String, stream: DetectedStream) {
        _tabStreams.update { current ->
            if (!activeViews.containsKey(tabId)) return@update current
            val currentList = current[tabId] ?: emptyList()
            if (currentList.any { it.originalUrl == stream.originalUrl }) {
                current
            } else {
                current.toMutableMap().apply {
                    put(tabId, currentList + stream)
                }
            }
        }
    }

    fun clearAll() {
        activeViews.values.forEach { it.destroy() }
        activeViews.clear()
        _tabStreams.value = emptyMap()
    }
}
