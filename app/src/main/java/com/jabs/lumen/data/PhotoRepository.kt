package com.jabs.lumen.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jabs.lumen.engine.ImageEngine
import com.jabs.lumen.model.EditParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

data class Photo(
    val id: String,
    val fileName: String,
    val displayName: String,
    val edit: EditParams = EditParams(),
)

class PhotoRepository(context: Context) {

    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(root, "index.json")

    fun file(photo: Photo): File = File(root, photo.fileName)

    fun isDecodable(photo: Photo): Boolean =
        runCatching { ImageEngine.decode(file(photo), 64) }.isSuccess

    fun delete(photo: Photo) {
        file(photo).delete()
    }

    fun load(): List<Photo> {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        return (0 until arr.length())
            .map { i ->
                val o = arr.getJSONObject(i)
                Photo(
                    id = o.getString("id"),
                    fileName = o.getString("fileName"),
                    displayName = o.getString("displayName"),
                    edit = EditParams.fromJson(o.optJSONObject("edit")),
                )
            }
            .filter { File(root, it.fileName).exists() }
    }

    fun save(photos: List<Photo>) {
        val arr = JSONArray()
        photos.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("fileName", p.fileName)
                    .put("displayName", p.displayName)
                    .put("edit", p.edit.toJson())
            )
        }
        indexFile.writeText(arr.toString())
    }

    fun importFile(resolver: ContentResolver, uri: Uri): Photo {
        val name = queryName(resolver, uri) ?: "foto.jpg"
        return importStream(name) {
            resolver.openInputStream(uri) ?: error("Não foi possível abrir $uri")
        }
    }

    fun importStream(name: String, open: () -> InputStream): Photo =
        importStream(name, 0L, open) { _, _ -> }

    fun importStream(
        name: String,
        expectedBytes: Long,
        open: () -> InputStream,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): Photo {
        val id = UUID.randomUUID().toString()
        val ext = name.substringAfterLast('.', "jpg")
        val fileName = "$id.$ext"
        open().use { input ->
            File(root, fileName).outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, expectedBytes)
                }
            }
        }
        return Photo(id = id, fileName = fileName, displayName = name)
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
