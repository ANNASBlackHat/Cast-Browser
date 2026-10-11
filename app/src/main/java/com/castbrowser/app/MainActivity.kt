package com.castbrowser.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.castbrowser.app.cast.*
import com.castbrowser.app.data.entity.TabEntity
import com.castbrowser.app.detect.DeliveryMode
import com.castbrowser.app.detect.DetectedStream
import com.castbrowser.app.proxy.LocalStreamProxy
import com.castbrowser.app.ui.screens.*
import com.castbrowser.app.ui.theme.CastBrowserTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CastApplication
        val container = app.container

        setContent {
            CastBrowserTheme {
                MainAppContent(container)
            }
        }
    }

    @Composable
    private fun MainAppContent(container: AppContainer) {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()

        val tabs by container.repository.getAllTabs().collectAsState(initial = emptyList())
        var activeTabId by remember { mutableStateOf<String?>(null) }

        val allTabStreams by container.webViewPool.tabStreams.collectAsState()
        val currentStreams = remember(activeTabId, allTabStreams) {
            allTabStreams[activeTabId] ?: emptyList()
        }

        var availableDevices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
        var isScanningDevices by remember { mutableStateOf(false) }

        var activeController by remember { mutableStateOf<CastController?>(null) }
        val castingPlaybackStatus = activeController?.status?.collectAsState()?.value

        // Restore existing tab or create initial tab only if DB is truly empty
        LaunchedEffect(Unit) {
            val existingTabs = container.repository.getAllTabsList()
            if (existingTabs.isNotEmpty()) {
                val active = existingTabs.firstOrNull { it.isActive } ?: existingTabs.first()
                activeTabId = active.id
            } else {
                val newId = UUID.randomUUID().toString()
                val initialTab = TabEntity(
                    id = newId,
                    url = "https://www.google.com",
                    title = "Google",
                    position = 0,
                    isActive = true,
                    isIncognito = false,
                    lastAccessedAt = System.currentTimeMillis()
                )
                container.repository.saveTab(initialTab)
                activeTabId = newId
            }
        }

        val activeTab = remember(tabs, activeTabId) {
            tabs.firstOrNull { it.id == activeTabId }
        }

        NavHost(navController = navController, startDestination = "browser") {
            composable("browser") {
                BrowserScreen(
                    repository = container.repository,
                    webViewPool = container.webViewPool,
                    activeTab = activeTab,
                    discoveredStreams = currentStreams,
                    availableDevices = availableDevices,
                    isScanningDevices = isScanningDevices,
                    castingPlaybackStatus = castingPlaybackStatus,
                    onOpenTabs = { navController.navigate("tabs") },
                    onOpenHistory = { navController.navigate("history") },
                    onStartScanDevices = {
                        scope.launch {
                            isScanningDevices = true
                            availableDevices = container.dlnaDiscoveryService.discoverDevices()
                            isScanningDevices = false
                        }
                    },
                    onStartCast = { device, stream ->
                        scope.launch {
                            if (device is DlnaDevice) {
                                val controller = DlnaCastController(device)
                                activeController = controller

                                var playUrl = stream.originalUrl
                                var proxyServer: LocalStreamProxy? = null

                                if (stream.deliveryMode == DeliveryMode.NEEDS_PROXY) {
                                    val proxy = LocalStreamProxy(8888)
                                    proxy.start()
                                    val token = proxy.registerStream(stream.headers, stream.cookies)
                                    val encoded = java.net.URLEncoder.encode(stream.originalUrl, "UTF-8")
                                    val phoneIp = getLocalIpAddress() ?: "127.0.0.1"
                                    playUrl = "http://$phoneIp:8888/proxy?token=$token&url=$encoded"
                                    proxyServer = proxy
                                }

                                val castMedia = CastMedia(
                                    url = playUrl,
                                    title = stream.title,
                                    mimeType = stream.mimeType ?: "video/mp4"
                                )

                                val serviceIntent = Intent(this@MainActivity, CastForegroundService::class.java)
                                startService(serviceIntent)

                                val pageUrl = activeTab?.url ?: "about:blank"
                                (CastForegroundService.activeControllerInstance as? DlnaCastController) // or cast directly
                                CastForegroundService().apply {
                                    // Start foreground session
                                }
                                controller.load(castMedia, 0L)
                            }
                        }
                    },
                    onPlayCast = { scope.launch { activeController?.play() } },
                    onPauseCast = { scope.launch { activeController?.pause() } },
                    onStopCast = {
                        scope.launch {
                            activeController?.stop()
                            activeController = null
                        }
                    },
                    onSeekCast = { pos -> scope.launch { activeController?.seekTo(pos) } },
                    onUrlNavigated = { updatedUrl ->
                        activeTabId?.let { tabId ->
                            val current = tabs.firstOrNull { it.id == tabId }
                            if (current != null && current.url != updatedUrl) {
                                scope.launch {
                                    container.repository.saveTab(current.copy(url = updatedUrl))
                                    container.repository.recordVisit(updatedUrl, current.title)
                                }
                            }
                        }
                    },
                    tabCount = tabs.size
                )
            }

            composable("tabs") {
                TabSwitcherScreen(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelectTab = { id ->
                        activeTabId = id
                        scope.launch { container.repository.setActiveTab(id) }
                        navController.popBackStack()
                    },
                    onCloseTab = { id ->
                        scope.launch {
                            container.webViewPool.removeTab(id)
                            container.repository.deleteTab(id)
                            if (activeTabId == id) {
                                activeTabId = tabs.firstOrNull { it.id != id }?.id
                            }
                        }
                    },
                    onNewTab = {
                        val newId = UUID.randomUUID().toString()
                        val newTab = TabEntity(
                            id = newId,
                            url = "https://www.google.com",
                            title = "New Tab",
                            position = tabs.size,
                            isActive = true,
                            isIncognito = false,
                            lastAccessedAt = System.currentTimeMillis()
                        )
                        scope.launch {
                            container.repository.saveTab(newTab)
                            container.repository.setActiveTab(newId)
                            activeTabId = newId
                            navController.popBackStack()
                        }
                    },
                    onDismiss = { navController.popBackStack() }
                )
            }

            composable("history") {
                HistoryScreen(
                    repository = container.repository,
                    onOpenUrl = { url ->
                        activeTabId?.let { tabId ->
                            val current = tabs.firstOrNull { it.id == tabId }
                            if (current != null) {
                                scope.launch {
                                    container.repository.saveTab(current.copy(url = url))
                                    navController.popBackStack()
                                }
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable("home") {
                HomeScreen(
                    repository = container.repository,
                    onOpenUrl = { url ->
                        activeTabId?.let { tabId ->
                            val current = tabs.firstOrNull { it.id == tabId }
                            if (current != null) {
                                scope.launch {
                                    container.repository.saveTab(current.copy(url = url))
                                    navController.navigate("browser")
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
