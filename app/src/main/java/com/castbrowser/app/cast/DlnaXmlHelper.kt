package com.castbrowser.app.cast

import java.util.Locale

enum class SubVariant { PV, RES_SRT, SEC }

object DlnaXmlHelper {

    private val UPNP_TIME_REGEX = Regex("^[+]?(?:([0-9]+):)?([0-9]{1,2}):([0-9]{1,2})([.][0-9]+)?$")

    fun xmlEscape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Parses UPnP time string (e.g. "01:23:45", "00:05:10.500", "0:02:10", "2:10").
     * Returns null for null, empty or "NOT_IMPLEMENTED".
     */
    fun parseUpnpTime(s: String?): Long? {
        val m = UPNP_TIME_REGEX.find(s?.trim().orEmpty()) ?: return null
        val (h, mi, se, frac) = m.destructured
        val fracMs = if (frac.isEmpty()) 0L else (frac.toDouble() * 1000).toLong()
        val hours = if (h.isEmpty()) 0L else h.toLong()
        return ((hours * 60 + mi.toLong()) * 60 + se.toLong()) * 1000L + fracMs
    }

    /**
     * Formats milliseconds into whole seconds HH:MM:SS for UPnP Seek and Position.
     */
    fun formatUpnpTime(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /**
     * Legacy helper kept for backward compatibility.
     */
    fun formatRelTime(seconds: Long): String = formatUpnpTime(seconds * 1000L)

    /**
     * Legacy helper kept for backward compatibility.
     */
    fun parseRelTimeToSeconds(timeStr: String): Long {
        val ms = parseUpnpTime(timeStr) ?: return 0L
        return ms / 1000L
    }

    /**
     * Builds standard DIDL-Lite metadata with all vendor subtitle variants:
     * - PacketVideo (pv:subtitleFileType, pv:subtitleFileUri)
     * - Generic extra <res protocolInfo="http-get:*:text/srt:*">
     * - Samsung sec:CaptionInfoEx & sec:CaptionInfo
     */
    fun buildDidl(
        title: String,
        videoUrl: String,
        mimeType: String = "video/mp4",
        subtitleUrl: String? = null,
        variants: Set<SubVariant> = setOf(SubVariant.PV, SubVariant.RES_SRT, SubVariant.SEC)
    ): String {
        val isHls = mimeType.contains("mpegurl", ignoreCase = true) ||
                videoUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
        val flags = if (isHls) {
            "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        } else {
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }

        val sub = subtitleUrl?.takeIf { it.isNotBlank() }?.let { xmlEscape(it) }
        val pvAttr = if (sub != null && SubVariant.PV in variants) {
            " pv:subtitleFileType='srt' pv:subtitleFileUri='$sub'"
        } else ""

        val extra = buildString {
            if (sub != null && SubVariant.RES_SRT in variants) {
                append("<res protocolInfo='http-get:*:text/srt:*'>$sub</res>")
            }
            if (sub != null && SubVariant.SEC in variants) {
                append("<sec:CaptionInfoEx sec:type='srt'>$sub</sec:CaptionInfoEx>")
                append("<sec:CaptionInfo sec:type='srt'>$sub</sec:CaptionInfo>")
            }
        }

        return "<DIDL-Lite xmlns='urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/' " +
                "xmlns:dc='http://purl.org/dc/elements/1.1/' " +
                "xmlns:upnp='urn:schemas-upnp-org:metadata-1-0/upnp/' " +
                "xmlns:dlna='urn:schemas-dlna-org:metadata-1-0/' " +
                "xmlns:sec='http://www.sec.co.kr/' " +
                "xmlns:pv='http://www.pv.com/pvns/'>" +
                "<item id='0' parentID='-1' restricted='1'>" +
                "<dc:title>${xmlEscape(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo='http-get:*:$mimeType:$flags'$pvAttr>${xmlEscape(videoUrl)}</res>" +
                "$extra</item></DIDL-Lite>"
    }

    /**
     * Builds SOAP Envelope for SetAVTransportURI with properly double-escaped DIDL-Lite metadata.
     */
    fun buildSetAvTransportUriXml(
        mediaUri: String,
        title: String,
        mimeType: String = "video/mp4",
        subtitleUrl: String? = null,
        variants: Set<SubVariant> = setOf(SubVariant.PV, SubVariant.RES_SRT, SubVariant.SEC)
    ): String {
        val didl = buildDidl(title, mediaUri, mimeType, subtitleUrl, variants)
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>${xmlEscape(mediaUri)}</CurrentURI>
<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>""".trimIndent()
    }

    fun buildPlayXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>""".trimIndent()

    fun buildPauseXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Pause>
</s:Body>
</s:Envelope>""".trimIndent()

    fun buildStopXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>""".trimIndent()

    fun buildSeekXml(targetMs: Long, unit: String = "REL_TIME"): String {
        val timeStr = formatUpnpTime(targetMs)
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Unit>$unit</Unit>
<Target>$timeStr</Target>
</u:Seek>
</s:Body>
</s:Envelope>""".trimIndent()
    }

    fun buildGetPositionInfoXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetPositionInfo>
</s:Body>
</s:Envelope>""".trimIndent()

    fun buildGetTransportInfoXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetTransportInfo>
</s:Body>
</s:Envelope>""".trimIndent()

    fun buildGetTransportActionsXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetCurrentTransportActions xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:GetCurrentTransportActions>
</s:Body>
</s:Envelope>""".trimIndent()
}
