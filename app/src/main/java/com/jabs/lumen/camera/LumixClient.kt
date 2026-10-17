package com.jabs.lumen.camera

import android.net.Network
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CameraState(
    val battery: String?,
    val mode: String,
    val version: String,
    val lens: String?,
    val sdCard: String?,
    /** True only while the camera is actually writing a video file (<rec>on</rec>). */
    val isRecording: Boolean,
) {
    /** Shooting mode as opposed to playback, regardless of video recording. */
    val isShooting: Boolean get() = mode.equals("rec", true)
}

data class CameraItem(
    val id: String,
    val title: String,
    val url: String,
    val isContainer: Boolean,
    val size: Long,
    val thumbUrl: String = "",
    val largeUrl: String = "",
)

/**
 * One entry of the camera's current menu: the id the official app uses, its present value, whether
 * it can be changed right now, and the options the camera offers for it.
 */
data class MenuEntry(
    val id: String,
    val value: String,
    val adjustable: Boolean,
    val options: List<String>,
)

/** Minimal client for Panasonic Lumix Wi-Fi: cam.cgi remote control and DLNA ContentDirectory browsing. */
class LumixClient(val host: String, private val network: Network?) {

    private val cgi = "http://$host/cam.cgi"

    /** Asks the camera to pair with this device. Returns the camera's reply, e.g. "ok" or "ok_under_research". */
    fun requestAccess(deviceId: String, deviceName: String): String {
        val name = URLEncoder.encode(deviceName, "UTF-8").replace("+", "%20")
        return get("$cgi?mode=accctrl&type=req_acc&value=$deviceId&value2=$name").substringBefore(",").trim()
    }

    fun listFiles(): List<CameraItem> {
        playMode()
        return listFolder("0", 0)
    }

