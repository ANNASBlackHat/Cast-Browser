package com.castbrowser.app.cast

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeekControllerTest {

    @Test
    fun `rapid taps accumulate and execute single seek after debounce`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var executedSeek: Long? = null
        var optimisticPos: Long = 0L

        var currentStatus = PlaybackStatus(
            state = PlaybackState.PLAYING,
            positionMs = 30_000L,
            durationMs = 300_000L,
            polledAtMs = 0L
        )

        val controller = SeekController(
            scope = testScope,
            onSeekTarget = { target -> executedSeek = target },
            statusProvider = { currentStatus },
            onOptimisticPositionUpdate = { pos -> optimisticPos = pos }
        )

        // 5 rapid taps on +10s (+10_000ms each)
        repeat(5) {
            controller.seekBy(10_000L)
            testScope.advanceTimeBy(100) // less than 400ms debounce
        }

        // Before debounce fires, optimistic update shows 80s (30s + 50s)
        assertThat(optimisticPos).isEqualTo(80_000L)
        // No seek dispatched yet
        assertThat(executedSeek).isNull()

        // Advance past 400ms debounce
        testScope.advanceTimeBy(400)

        // Single seek dispatched with +50s accumulated
        assertThat(executedSeek).isEqualTo(80_000L)
    }

    @Test
    fun `seekBy clamps negative target to 0`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var executedSeek: Long? = null
        val currentStatus = PlaybackStatus(
            state = PlaybackState.PLAYING,
            positionMs = 5_000L, // 5s in
            durationMs = 100_000L
        )

        val controller = SeekController(
            scope = testScope,
            onSeekTarget = { executedSeek = it },
            statusProvider = { currentStatus },
            onOptimisticPositionUpdate = {}
        )

        // -10s when at 5s
        controller.seekBy(-10_000L)
        testScope.advanceTimeBy(500)

        assertThat(executedSeek).isEqualTo(0L)
    }

    @Test
    fun `seekBy clamps target to duration minus 1s`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var executedSeek: Long? = null
        val currentStatus = PlaybackStatus(
            state = PlaybackState.PLAYING,
            positionMs = 95_000L,
            durationMs = 100_000L // max target should be 99_000L
        )

        val controller = SeekController(
            scope = testScope,
            onSeekTarget = { executedSeek = it },
            statusProvider = { currentStatus },
            onOptimisticPositionUpdate = {}
        )

        // +10s when at 95s
        controller.seekBy(10_000L)
        testScope.advanceTimeBy(500)

        assertThat(executedSeek).isEqualTo(99_000L)
    }
}
