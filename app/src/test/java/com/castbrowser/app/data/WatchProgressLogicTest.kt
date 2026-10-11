package com.castbrowser.app.data

import com.castbrowser.app.data.dao.WatchEntryDao
import com.castbrowser.app.data.entity.WatchEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressLogicTest {

    @Test
    fun testFinishedThresholdCalculation() {
        val durationMs = 100_000L // 100 seconds
        val positionMs94 = 94_000L // 94%
        val positionMs96 = 96_000L // 96%

        val finished94 = durationMs > 0 && positionMs94 >= (durationMs * 0.95)
        val finished96 = durationMs > 0 && positionMs96 >= (durationMs * 0.95)

        assertEquals(false, finished94)
        assertEquals(true, finished96)
    }

    @Test
    fun testHostExtraction() {
        val url = "https://subdomain.streamingsite.com/watch/movie-123?ref=feed"
        val host = java.net.URI(url).host
        assertEquals("subdomain.streamingsite.com", host)
    }
}
