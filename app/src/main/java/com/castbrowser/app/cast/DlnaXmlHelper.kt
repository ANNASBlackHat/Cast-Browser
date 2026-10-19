package com.castbrowser.app.cast

object DlnaXmlHelper {

    fun buildSetAvTransportUriXml(
        mediaUri: String,
        title: String,
        mimeType: String = "video/mp4",
        subtitleUrl: String? = null
    ): String {
        val subtitleMetadata = if (!subtitleUrl.isNullOrBlank()) {
            val subMime = if (subtitleUrl.endsWith(".vtt", ignoreCase = true)) "text/vtt" else "text/srt"
            val subType = if (subtitleUrl.endsWith(".vtt", ignoreCase = true)) "vtt" else "srt"
            """
&lt;res protocolInfo="http-get:*:${escapeXml(subMime)}:*"&gt;${escapeXml(subtitleUrl)}&lt;/res&gt;
&lt;sec:CaptionInfo xmlns:sec="http://www.sec.org/dlna" sec:type="${escapeXml(subType)}"&gt;${escapeXml(subtitleUrl)}&lt;/sec:CaptionInfo&gt;
&lt;sec:CaptionInfoEx xmlns:sec="http://www.sec.org/dlna" sec:type="${escapeXml(subType)}"&gt;${escapeXml(subtitleUrl)}&lt;/sec:CaptionInfoEx&gt;
            """.trimIndent()
        } else ""

        val didlMetadata = """
&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.org/dlna"&gt;
&lt;item id="0" parentID="-1" restricted="1"&gt;
&lt;dc:title&gt;${escapeXml(title)}&lt;/dc:title&gt;
&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
&lt;res protocolInfo="http-get:*:${escapeXml(mimeType)}:*"&gt;${escapeXml(mediaUri)}&lt;/res&gt;
$subtitleMetadata
&lt;/item&gt;
&lt;/DIDL-Lite&gt;
        """.trimIndent()

        return """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>${escapeXml(mediaUri)}</CurrentURI>
<CurrentURIMetaData>$didlMetadata</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>
        """.trimIndent()
    }

    fun buildPlayXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>
    """.trimIndent()

    fun buildPauseXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Pause>
</s:Body>
</s:Envelope>
    """.trimIndent()

    fun buildStopXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>
    """.trimIndent()

    fun buildSeekXml(targetSeconds: Long): String {
        val relTime = formatRelTime(targetSeconds)
        return """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Unit>REL_TIME</Unit>
<Target>$relTime</Target>
</u:Seek>
</s:Body>
</s:Envelope>
        """.trimIndent()
    }

    fun buildGetPositionInfoXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetPositionInfo>
</s:Body>
</s:Envelope>
    """.trimIndent()

    fun formatRelTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun parseRelTimeToSeconds(timeStr: String): Long {
        return try {
            val parts = timeStr.trim().split(":")
            if (parts.size == 3) {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].substringBefore(".").toLongOrNull() ?: 0L
                h * 3600 + m * 60 + s
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun escapeXml(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
