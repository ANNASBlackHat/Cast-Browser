package com.castbrowser.app.detect

enum class StreamType {
    MP4,
    HLS,
    DASH,
    WEBM,
    UNKNOWN
}

enum class DeliveryMode {
    DIRECT,
    NEEDS_PROXY
}

data class StreamQuality(
    val label: String,
    val resolution: String? = null,
    val bandwidth: Long? = null,
    val url: String
)

data class DetectedStream(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalUrl: String,
    val title: String,
    val type: StreamType,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    val deliveryMode: DeliveryMode = DeliveryMode.DIRECT,
    val isDrmProtected: Boolean = false,
    val drmReason: String? = null,
    val qualities: List<StreamQuality> = emptyList(),
    val durationSeconds: Long? = null,
    val fileSizeBytes: Long? = null,
    val discoveredAt: Long = System.currentTimeMillis()
)
