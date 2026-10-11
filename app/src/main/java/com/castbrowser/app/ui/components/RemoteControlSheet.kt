package com.castbrowser.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.castbrowser.app.cast.DlnaXmlHelper
import com.castbrowser.app.cast.PlaybackState
import com.castbrowser.app.cast.PlaybackStatus

@Composable
fun RemoteControlSheet(
    title: String,
    status: PlaybackStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
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
                TextButton(onClick = onDismiss) {
                    Text("Hide")
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
                Slider(
                    value = progressFraction,
                    onValueChange = { frac ->
                        onSeek((frac * durationSec * 1000L).toLong())
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = DlnaXmlHelper.formatRelTime(positionSec),
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

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { onSeek((status.positionMs - 10_000L).coerceAtLeast(0L)) }) {
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

                Button(onClick = { onSeek(status.positionMs + 10_000L) }) {
                    Text("+10s")
                }

                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}
