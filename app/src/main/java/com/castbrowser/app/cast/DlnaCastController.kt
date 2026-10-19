package com.castbrowser.app.cast

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class DlnaDevice(
    override val id: String,
    override val name: String,
    override val address: String,
    val controlUrl: String,
    override val protocol: CastProtocol = CastProtocol.DLNA
) : CastDevice

class DlnaCastController(
    private val device: DlnaDevice,
    private val client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : CastController {

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private var pollJob: Job? = null
    private val soapMediaType = "text/xml; charset=utf-8".toMediaType()

    override suspend fun load(media: CastMedia, startMs: Long) = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(state = PlaybackState.BUFFERING)

        val setUriSoap = DlnaXmlHelper.buildSetAvTransportUriXml(
            mediaUri = media.url,
            title = media.title,
            mimeType = media.mimeType,
            subtitleUrl = media.subtitleUrl
        )
        val success = sendSoapAction("SetAVTransportURI", setUriSoap)
        if (success) {
            play()
            if (startMs > 0) {
                // Seek after play command
                delay(1000)
                seekTo(startMs)
            }
            startPolling()
        } else {
            _status.value = _status.value.copy(
                state = PlaybackState.ERROR,
                errorMessage = "Failed to load media on TV"
            )
        }
    }

    override suspend fun play() = withContext(Dispatchers.IO) {
        val success = sendSoapAction("Play", DlnaXmlHelper.buildPlayXml())
        if (success) {
            _status.value = _status.value.copy(state = PlaybackState.PLAYING)
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        val success = sendSoapAction("Pause", DlnaXmlHelper.buildPauseXml())
        if (success) {
            _status.value = _status.value.copy(state = PlaybackState.PAUSED)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        sendSoapAction("Stop", DlnaXmlHelper.buildStopXml())
        _status.value = _status.value.copy(state = PlaybackState.STOPPED)
        stopPolling()
    }

    override suspend fun seekTo(positionMs: Long): Unit = withContext(Dispatchers.IO) {
        val seconds = positionMs / 1000
        sendSoapAction("Seek", DlnaXmlHelper.buildSeekXml(seconds))
        Unit
    }

    override suspend fun setVolume(level: Float) {
        // Optional rendering control
    }

    override fun release() {
        stopPolling()
        scope.cancel()
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(1500)
                pollPosition()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollPosition() = withContext(Dispatchers.IO) {
        try {
            val soap = DlnaXmlHelper.buildGetPositionInfoXml()
            val req = Request.Builder()
                .url(device.controlUrl)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"")
                .post(soap.toRequestBody(soapMediaType))
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                parsePositionInfoXml(body)
            } else {
                resp.close()
            }
        } catch (_: Exception) {}
    }

    private fun parsePositionInfoXml(xml: String) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""
            var relTime = ""
            var trackDuration = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag = parser.name
                    XmlPullParser.TEXT -> {
                        if (currentTag.equals("RelTime", ignoreCase = true)) {
                            relTime = parser.text
                        } else if (currentTag.equals("TrackDuration", ignoreCase = true)) {
                            trackDuration = parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                eventType = parser.next()
            }

            val posSec = DlnaXmlHelper.parseRelTimeToSeconds(relTime)
            val durSec = DlnaXmlHelper.parseRelTimeToSeconds(trackDuration)

            if (durSec > 0 || posSec > 0) {
                _status.value = _status.value.copy(
                    positionMs = posSec * 1000L,
                    durationMs = durSec * 1000L,
                    state = if (_status.value.state == PlaybackState.BUFFERING) PlaybackState.PLAYING else _status.value.state
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun sendSoapAction(action: String, bodyXml: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder()
                .url(device.controlUrl)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
                .post(bodyXml.toRequestBody(soapMediaType))
                .build()

            val resp = client.newCall(req).execute()
            val isSuccess = resp.isSuccessful
            resp.close()
            isSuccess
        } catch (_: Exception) {
            false
        }
    }
}
