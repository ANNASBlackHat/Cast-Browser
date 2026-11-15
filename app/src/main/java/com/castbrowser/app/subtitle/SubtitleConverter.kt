package com.castbrowser.app.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

object SubtitleConverter {

    private val VTT_TIMESTAMP_REGEX = Regex(
        """(?:(\d{2,}):)?(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(?:(\d{2,}):)?(\d{2}):(\d{2})\.(\d{3})"""
    )
    private val ASS_DIALOGUE_REGEX = Regex(
        """^Dialogue:\s*[^,]*,([^,]*),([^,]*),(?:[^,]*,){6}(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*\}""")

    /**
     * Decodes subtitle bytes into a String.
     * Strips UTF-8 BOM if present.
     * If UTF-8 decoding fails with malformed bytes, falls back to Windows-1252 (ANSI).
     */
    fun decodeSubtitle(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val hasBom = bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes

        val strictDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            strictDecoder.decode(ByteBuffer.wrap(body)).toString()
        } catch (_: Exception) {
            try {
                String(body, Charset.forName("windows-1252"))
            } catch (_: Exception) {
                String(body, Charsets.ISO_8859_1)
            }
        }
    }

    /**
     * Converts WebVTT string to plain, compliant SubRip (.srt) format.
     */
    fun webVttToSrt(vtt: String): String {
        val lines = vtt.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val srtBuilder = StringBuilder()
        var cueIndex = 1
        var inCue = false
        var cueTextLines = mutableListOf<String>()
        var currentTimestampLine: String? = null
        var inNoteOrStyle = false

        fun flushCue() {
            if (currentTimestampLine != null && cueTextLines.isNotEmpty()) {
                val text = cueTextLines.joinToString("\n").trim()
                if (text.isNotEmpty()) {
                    if (cueIndex > 1) srtBuilder.append("\n\n")
                    srtBuilder.append(cueIndex).append("\n")
                    srtBuilder.append(currentTimestampLine).append("\n")
                    srtBuilder.append(text)
                    cueIndex++
                }
            }
            currentTimestampLine = null
            cueTextLines.clear()
            inCue = false
        }

        for (rawLine in lines) {
            val line = rawLine.trim()

            if (line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION")) {
                flushCue()
                inNoteOrStyle = true
                continue
            }

            if (inNoteOrStyle) {
                if (line.isEmpty()) {
                    inNoteOrStyle = false
                }
                continue
            }

            if (line.startsWith("WEBVTT")) {
                continue
            }

            val timestampMatch = VTT_TIMESTAMP_REGEX.find(line)
            if (timestampMatch != null) {
                flushCue()
                inCue = true

                val (sH, sM, sS, sMs, eH, eM, eS, eMs) = timestampMatch.destructured
                val startHour = if (sH.isEmpty()) 0 else sH.toInt()
                val endHour = if (eH.isEmpty()) 0 else eH.toInt()

                val srtStart = String.format(
                    Locale.US,
                    "%02d:%02d:%02d,%03d",
                    startHour,
                    sM.toInt(),
                    sS.toInt(),
                    sMs.toInt()
                )
                val srtEnd = String.format(
                    Locale.US,
                    "%02d:%02d:%02d,%03d",
                    endHour,
                    eM.toInt(),
                    eS.toInt(),
                    eMs.toInt()
                )

                currentTimestampLine = "$srtStart --> $srtEnd"
                continue
            }

            if (inCue) {
                if (line.isEmpty()) {
                    flushCue()
                } else {
                    // Strip tags like <c.yellow>, </c>, <v Speaker>, <b>, etc.
                    val cleanLine = line.replace(HTML_TAG_REGEX, "")
                    if (cleanLine.isNotBlank()) {
                        cueTextLines.add(cleanLine)
                    }
                }
            }
        }

        flushCue()
        return srtBuilder.toString().trim()
    }

    /**
     * Converts ASS/SSA string to clean SubRip (.srt) format.
     */
    fun assToSrt(ass: String): String {
        val lines = ass.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val srtBuilder = StringBuilder()
        var cueIndex = 1

        for (rawLine in lines) {
            val line = rawLine.trim()
            val match = ASS_DIALOGUE_REGEX.find(line) ?: continue
            val (startStr, endStr, textRaw) = match.destructured

            val srtStart = formatAssTime(startStr) ?: continue
            val srtEnd = formatAssTime(endStr) ?: continue

            // Strip ASS override codes like {\an8}, {\pos(10,20)}, etc.
            val cleanText = textRaw
                .replace(ASS_OVERRIDE_REGEX, "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .trim()

            if (cleanText.isEmpty()) continue

            if (cueIndex > 1) srtBuilder.append("\n\n")
            srtBuilder.append(cueIndex).append("\n")
            srtBuilder.append("$srtStart --> $srtEnd\n")
            srtBuilder.append(cleanText)
            cueIndex++
        }

        return srtBuilder.toString().trim()
    }

    private fun formatAssTime(time: String): String? {
        val parts = time.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val secParts = parts[2].split(".")
        val s = secParts[0].toIntOrNull() ?: return null
        val cs = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toIntOrNull() ?: 0 else 0
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, cs)
    }

    /**
     * Normalizes an existing SRT file (ensuring valid numbering and clean newlines).
     */
    fun normalizeSrt(srt: String): String {
        val lines = srt.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val srtBuilder = StringBuilder()
        var cueIndex = 1
        var currentTimestamp: String? = null
        var cueLines = mutableListOf<String>()

        val srtTimestampRegex = Regex("""(\d{2}:\d{2}:\d{2}[,\.]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,\.]\d{3})""")

        fun flush() {
            if (currentTimestamp != null && cueLines.isNotEmpty()) {
                if (cueIndex > 1) srtBuilder.append("\n\n")
                srtBuilder.append(cueIndex).append("\n")
                srtBuilder.append(currentTimestamp).append("\n")
                srtBuilder.append(cueLines.joinToString("\n").trim())
                cueIndex++
            }
            currentTimestamp = null
            cueLines.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            val tsMatch = srtTimestampRegex.find(line)
            if (tsMatch != null) {
                flush()
                val (start, end) = tsMatch.destructured
                currentTimestamp = "${start.replace('.', ',')} --> ${end.replace('.', ',')}"
            } else if (currentTimestamp != null) {
                if (line.isEmpty()) {
                    flush()
                } else if (!line.all { it.isDigit() }) {
                    cueLines.add(line.replace(HTML_TAG_REGEX, ""))
                }
            }
        }
        flush()
        return srtBuilder.toString().trim()
    }

    /**
     * Converts any supported subtitle byte payload into clean UTF-8 SRT.
     */
    fun convertToSrt(bytes: ByteArray, sourceUrl: String = ""): String {
        val text = decodeSubtitle(bytes)
        val cleanUrl = sourceUrl.substringBefore("?").lowercase()

        return when {
            text.startsWith("WEBVTT") || cleanUrl.endsWith(".vtt") -> webVttToSrt(text)
            text.contains("[Events]") || cleanUrl.endsWith(".ass") || cleanUrl.endsWith(".ssa") -> assToSrt(text)
            else -> normalizeSrt(text)
        }
    }
}
