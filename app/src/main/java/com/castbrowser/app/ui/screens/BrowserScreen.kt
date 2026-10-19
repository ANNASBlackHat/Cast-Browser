package com.castbrowser.app.ui.screens

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.castbrowser.app.browser.WebViewPool
import com.castbrowser.app.cast.CastDevice
import com.castbrowser.app.cast.PlaybackStatus
import com.castbrowser.app.data.AppRepository
import com.castbrowser.app.data.entity.HistoryVisitEntity
import com.castbrowser.app.data.entity.TabEntity
import com.castbrowser.app.detect.DeliveryMode
import com.castbrowser.app.detect.DetectedStream
import com.castbrowser.app.ui.components.DevicePickerSheet
import com.castbrowser.app.ui.components.RemoteControlSheet
import com.castbrowser.app.ui.components.VideoDetectionSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    repository: AppRepository,
    webViewPool: WebViewPool,
    activeTab: TabEntity?,
    discoveredStreams: List<DetectedStream>,
    availableDevices: List<CastDevice>,
    isScanningDevices: Boolean,
    castingPlaybackStatus: PlaybackStatus?,
    onOpenTabs: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartScanDevices: () -> Unit,
    onStartCast: (CastDevice, DetectedStream) -> Unit,
    onPlayCast: () -> Unit,
    onPauseCast: () -> Unit,
    onStopCast: () -> Unit,
    onSeekCast: (Long) -> Unit,
    onSetSubtitle: ((String) -> Unit)? = null,
    onUrlNavigated: (String) -> Unit,
    onTitleUpdated: (String) -> Unit = {},
    tabCount: Int = 1
) {
    var isEditingUrl by remember { mutableStateOf(false) }
    var editUrlText by remember { mutableStateOf("") }

    var currentDisplayUrl by remember(activeTab?.url) { mutableStateOf(activeTab?.url ?: "") }
    var currentDisplayTitle by remember(activeTab?.title) { mutableStateOf(activeTab?.title ?: "") }

    var showVideoSheet by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var selectedStreamToCast by remember { mutableStateOf<DetectedStream?>(null) }
    var showRemoteSheet by remember { mutableStateOf(false) }

    val recentVisits by repository.getAllHistory().collectAsState(initial = emptyList())

    val currentTabId = activeTab?.id ?: "default_tab"
    val initialTabUrl = activeTab?.url ?: "https://www.google.com"

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // H5 Fix: only intercept back press if URL bar is open or WebView can go back
    androidx.activity.compose.BackHandler(enabled = isEditingUrl || webViewInstance?.canGoBack() == true) {
        if (isEditingUrl) {
            isEditingUrl = false
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        }
    }

    // H1 Fix: Only auto-open remote sheet when casting transitions from inactive to active
    var wasCasting by remember { mutableStateOf(false) }
    LaunchedEffect(castingPlaybackStatus != null) {
        val isNowCasting = castingPlaybackStatus != null
        if (isNowCasting && !wasCasting) {
            showRemoteSheet = true
        }
        wasCasting = isNowCasting
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingUrl) {
                        OutlinedTextField(
                            value = editUrlText,
                            onValueChange = { editUrlText = it },
                            singleLine = true,
                            placeholder = { Text("Search or type URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Chrome-style display chip
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable {
                                    isEditingUrl = true
                                    editUrlText = "" // Empty by default like Chrome!
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val host = try {
                                    java.net.URI(currentDisplayUrl).host ?: currentDisplayUrl
                                } catch (_: Exception) {
                                    currentDisplayUrl
                                }
                                Text(
                                    text = host.ifBlank { "Search or type URL" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    color = if (host.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isEditingUrl) {
                        IconButton(onClick = {
                            if (editUrlText.isNotBlank()) {
                                val target = if (editUrlText.startsWith("http://") || editUrlText.startsWith("https://")) {
                                    editUrlText
                                } else {
                                    "https://www.google.com/search?q=${java.net.URLEncoder.encode(editUrlText, "UTF-8")}"
                                }
                                webViewInstance?.loadUrl(target)
                            }
                            isEditingUrl = false
                        }) {
                            Text("Go")
                        }
                        IconButton(onClick = { isEditingUrl = false }) {
                            Text("✕")
                        }
                    } else {
                        // Cast Remote Control Button (shown if active cast is running)
                        if (castingPlaybackStatus != null) {
                            IconButton(onClick = { showRemoteSheet = true }) {
                                Text("📺")
                            }
                        }

                        // History Button
                        IconButton(onClick = onOpenHistory) {
                            Text("⏱")
                        }

                        // Video Badge Button
                        IconButton(onClick = { showVideoSheet = true }) {
                            Badge(
                                containerColor = if (discoveredStreams.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text("${discoveredStreams.size}")
                            }
                        }

                        // Tab Switcher Button with Counter Badge (Chrome style)
                        IconButton(onClick = onOpenTabs) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.onSurface
                                ),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = "$tabCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (showRemoteSheet && castingPlaybackStatus != null) {
                RemoteControlSheet(
                    title = selectedStreamToCast?.title ?: "Playing on TV",
                    status = castingPlaybackStatus,
                    onPlay = onPlayCast,
                    onPause = onPauseCast,
                    onStop = {
                        onStopCast()
                        showRemoteSheet = false
                    },
                    onSeek = onSeekCast,
                    onSetSubtitle = onSetSubtitle,
                    onDismiss = { showRemoteSheet = false }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            key(currentTabId) {
                AndroidView(
                    factory = { _ ->
                        webViewPool.getOrCreateWebView(
                            tabId = currentTabId,
                            initialUrl = initialTabUrl,
                            isIncognito = false,
                            onTitleUpdate = { title ->
                                currentDisplayTitle = title
                                onTitleUpdated(title)
                            },
                            onUrlUpdate = { updatedUrl ->
                                currentDisplayUrl = updatedUrl
                                onUrlNavigated(updatedUrl)
                            }
                        ).also {
                            webViewInstance = it
                        }
                    },
                    update = { webView ->
                        webViewInstance = webView
                        val targetUrl = activeTab?.url
                        if (!targetUrl.isNullOrBlank() && webView.url != targetUrl && targetUrl != "about:blank") {
                            webView.loadUrl(targetUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Chrome-style URL edit dropdown overlay
            if (isEditingUrl) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Current Visited URL card with Edit button
                        if (currentDisplayUrl.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = currentDisplayTitle.ifBlank { "Current Page" },
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = currentDisplayUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Edit button: loads current URL into input field
                                    FilledTonalButton(onClick = {
                                        editUrlText = currentDisplayUrl
                                    }) {
                                        Text("Edit ✏️")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Recent Visits Quick List
                        Text(
                            text = "Recent History",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val topRecent = recentVisits.take(6)
                        if (topRecent.isEmpty()) {
                            Text(
                                text = "No recent history yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(topRecent) { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                isEditingUrl = false
                                                webViewInstance?.loadUrl(item.url)
                                            },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🔗 ", style = MaterialTheme.typography.bodyMedium)
                                            Column {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = item.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Video Candidates Sheet
            if (showVideoSheet) {
                VideoDetectionSheet(
                    streams = discoveredStreams,
                    onSelectStream = { stream ->
                        showVideoSheet = false
                        selectedStreamToCast = stream
                        onStartScanDevices()
                        showDevicePicker = true
                    },
                    onDismiss = { showVideoSheet = false }
                )
            }

            // Device Picker Sheet
            if (showDevicePicker) {
                DevicePickerSheet(
                    devices = availableDevices,
                    isScanning = isScanningDevices,
                    onSelectDevice = { device ->
                        showDevicePicker = false
                        selectedStreamToCast?.let { stream ->
                            onStartCast(device, stream)
                        }
                    },
                    onRefreshScan = onStartScanDevices,
                    onDismiss = { showDevicePicker = false }
                )
            }
        }
    }
}
