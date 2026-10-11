package com.castbrowser.app.detect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class StreamProbeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun probeStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookies: String? = null
    ): DetectedStream? = withContext(Dispatchers.IO) {
        if (!StreamClassifier.isPotentialStreamUrl(url)) return@withContext null

        try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (!cookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookies)
            }
            // Probe with Range bytes=0-1
            reqBuilder.header("Range", "bytes=0-1")

            var response = client.newCall(reqBuilder.build()).execute()
            var contentType = response.header("Content-Type")
            var contentRange = response.header("Content-Range")
            var contentLength = response.header("Content-Length")?.toLongOrNull()

            if (!response.isSuccessful && response.code != 206) {
                response.close()
                // Fallback attempt: simple HEAD request without Range
                val headReq = Request.Builder().url(url)
                headers.forEach { (k, v) -> headReq.header(k, v) }
                if (!cookies.isNullOrBlank()) headReq.header("Cookie", cookies)
                val headResp = client.newCall(headReq.head().build()).execute()
                if (headResp.isSuccessful) {
                    contentType = headResp.header("Content-Type")
                    contentLength = headResp.header("Content-Length")?.toLongOrNull()
                    headResp.close()
                } else {
                    headResp.close()
                    // If URL is explicitly .m3u8, .mp4, .mpd, don't discard it just because probe was rejected
                    val lower = url.substringBefore("?").lowercase()
                    if (!lower.endsWith(".m3u8") && !lower.endsWith(".mp4") && !lower.endsWith(".mpd")) {
                        return@withContext null
                    }
                }
            } else {
                response.close()
            }

            val totalSize = if (contentRange != null && contentRange.contains("/")) {
                contentRange.substringAfter("/").toLongOrNull()
            } else {
                contentLength
            }

            val streamType = StreamClassifier.classifyStreamType(url, contentType)
            response.close()

            // Next check: Can TV fetch directly without Referer/Cookies?
            val directWorks = testDirectAccess(url)
            val deliveryMode = if (directWorks) DeliveryMode.DIRECT else DeliveryMode.NEEDS_PROXY

            // If HLS, check master playlist or DRM
            val isDrm = contentType?.contains("drm", ignoreCase = true) == true

            DetectedStream(
                originalUrl = url,
                title = extractTitleFromUrl(url),
                type = streamType,
                mimeType = contentType,
                headers = headers,
                cookies = cookies,
                deliveryMode = deliveryMode,
                isDrmProtected = isDrm,
                fileSizeBytes = totalSize
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun testDirectAccess(url: String): Boolean {
        return try {
            val simpleReq = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .build()
            val resp = client.newCall(simpleReq).execute()
            val success = resp.isSuccessful || resp.code == 206
            resp.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun extractTitleFromUrl(url: String): String {
        return try {
            val path = java.net.URI(url).path
            val filename = path.substringAfterLast("/")
            if (filename.isNotBlank()) filename else "Detected Stream"
        } catch (_: Exception) {
            "Detected Stream"
        }
    }
}
