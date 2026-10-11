package com.castbrowser.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.castbrowser.app.cast.DlnaXmlHelper
import com.castbrowser.app.data.AppRepository
import com.castbrowser.app.data.entity.WatchEntryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: AppRepository,
    onOpenUrl: (String) -> Unit
) {
    val continueWatching by repository.getContinueWatching().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CastBrowser") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (continueWatching.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active videos to resume.\nBrowse a video page to start casting!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(continueWatching) { item ->
                        WatchEntryCard(item = item, onClick = { onOpenUrl(item.pageUrl) })
                    }
                }
            }
        }
    }
}

@Composable
fun WatchEntryCard(
    item: WatchEntryEntity,
    onClick: () -> Unit
) {
    val durationSec = item.durationMs / 1000L
    val positionSec = item.positionMs / 1000L
    val progress = if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.seriesKey ?: item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                if (item.season != null && item.episode != null) {
                    Text(
                        text = "S${item.season} E${item.episode}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${positionSec / 60}m / ${durationSec / 60}m",
                    style = MaterialTheme.typography.labelSmall
                )
                val remainingMin = ((durationSec - positionSec) / 60).coerceAtLeast(0)
                Text(
                    text = "$remainingMin min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