    fun state(): CameraState? {
        val body = get("$cgi?mode=getstate")
        if (!body.contains("<result>ok</result>")) return null
        fun tag(name: String): String? =
            Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)
        return CameraState(
            battery = tag("batt"),
            mode = tag("cammode").orEmpty(),
            version = tag("version").orEmpty(),
            lens = tag("lens"),
            sdCard = tag("sdcardstatus"),
            isRecording = tag("rec").equals("on", true),
        )
    }

    /** Reads one camera setting; returns null when the model does not report it. */
    fun getSetting(type: String): String? {
        val body = get("$cgi?mode=getsetting&type=$type")
        if (!body.contains("<result>ok</result>")) return null
        // Older replies use <type>value</type>; current ones use <settingvalue type="value">.
        Regex("<$type>([^<]*)</$type>").find(body)?.groupValues?.get(1)?.let { return it }
        return Regex("$type=\"([^\"]*)\"").find(body)?.groupValues?.get(1)
    }

    /** Writes one camera setting; the value is encoded because focal/shtrspeed use fractions. */
    fun setSetting(type: String, value: String): Boolean =
        get("$cgi?mode=setsetting&type=$type&value=${URLEncoder.encode(value, "UTF-8")}").contains("<result>ok</result>")

    /**
     * Reads the whole current menu: exposure mode, every value, and what can change right now.
     * Each entry carries the same id the official app uses, so this replaces guessing which
     * settings exist. Returns an empty map when the camera does not answer.
     */
    fun curMenu(): Map<String, MenuEntry> {
        val body = get("$cgi?mode=getinfo&type=curmenu")
        if (!body.contains("<result>ok</result>")) return emptyMap()
        val out = mutableMapOf<String, MenuEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(body))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("item", true)) {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    if (id.isNotEmpty()) {
                        // Availability rides on enable; active only refines it. Either "no" means hands off.
                        val adjustable = !parser.getAttributeValue(null, "enable").equals("no", true) &&
                            !(parser.getAttributeValue(null, "active") ?: "yes").equals("no", true)
                        out[id] = MenuEntry(
                            id = id,
                            value = parser.getAttributeValue(null, "value").orEmpty(),
                            adjustable = adjustable,
                            options = splitOptions(parser.getAttributeValue(null, "option").orEmpty()),
                        )
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return out
    }

    private fun splitOptions(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val sep = when {
            ',' in raw -> ','
            '/' in raw -> '/'
            ';' in raw -> ';'
            else -> ' '
        }
        return raw.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Asks the camera to push live view frames as UDP datagrams to this port on the phone. */
    fun startStream(port: Int) {
        get("$cgi?mode=startstream&value=$port")
    }

    fun stopStream() {
        get("$cgi?mode=stopstream")
    }

    fun recordStart() {
        get("$cgi?mode=camcmd&value=video_recstart")
    }

    fun recordStop() {
        get("$cgi?mode=camcmd&value=video_recstop")
    }

    fun playMode() {
        get("$cgi?mode=camcmd&value=playmode")
    }

    /** Switches the camera to still shooting mode and fires the shutter. */
    fun recMode() {
        get("$cgi?mode=camcmd&value=recmode")
    }

    fun capture() {
        get("$cgi?mode=camcmd&value=capture")
    }

    fun openStream(url: String): CameraStream {
        val conn = open(url).apply {
            connectTimeout = 5000
            readTimeout = 30000
        }
        // The camera reports the real size in X-FILE_SIZE; Content-Length comes back as 0.
        val size = conn.getHeaderField("X-FILE_SIZE")?.toLongOrNull() ?: conn.contentLengthLong
        return CameraStream(conn.inputStream, size, conn)
    }

    class CameraStream(
        private val stream: InputStream,
        val contentLength: Long,
        private val connection: HttpURLConnection,
    ) : InputStream() {
        override fun read(): Int = stream.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
        override fun available(): Int = stream.available()
        override fun close() {
            runCatching { stream.close() }
            runCatching { connection.disconnect() }
        }
    }

    private fun listFolder(objectId: String, depth: Int): List<CameraItem> {
        val files = mutableListOf<CameraItem>()
        val folders = mutableListOf<CameraItem>()
        var start = 0
        while (true) {
            val page = browse(objectId, start)
            page.items.forEach { if (it.isContainer) folders += it else files += it }
            start += page.returned
            if (page.returned == 0 || start >= page.total) break
        }
        if (depth < MAX_DEPTH) {
            folders.forEach { files += listFolder(it.id, depth + 1) }
        }
        return files
    }

    private fun browse(objectId: String, start: Int): BrowsePage {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" " +
            "xmlns:pana=\"urn:schemas-panasonic-com:pana\">" +
            "<ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter><StartingIndex>$start</StartingIndex>" +
            "<RequestedCount>$PAGE_SIZE</RequestedCount><SortCriteria></SortCriteria>" +
            "<pana:X_FromCP>LumixLink2.0</pana:X_FromCP></u:Browse></s:Body></s:Envelope>"

        val conn = open("http://$host:$DLNA_PORT/Server0/CDS_control").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
        }
        val soap = try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseBrowse(soap)
    }

    private fun parseBrowse(soap: String): BrowsePage {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(soap))
        var result = ""
        var total = 0
        var returned = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Result" -> result = parser.nextText()
                    "TotalMatches" -> total = parser.nextText().trim().toIntOrNull() ?: 0
                    "NumberReturned" -> returned = parser.nextText().trim().toIntOrNull() ?: 0
                }
            }
            parser.next()
        }
        return BrowsePage(parseDidl(result), total, returned)
    }

    private fun parseDidl(xml: String): List<CameraItem> {
        if (xml.isBlank()) return emptyList()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<CameraItem>()
        var id = ""
        var title = ""
        var url = ""
        var thumb = ""
        var large = ""
        var size = 0L
        var isContainer = false
        var inEntry = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "container", "item" -> {
                        inEntry = true
                        isContainer = parser.name == "container"
                        id = parser.getAttributeValue(null, "id") ?: ""
                        title = ""
                        url = ""
                        thumb = ""
                        large = ""
                        size = 0L
                    }
                    "dc:title", "title" -> title = parser.nextText().trim()
                    "res" -> {
                        val protocol = parser.getAttributeValue(null, "protocolInfo").orEmpty()
                        val resSize = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        val value = parser.nextText().trim()
                        when {
                            protocol.contains("CAM_TN") || protocol.contains("JPEG_TN") -> thumb = value
                            protocol.contains("CAM_LRGTN") || protocol.contains("JPEG_MED") -> large = value
                            protocol.contains("CAM_RAW") -> {
                                url = value
                                size = resSize
                            }
                            url.isEmpty() -> {
                                url = value
                                size = resSize
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if ((parser.name == "container" || parser.name == "item") && inEntry) {
                    inEntry = false
                    if (isContainer || url.isNotEmpty()) {
                        out += CameraItem(id, title.ifEmpty { id }, url, isContainer, size, thumb, large)
                    }
                }
            }
            parser.next()
        }
        return out
    }

    private fun get(url: String): String {
        val conn = open(url).apply {
            connectTimeout = 5000
            readTimeout = 10000
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val connection = network?.openConnection(target) ?: target.openConnection()
        return connection as HttpURLConnection
    }

    private data class BrowsePage(val items: List<CameraItem>, val total: Int, val returned: Int)

    companion object {
        const val DEFAULT_HOST = "192.168.54.1"
        private const val DLNA_PORT = 60606
        private const val PAGE_SIZE = 200
        private const val MAX_DEPTH = 3
    }
}
