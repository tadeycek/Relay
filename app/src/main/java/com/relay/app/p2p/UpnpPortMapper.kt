package com.relay.app.p2p

import android.util.Log
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * Best-effort UPnP IGD (Internet Gateway Device) port mapping, so a phone behind a home router can
 * still be reached from outside its own network for a direct transfer, not just on the same Wi-Fi.
 *
 * This is explicitly best-effort, not a guarantee: most mobile data connections (carrier-grade NAT)
 * have no router to ask at all, plenty of home routers ship with UPnP disabled, and even a successful
 * mapping can still be blocked further upstream. Any failure here just means one fewer candidate
 * address is offered — same-network transfers (phase 2) are unaffected, and a cross-network transfer
 * that can't connect fails with a plain, honest message rather than a silent retry loop.
 *
 * No external library: SSDP discovery is a handful of UDP packets, and IGD's "SOAP" control calls are
 * simple enough XML to build and read by hand without pulling in a client for it.
 */
object UpnpPortMapper {

    private const val TAG = "Upnp"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    // Kept tight: the whole attempt has to fit inside the presence check's reply window
    // (PresenceRules.TIMEOUT_MS) alongside the instant LAN-only candidates, so a slow or absent router
    // does not delay a same-network transfer that did not need UPnP at all.
    private const val DISCOVER_TIMEOUT_MS = 1_500
    private const val HTTP_TIMEOUT_MS = 1_500
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:WANIPConnection:1"
    private const val PPP_SEARCH_TARGET = "urn:schemas-upnp-org:service:WANPPPConnection:1"

    private data class ControlPoint(val controlUrl: URL, val serviceType: String)

    /**
     * Maps [port] on this phone's LAN address to the same external port and returns "externalIp:port"
     * for [PresenceMessages] to offer as a candidate, or null if any step fails or times out.
     */
    fun requestMapping(port: Int, localAddress: String): String? = try {
        val location = discoverGateway() ?: return null
        val control = findControlUrl(location) ?: return null
        addPortMapping(control, port, localAddress)
        val externalIp = getExternalIp(control) ?: return null
        "$externalIp:$port"
    } catch (e: Exception) {
        Log.i(TAG, "UPnP mapping failed: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** SSDP M-SEARCH: ask on the local network's multicast group "who is a gateway?" and read one reply. */
    private fun discoverGateway(): URL? {
        DatagramSocket().use { socket ->
            socket.soTimeout = DISCOVER_TIMEOUT_MS
            val request = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $SEARCH_TARGET\r\n\r\n"
            val packet = DatagramPacket(
                request.toByteArray(), request.length,
                InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT),
            )
            socket.send(packet)

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply) // throws SocketTimeoutException at the deadline; caught by requestMapping
                val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                val location = Regex("""(?im)^LOCATION:\s*(\S+)""").find(text)?.groupValues?.get(1) ?: continue
                return runCatching { URL(location) }.getOrNull() ?: continue
            }
        }
        return null
    }

    /** Fetches the gateway's device description XML and finds the WAN connection service's control URL. */
    private fun findControlUrl(deviceDescriptionUrl: URL): ControlPoint? {
        val xml = httpGet(deviceDescriptionUrl) ?: return null
        for (target in listOf(SEARCH_TARGET, PPP_SEARCH_TARGET)) {
            // The IGD schema pairs <serviceType> with a sibling <controlURL> inside one <service> block;
            // a non-greedy match between the two tag names is a pragmatic way to pair them without a full
            // namespace-aware XML parser, which this simple, well-known document shape doesn't need.
            val match = Regex(
                """<serviceType>\Q$target\E</serviceType>.*?<controlURL>([^<]+)</controlURL>""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(xml) ?: continue
            val controlPath = match.groupValues[1].trim()
            val resolved = runCatching { URL(deviceDescriptionUrl, controlPath) }.getOrNull() ?: continue
            return ControlPoint(resolved, target)
        }
        return null
    }

    private fun addPortMapping(control: ControlPoint, port: Int, localAddress: String) {
        val body = soapEnvelope(
            control.serviceType,
            "AddPortMapping",
            """
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$port</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            <NewInternalPort>$port</NewInternalPort>
            <NewInternalClient>$localAddress</NewInternalClient>
            <NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>Relay</NewPortMappingDescription>
            <NewLeaseDuration>3600</NewLeaseDuration>
            """.trimIndent(),
        )
        soapPost(control, "AddPortMapping", body)
    }

    private fun getExternalIp(control: ControlPoint): String? {
        val body = soapEnvelope(control.serviceType, "GetExternalIPAddress", "")
        val response = soapPost(control, "GetExternalIPAddress", body) ?: return null
        return Regex("""<NewExternalIPAddress>([^<]+)</NewExternalIPAddress>""").find(response)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun soapEnvelope(serviceType: String, action: String, argsXml: String): String = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:$action xmlns:u="$serviceType">$argsXml</u:$action></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun httpGet(url: URL): String? {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun soapPost(control: ControlPoint, action: String, body: String): String? {
        val conn = control.controlUrl.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"${control.serviceType}#$action\"")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
