package com.castbrowser.app.ui.components

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
import com.castbrowser.app.cast.PlaybackState
import com.castbrowser.app.cast.PlaybackStatus
import com.castbrowser.app.detect.DetectedSubtitle

@Composable
fun RemoteControlSheet(
    title: String,
    status: PlaybackStatus,
    detectedSubtitles: List<DetectedSubtitle> = emptyList(),
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: ((Long) -> Unit)? = null,
    onSetSubtitle: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var subtitleInput by remember { mutableStateOf("") }

    if (showSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text("Select Subtitles") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Subtitles are applied on TV. Choose a detected track or enter a custom URL:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (detectedSubtitles.isNotEmpty()) {
                        Text(
                            text = "Detected Tracks",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSetSubtitle?.invoke("")
                                            showSubtitleDialog = false
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "None (Turn Off Subtitles)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                            items(detectedSubtitles) { sub ->
                                val labelText = if (sub.language.isNullOrBlank()) sub.label else "${sub.label} (${sub.language})"
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSetSubtitle?.invoke(sub.url)
                                            showSubtitleDialog = false
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = labelText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = "Custom Subtitle URL (.srt / .vtt / .ass):",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = subtitleInput,
                        onValueChange = { subtitleInput = it },
                        placeholder = { Text("https://example.com/subs.srt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (subtitleInput.isNotBlank()) {
                        onSetSubtitle?.invoke(subtitleInput.trim())
                    }
                    showSubtitleDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Casting to TV",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    if (onSetSubtitle != null) {
                        TextButton(onClick = { showSubtitleDialog = true }) {
                            Text("Subtitles")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Hide")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Time and Progress (shown only when TV reports valid duration)
            val positionSec = status.positionMs / 1000L
            val durationSec = status.durationMs / 1000L

            if (durationSec > 0) {
                val progressFraction = (positionSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
                var isDragging by remember { mutableStateOf(false) }
                var dragFraction by remember { mutableFloatStateOf(0f) }

                val currentFraction = if (isDragging) dragFraction else progressFraction
                val displayPositionSec = if (isDragging) (dragFraction * durationSec).toLong() else positionSec

                Slider(
                    value = currentFraction,
                    enabled = status.canSeek,
                    onValueChange = { frac ->
                        isDragging = true
                        dragFraction = frac
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeek((dragFraction * durationSec * 1000L).toLong())
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = DlnaXmlHelper.formatRelTime(displayPositionSec),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = DlnaXmlHelper.formatRelTime(durationSec),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Text(
                    text = "Playing on TV • Use TV remote or buttons below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!status.canSeek) {
                Text(
                    text = "Seeking is not supported for this stream (live or non-seekable)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (onSeekBy != null) onSeekBy(-10_000L)
                        else onSeek((status.positionMs - 10_000L).coerceAtLeast(0L))
                    },
                    enabled = status.canSeek
                ) {
                    Text("-10s")
                }

                if (status.state == PlaybackState.PLAYING) {
                    Button(onClick = onPause) {
                        Text("Pause")
                    }
                } else {
                    Button(onClick = onPlay) {
                        Text("Play")
                    }
                }

                Button(
                    onClick = {
                        if (onSeekBy != null) onSeekBy(10_000L)
                        else onSeek(status.positionMs + 10_000L)
                    },
                    enabled = status.canSeek
                ) {
                    Text("+10s")
                }

                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}
