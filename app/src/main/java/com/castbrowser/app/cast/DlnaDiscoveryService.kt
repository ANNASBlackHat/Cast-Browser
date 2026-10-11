package com.castbrowser.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

class DlnaDiscoveryService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val multicastAddress = "239.255.255.250"
    private val multicastPort = 1900

    private val searchMessage = """
M-SEARCH * HTTP/1.1
HOST: 239.255.255.250:1900
MAN: "ssdp:discover"
MX: 3
ST: urn:schemas-upnp-org:service:AVTransport:1

""".replace("\n", "\r\n")

    suspend fun discoverDevices(timeoutMs: Int = 3000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("CastBrowserDlnaLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val discovered = mutableListOf<DlnaDevice>()
        val locationUrls = mutableSetOf<String>()

        try {
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val sendData = searchMessage.toByteArray()
            val sendPacket = DatagramPacket(
                sendData,
                sendData.size,
                InetAddress.getByName(multicastAddress),
                multicastPort
            )
            socket.send(sendPacket)

            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    extractLocationUrl(response)?.let { loc ->
                        locationUrls.add(loc)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()

            // Fetch device XML descriptions
            for (loc in locationUrls) {
                fetchDeviceDescription(loc)?.let { device ->
                    discovered.add(device)
                }
            }
        } catch (_: Exception) {
        } finally {
            multicastLock?.release()
        }

        return@withContext discovered
    }

    private fun extractLocationUrl(ssdpResponse: String): String? {
        val lines = ssdpResponse.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("LOCATION:", ignoreCase = true)) {
                return trimmed.substringAfter(":").trim()
            }
        }
        return null
    }

    private suspend fun fetchDeviceDescription(locationUrl: String): DlnaDevice? = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder().url(locationUrl).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                resp.close()
                return@withContext null
            }
            val xml = resp.body?.string() ?: ""
            resp.close()

            parseDeviceXml(xml, locationUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDeviceXml(xml: String, locationUrl: String): DlnaDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""
            var friendlyName = ""
            var controlUrl = ""
            var udn = ""
            var insideAvTransport = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag.equals("service", ignoreCase = true)) {
                            insideAvTransport = false
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            when {
                                currentTag.equals("friendlyName", ignoreCase = true) && friendlyName.isEmpty() -> {
                                    friendlyName = text
                                }
                                currentTag.equals("UDN", ignoreCase = true) && udn.isEmpty() -> {
                                    udn = text
                                }
                                currentTag.equals("serviceType", ignoreCase = true) && text.contains("AVTransport", ignoreCase = true) -> {
                                    insideAvTransport = true
                                }
                                currentTag.equals("controlURL", ignoreCase = true) && insideAvTransport -> {
                                    controlUrl = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                eventType = parser.next()
            }

            if (controlUrl.isNotEmpty()) {
                val baseUri = URI(locationUrl)
                val fullControlUrl = baseUri.resolve(controlUrl).toString()
                DlnaDevice(
                    id = udn.ifBlank { locationUrl },
                    name = friendlyName.ifBlank { "Smart TV (DLNA)" },
                    address = locationUrl,
                    controlUrl = fullControlUrl
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
