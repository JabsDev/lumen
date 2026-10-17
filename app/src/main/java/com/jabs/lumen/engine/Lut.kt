package com.jabs.lumen.engine

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * A 3D colour lookup table: `size` cubed samples of red, green and blue, stored with red
 * varying fastest (the ordering the .cube format writes them in) and already normalised
 * into 0..1, so the pixel loop can interpolate straight out of the array.
 */
class Lut(val size: Int, val data: FloatArray)

/**
 * Home of the looks: the bundled .cube files and the ones the user picks, which are copied
 * into app storage so they survive the picker's temporary permission. One parsed LUT is close
 * to a megabyte, so files are read and parsed once on a worker thread and then served from
 * memory — a render must never be waiting on the disk while a slider moves.
 */
object LutLibrary {

    /** A LUT: the id a saved edit stores, the name shown in the row, and where it came from. */
    data class Entry(val id: String, val name: String, val user: Boolean)

    private const val BUNDLED_DIR = "luts"
    private const val USER_DIR = "user"
    private const val USER_PREFIX = "$USER_DIR/"

    /** 64 cubed x 3 channels x 4 bytes is 3 MB parsed; anything larger is rejected at the door. */
    private const val MAX_LUT_SIZE = 64
    private const val MAX_IMPORT_BYTES = 16 shl 20

    /** A couple of bundled looks plus whatever is in use fits in here several times over. */
    private const val MAX_CACHED = 8

