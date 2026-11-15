package com.castbrowser.app.cast

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class DlnaDevice(
    override val id: String,
    override val name: String,
    override val address: String,
    val controlUrl: String,
    override val protocol: CastProtocol = CastProtocol.DLNA
) : CastDevice

class SoapFault(val code: Int, message: String) : Exception("SOAP Fault [$code]: $message")

class DlnaCastController(
    private val device: DlnaDevice,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : CastController {

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val soapMutex = Mutex()
    private var pollJob: Job? = null
    private val soapMediaType = "text/xml; charset=utf-8".toMediaType()

    private var activeMedia: CastMedia? = null
    @Volatile
    private var suppressPollsUntilMs: Long = 0L

    private val seekController = SeekController(
        scope = scope,
        onSeekTarget = { target -> executeSeek(target) },
        statusProvider = { _status.value },
        onOptimisticPositionUpdate = { pos -> showOptimisticPosition(pos) }
    )

    override fun showOptimisticPosition(positionMs: Long) {
        _status.value = _status.value.copy(positionMs = positionMs)
    }

    override suspend fun load(media: CastMedia, startMs: Long) = withContext(Dispatchers.IO) {
        activeMedia = media
        _status.value = _status.value.copy(state = PlaybackState.BUFFERING)

        val setUriSoap = DlnaXmlHelper.buildSetAvTransportUriXml(
            mediaUri = media.url,
            title = media.title,
            mimeType = media.mimeType,
            subtitleUrl = media.subtitleUrl
        )

        try {
            sendSoapAction("SetAVTransportURI", setUriSoap)
            play()

            // If a start position is specified (e.g. resume or subtitle change), wait for PLAYING and seek
            if (startMs > 0L) {
                waitForPlaybackState(setOf(PlaybackState.PLAYING, PlaybackState.PAUSED), timeoutMs = 4000L)
                delay(300)
                executeSeek(startMs)
            }

            startPolling()
        } catch (e: Exception) {
            println("[DlnaCastController] Failed to load media on TV: ${e.message}")
            _status.value = _status.value.copy(
                state = PlaybackState.ERROR,
                errorMessage = "Failed to load media on TV: ${e.message}"
            )
        }
    }

    override suspend fun play() = withContext(Dispatchers.IO) {
        try {
            sendSoapAction("Play", DlnaXmlHelper.buildPlayXml())
            _status.value = _status.value.copy(state = PlaybackState.PLAYING)
        } catch (e: Exception) {
            println("[DlnaCastController] Play error: ${e.message}")
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        try {
            sendSoapAction("Pause", DlnaXmlHelper.buildPauseXml())
            _status.value = _status.value.copy(state = PlaybackState.PAUSED)
        } catch (e: Exception) {
            println("[DlnaCastController] Pause error: ${e.message}")
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            sendSoapAction("Stop", DlnaXmlHelper.buildStopXml())
        } catch (_: Exception) {}
        _status.value = _status.value.copy(state = PlaybackState.STOPPED)
        stopPolling()
    }

    override suspend fun seekTo(positionMs: Long) {
        seekController.seekTo(positionMs)
    }

    override suspend fun seekBy(deltaMs: Long) {
        seekController.seekBy(deltaMs)
    }

    override suspend fun setVolume(level: Float) {
        // Optional rendering control
    }

    override fun release() {
        stopPolling()
        scope.cancel()
    }

    /**
     * Executes the actual seek call with retries and fallbacks (A3 & A4).
     */
    private suspend fun executeSeek(targetMs: Long) = withContext(Dispatchers.IO) {
        val duration = _status.value.durationMs
        val max = if (duration > 0L) (duration - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE
        val clampedMs = targetMs.coerceIn(0L, max)

        println("[DlnaCastController] Executing seek to $clampedMs ms (duration: $duration ms)")

        waitForPlaybackState(setOf(PlaybackState.PLAYING, PlaybackState.PAUSED), timeoutMs = 3000L)
        val wasPlaying = _status.value.state == PlaybackState.PLAYING

        // Step 5: Try REL_TIME, fall back to ABS_TIME if fault 710
        var ok = trySeek("REL_TIME", clampedMs)
        if (!ok) {
            println("[DlnaCastController] REL_TIME seek unsupported/failed, falling back to ABS_TIME")
            ok = trySeek("ABS_TIME", clampedMs)
        }

        if (!ok) {
            println("[DlnaCastController] Seek failed with both REL_TIME and ABS_TIME, attempting reloadAndSeek fallback")
            reloadAndSeek(clampedMs)
            return@withContext
        }

        // Step 6: If TV left PLAYING after seek, resume playback
        delay(300)
        val currentState = queryTransportState()
        if (wasPlaying && currentState != PlaybackState.PLAYING) {
            println("[DlnaCastController] TV state was not PLAYING after seek, sending Play")
            play()
        }

        // Step 7: Ignore position polls for 1.5s to avoid old poll snaps
        suppressPollsFor(1500L)
        _status.value = _status.value.copy(positionMs = clampedMs)
    }

    private suspend fun trySeek(unit: String, ms: Long): Boolean {
        val xml = DlnaXmlHelper.buildSeekXml(ms, unit)
        return try {
            sendSoapAction("Seek", xml)
            true
        } catch (e: SoapFault) {
            println("[DlnaCastController] Seek ($unit) returned SoapFault ${e.code}: ${e.message}")
            when (e.code) {
                710 -> false // Mode not supported -> caller will try ABS_TIME
                711 -> {
                    // Illegal seek target: clamp to duration - 1s and retry once
                    val dur = _status.value.durationMs
                    val retryTarget = if (dur > 1000L) dur - 1000L else 0L
                    val retryXml = DlnaXmlHelper.buildSeekXml(retryTarget, unit)
                    try {
                        sendSoapAction("Seek", retryXml)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                701, 705 -> {
                    // Transition not available or locked: wait and retry once
                    delay(1000)
                    try {
                        sendSoapAction("Seek", xml)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            println("[DlnaCastController] Seek ($unit) error: ${e.message}")
            false
        }
    }

    /**
     * Fallback for TVs that reject in-stream Seek commands (A6):
     * Re-send SetAVTransportURI, Play, wait for PLAYING, then seek to target.
     */
    private suspend fun reloadAndSeek(targetMs: Long) {
        val media = activeMedia ?: return
        try {
            val setUriSoap = DlnaXmlHelper.buildSetAvTransportUriXml(
                mediaUri = media.url,
                title = media.title,
                mimeType = media.mimeType,
                subtitleUrl = media.subtitleUrl
            )
            sendSoapAction("SetAVTransportURI", setUriSoap)
            play()
            waitForPlaybackState(setOf(PlaybackState.PLAYING), timeoutMs = 3000L)
            delay(500)
            trySeek("REL_TIME", targetMs)
            suppressPollsFor(1500L)
            _status.value = _status.value.copy(positionMs = targetMs)
        } catch (e: Exception) {
            println("[DlnaCastController] reloadAndSeek failed: ${e.message}")
        }
    }

    private fun suppressPollsFor(durationMs: Long) {
        suppressPollsUntilMs = System.currentTimeMillis() + durationMs
    }

    private suspend fun waitForPlaybackState(expected: Set<PlaybackState>, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = _status.value.state
            if (state in expected) return
            delay(200)
        }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            // First query transport actions after starting playback
            delay(1000)
            queryTransportActions()

            while (isActive) {
                delay(1500)
                pollPosition()
                pollTransportInfo()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollPosition() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < suppressPollsUntilMs) {
            return@withContext
        }

        try {
            val soap = DlnaXmlHelper.buildGetPositionInfoXml()
            val body = sendSoapAction("GetPositionInfo", soap)
            parsePositionInfoXml(body)
        } catch (_: Exception) {}
    }

    private suspend fun pollTransportInfo() = withContext(Dispatchers.IO) {
        try {
            val soap = DlnaXmlHelper.buildGetTransportInfoXml()
            val body = sendSoapAction("GetTransportInfo", soap)
            parseTransportInfoXml(body)
        } catch (_: Exception) {}
    }

    private suspend fun queryTransportState(): PlaybackState = withContext(Dispatchers.IO) {
        return@withContext try {
            val soap = DlnaXmlHelper.buildGetTransportInfoXml()
            val body = sendSoapAction("GetTransportInfo", soap)
            extractTagValue(body, "CurrentTransportState")?.let { mapUpnpState(it) } ?: _status.value.state
        } catch (_: Exception) {
            _status.value.state
        }
    }

    private suspend fun queryTransportActions() = withContext(Dispatchers.IO) {
        try {
            val soap = DlnaXmlHelper.buildGetTransportActionsXml()
            val body = sendSoapAction("GetCurrentTransportActions", soap)
            val actions = extractTagValue(body, "Actions") ?: ""
            val canSeek = actions.contains("Seek", ignoreCase = true) || actions.isBlank()
            _status.value = _status.value.copy(canSeek = canSeek)
        } catch (_: Exception) {}
    }

    private fun parsePositionInfoXml(xml: String) {
        val relTimeStr = extractTagValue(xml, "RelTime")
        val absTimeStr = extractTagValue(xml, "AbsTime")
        val trackDurationStr = extractTagValue(xml, "TrackDuration")

        val posMs = DlnaXmlHelper.parseUpnpTime(relTimeStr)
            ?: DlnaXmlHelper.parseUpnpTime(absTimeStr)
        val durMs = DlnaXmlHelper.parseUpnpTime(trackDurationStr)

        val current = _status.value
        val newDuration = if (durMs != null && durMs > 0L) durMs else current.durationMs

        // Never overwrite a good position with 0 if reported during buffering or loading
        val newPosition = if (posMs != null && (posMs > 0L || current.positionMs == 0L)) {
            posMs
        } else {
            current.positionMs
        }

        _status.value = current.copy(
            positionMs = newPosition,
            durationMs = newDuration,
            polledAtMs = System.currentTimeMillis()
        )
    }

    private fun parseTransportInfoXml(xml: String) {
        val stateStr = extractTagValue(xml, "CurrentTransportState") ?: return
        val mappedState = mapUpnpState(stateStr)
        _status.value = _status.value.copy(state = mappedState)
    }

    private fun mapUpnpState(stateStr: String): PlaybackState {
        return when (stateStr.trim().uppercase()) {
            "PLAYING" -> PlaybackState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> PlaybackState.PAUSED
            "STOPPED" -> PlaybackState.STOPPED
            "TRANSITIONING" -> PlaybackState.BUFFERING
            "NO_MEDIA_PRESENT" -> PlaybackState.IDLE
            else -> _status.value.state
        }
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            var found = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals(tagName, ignoreCase = true)) {
                    found = true
                } else if (eventType == XmlPullParser.TEXT && found) {
                    return parser.text.trim()
                } else if (eventType == XmlPullParser.END_TAG && found) {
                    return ""
                }
                eventType = parser.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes a SOAP action with mutex lock, 5s timeout, and error parsing.
     * Throws SoapFault on UPnP errors.
     */
    private suspend fun sendSoapAction(action: String, bodyXml: String): String = soapMutex.withLock {
        withContext(Dispatchers.IO) {
            println("[DlnaCastController] Sending SOAPAction: $action")
            val req = Request.Builder()
                .url(device.controlUrl)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
                .post(bodyXml.toRequestBody(soapMediaType))
                .build()

            val resp = client.newCall(req).execute()
            val respCode = resp.code
            val respBody = resp.body?.string().orEmpty()
            resp.close()

            println("[DlnaCastController] SOAPAction $action returned HTTP $respCode")

            if (resp.isSuccessful) {
                return@withContext respBody
            } else {
                val errorCode = extractTagValue(respBody, "errorCode")?.toIntOrNull() ?: respCode
                val errorDesc = extractTagValue(respBody, "errorDescription") ?: "HTTP error $respCode"
                throw SoapFault(errorCode, errorDesc)
            }
        }
    }
}
