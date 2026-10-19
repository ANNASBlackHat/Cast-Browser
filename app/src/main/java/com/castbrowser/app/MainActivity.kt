package com.castbrowser.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifPermission) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

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

        // Live cast status from CastForegroundService
        val castingPlaybackStatus by CastForegroundService.currentStatus.collectAsState()

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
                if (activeTab == null) {
                    // C2 Fix: Do NOT mount a phantom default_tab WebView before active tab is resolved
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
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
                                    var playUrl = stream.originalUrl
                                    var proxyServer: LocalStreamProxy? = null

                                    if (stream.deliveryMode == DeliveryMode.NEEDS_PROXY) {
                                        val proxy = LocalStreamProxy(8888)
                                        try {
                                            proxy.start()
                                        } catch (_: Exception) {}
                                        val token = proxy.registerStream(stream.headers, stream.cookies)
                                        val encoded = URLEncoder.encode(stream.originalUrl, "UTF-8")
                                        val phoneIp = getLocalIpAddress() ?: "127.0.0.1"
                                        playUrl = "http://$phoneIp:8888/proxy?token=$token&url=$encoded"
                                        proxyServer = proxy
                                    }

                                    val pageUrl = activeTab.url
                                    val existingWatch = container.repository.getWatchEntryByUrl(pageUrl)
                                    val startMs = if (existingWatch != null && !existingWatch.finished) {
                                        existingWatch.positionMs
                                    } else 0L

                                    val castMedia = CastMedia(
                                        url = playUrl,
                                        title = stream.title,
                                        mimeType = stream.mimeType ?: "video/mp4"
                                    )

                                    CastForegroundService.startCast(
                                        context = this@MainActivity,
                                        controller = controller,
                                        media = castMedia,
                                        pageUrl = pageUrl,
                                        proxy = proxyServer,
                                        startMs = startMs,
                                        repository = container.repository
                                    )
                                }
                            }
                        },
                        onPlayCast = {
                            scope.launch { CastForegroundService.activeControllerInstance?.play() }
                        },
                        onPauseCast = {
                            scope.launch { CastForegroundService.activeControllerInstance?.pause() }
                        },
                        onStopCast = {
                            CastForegroundService.stopCast(this@MainActivity)
                        },
                        onSeekCast = { pos ->
                            scope.launch { CastForegroundService.activeControllerInstance?.seekTo(pos) }
                        },
                        onSetSubtitle = { subUrl ->
                            CastForegroundService.setSubtitle(subUrl)
                        },
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
                        onTitleUpdated = { newTitle ->
                            activeTabId?.let { tabId ->
                                val current = tabs.firstOrNull { it.id == tabId }
                                if (current != null && current.title != newTitle && newTitle.isNotBlank()) {
                                    scope.launch {
                                        container.repository.saveTab(current.copy(title = newTitle))
                                    }
                                }
                            }
                        },
                        tabCount = tabs.size
                    )
                }
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
                            val remaining = container.repository.getAllTabsList()
                            if (remaining.isNotEmpty()) {
                                val next = remaining.firstOrNull { it.id != id } ?: remaining.first()
                                container.repository.setActiveTab(next.id)
                                activeTabId = next.id
                            } else {
                                // H6 Fix: When last tab is closed, create a fresh valid tab
                                val newId = UUID.randomUUID().toString()
                                val freshTab = TabEntity(
                                    id = newId,
                                    url = "https://www.google.com",
                                    title = "Google",
                                    position = 0,
                                    isActive = true,
                                    isIncognito = false,
                                    lastAccessedAt = System.currentTimeMillis()
                                )
                                container.repository.saveTab(freshTab)
                                container.repository.setActiveTab(newId)
                                activeTabId = newId
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
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null) {
                            candidates.add(iface.name to host)
                        }
                    }
                }
            }
            // H4 Fix: Prioritize WLAN/Ethernet interfaces over cellular (rmnet)
            val wlanIp = candidates.firstOrNull { (name, _) ->
                name.startsWith("wlan", ignoreCase = true) ||
                name.startsWith("eth", ignoreCase = true) ||
                name.startsWith("en", ignoreCase = true)
            }?.second
            return wlanIp ?: candidates.firstOrNull()?.second
        } catch (_: Exception) {}
        return null
    }
}
