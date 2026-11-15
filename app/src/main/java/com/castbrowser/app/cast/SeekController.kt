package com.castbrowser.app.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SeekController(
    private val scope: CoroutineScope,
    private val onSeekTarget: suspend (Long) -> Unit,
    private val statusProvider: () -> PlaybackStatus,
    private val onOptimisticPositionUpdate: (Long) -> Unit
) {
    private var pendingMs: Long? = null
    private var debounceJob: Job? = null

    /**
     * Relative seek (+10s or -10s).
     * Rapid taps accumulate and send a single seek after 400ms debounce.
     */
    fun seekBy(deltaMs: Long) {
        val s = statusProvider()
        val base = pendingMs ?: s.estimatedPositionMs()
        val max = if (s.durationMs > 0L) (s.durationMs - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE
        val target = (base + deltaMs).coerceIn(0L, max)
        pendingMs = target
        onOptimisticPositionUpdate(target)

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(400) // merge rapid taps
            val t = pendingMs ?: return@launch
            pendingMs = null
            onSeekTarget(t)
        }
    }

    /**
     * Absolute seek (e.g. from slider scrub).
     */
    fun seekTo(targetMs: Long) {
        val s = statusProvider()
        val max = if (s.durationMs > 0L) (s.durationMs - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE
        val target = targetMs.coerceIn(0L, max)
        pendingMs = target
        onOptimisticPositionUpdate(target)

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(400)
            val t = pendingMs ?: return@launch
            pendingMs = null
            onSeekTarget(t)
        }
    }
}
