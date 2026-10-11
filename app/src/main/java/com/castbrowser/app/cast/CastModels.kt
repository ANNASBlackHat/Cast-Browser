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
    val errorMessage: String? = null
)

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
    suspend fun setVolume(level: Float)
    fun release()
}