    /** Access-ordered so the look on screen is the last one an eviction would ever reach. */
    private val cache = object : LinkedHashMap<String, Lut>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lut>): Boolean =
            size > MAX_CACHED
    }

    private var assets: AssetManager? = null
    private var userDir: File? = null

    /** Stores the two lookups a render may need; cheap enough to do before anything is open. */
    fun init(context: Context) {
        if (assets != null) return
        val app = context.applicationContext
        assets = app.assets
        userDir = File(app.filesDir, "$BUNDLED_DIR/$USER_DIR")
    }

    /** Everything available: the bundled LUTs first, then the ones imported on this phone. */
    fun scan(): List<Entry> {
        val bundled = assets?.let { a ->
            runCatching { a.list(BUNDLED_DIR) }.getOrNull()
                ?.filter { it.endsWith(".cube", ignoreCase = true) }
                ?.sorted()
                ?.map { Entry(it, titleOf(head(a, it)) ?: prettyName(it), user = false) }
        }.orEmpty()
        val imported = userDir?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { Entry(USER_PREFIX + it.name, titleOf(head(it)) ?: prettyName(it.name), user = true) }
            .orEmpty()
        return bundled + imported
    }

    /** Parses a LUT the first time it is picked; every later render finds it in the cache. */
    fun warm(id: String): Lut? {
        if (id.isEmpty()) return null
        synchronized(cache) { cache[id]?.let { return it } }
        val text = read(id) ?: return null
        val lut = parse(text) ?: return null
        synchronized(cache) { cache[id] = lut }
        return lut
    }

    /** The already parsed LUT, or null while cold. Renders only ever call this. */
    fun get(id: String): Lut? = if (id.isEmpty()) null else synchronized(cache) { cache[id] }

    /**
     * Copies a picked .cube into app storage and hands back its entry. The file is parsed
     * before it is written, so a look that was never valid never reaches the list.
     */
    fun importUserLut(resolver: ContentResolver, uri: Uri): Entry? {
        val bytes = readCapped(resolver, uri) ?: return null
        val text = String(bytes, Charsets.UTF_8)
        val lut = parse(text) ?: return null

        val dir = userDir ?: return null
        if (!runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) return null
        val stem = fileNameOf(resolver, uri)?.let { stemOf(it) } ?: "lut"
        var name = "$stem.cube"
        var copy = 2
        while (File(dir, name).exists()) {
            name = "${stem}_$copy.cube"
            copy++
        }
        val file = File(dir, name)
        runCatching { file.writeBytes(bytes) }.getOrNull() ?: return null

        val id = USER_PREFIX + name
        synchronized(cache) { cache[id] = lut }
        return Entry(id, titleOf(text) ?: prettyName(name), user = true)
    }

    /** Forgets an imported look and deletes its file; bundled ones are untouchable. */
    fun removeUserLut(id: String): Boolean {
        if (!id.startsWith(USER_PREFIX)) return false
        val dir = userDir ?: return false
        // The name after the last slash keeps the id from ever reaching outside the folder.
        val file = File(dir, id.substringAfterLast('/'))
        synchronized(cache) { cache.remove(id) }
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }

    private fun read(id: String): String? {
        val a = assets ?: return null
        return if (id.startsWith(USER_PREFIX)) {
            val dir = userDir ?: return null
            val file = File(dir, id.substringAfterLast('/'))
            runCatching { file.readText() }.getOrNull()
        } else {
            runCatching { a.open("$BUNDLED_DIR/$id").bufferedReader().use { it.readText() } }.getOrNull()
        }
    }

    private fun head(assets: AssetManager, id: String): String? = runCatching {
        assets.open("$BUNDLED_DIR/$id").use { readHead(it) }
    }.getOrNull()

    private fun head(file: File): String? = runCatching {
        file.inputStream().use { readHead(it) }
    }.getOrNull()

    /** TITLE lives in the first lines; reading a few kilobytes beats parsing the whole file. */
    private fun readHead(input: InputStream): String {
        val head = ByteArray(8192)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        return String(head, 0, read, Charsets.UTF_8)
    }

    private fun titleOf(text: String?): String? =
        text?.lineSequence()?.firstNotNullOfOrNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("TITLE")) return@firstNotNullOfOrNull null
            line.substringAfter("TITLE").trim().removeSurrounding("\"").trim()
                .takeIf { it.isNotEmpty() }
        }

    private fun prettyName(id: String): String = id
        .substringBeforeLast('.')
        .split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

    /** Keeps what the picker called the file, without anything that cannot be part of a name. */
    private fun fileNameOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    private fun stemOf(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9 ._\\-]"), "")
            .trim()
        val stem = if (cleaned.endsWith(".cube", ignoreCase = true)) cleaned.dropLast(5) else cleaned
        return stem.trim().ifEmpty { "lut" }
    }

    private fun readCapped(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1 shl 15)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) return@use null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * Reads a standard .cube: comments, TITLE, the cube size, the data domain and then one
     * "r g b" line per sample. Files that are 1D, oversized or truncated are rejected rather
     * than guessed at — a wrong LUT tints the whole photo.
     */
    private fun parse(text: String): Lut? {
        val body = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        var size = 0
        var data: FloatArray? = null
        var written = 0

        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            when {
                line.startsWith("TITLE") -> continue
                line.startsWith("LUT_3D_SIZE") -> {
                    size = line.substringAfter("LUT_3D_SIZE").trim().takeWhile { it.isDigit() }
                        .toIntOrNull() ?: return null
                    if (size < 2 || size > MAX_LUT_SIZE) return null
                    data = FloatArray(size * size * size * 3)
                }
                line.startsWith("LUT_1D") -> return null
                line.startsWith("DOMAIN_MIN") -> readDomain(line, domainMin)
                line.startsWith("DOMAIN_MAX") -> readDomain(line, domainMax)
                else -> {
                    val out = data ?: return null
                    var pos = 0
                    repeat(3) { channel ->
                        val start = pos
                        while (pos < line.length && line[pos] != ' ' && line[pos] != '\t') pos++
                        if (start == pos) return null
                        val value = line.substring(start, pos).toFloatOrNull() ?: return null
                        val lo = domainMin[channel]
                        val hi = domainMax[channel]
                        val scaled = if (hi > lo) (value - lo) / (hi - lo) else value
                        out[written++] = if (scaled < 0f) 0f else if (scaled > 1f) 1f else scaled
                        while (pos < line.length && (line[pos] == ' ' || line[pos] == '\t')) pos++
                    }
                    if (written == out.size) break
                }
            }
        }

        val out = data ?: return null
        if (written != out.size) return null
        return Lut(size, out)
    }

    private fun readDomain(line: String, target: FloatArray) {
        val numbers = line.substringAfter(' ').trim().split(WHITESPACE)
        for (i in target.indices) {
            numbers.getOrNull(i)?.toFloatOrNull()?.let { target[i] = it }
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
