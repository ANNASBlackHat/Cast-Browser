package com.castbrowser.app.cast

import kotlinx.coroutines.flow.StateFlow

enum class CastProtocol {
    DLNA,
    CHROMECAST
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR
}

data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1.0f,
    val errorMessage: String? = null,
    val polledAtMs: Long = 0L,
    val canSeek: Boolean = true
)

fun PlaybackStatus.estimatedPositionMs(now: Long = System.currentTimeMillis()): Long {
    return if (state == PlaybackState.PLAYING && polledAtMs > 0L) {
        val elapsed = (now - polledAtMs).coerceAtLeast(0L)
        val max = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        (positionMs + elapsed).coerceAtMost(max)
    } else {
        positionMs
    }
}

data class CastMedia(
    val url: String,
    val title: String,
    val mimeType: String = "video/mp4",
    val subtitleUrl: String? = null,
    val headers: Map<String, String> = emptyMap()
)

interface CastDevice {
    val id: String
    val name: String
    val address: String
    val protocol: CastProtocol
}

interface CastController {
    val status: StateFlow<PlaybackStatus>
    suspend fun load(media: CastMedia, startMs: Long = 0L)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun seekBy(deltaMs: Long) = Unit
    suspend fun setVolume(level: Float)
    fun showOptimisticPosition(positionMs: Long) = Unit
    fun release()
}
