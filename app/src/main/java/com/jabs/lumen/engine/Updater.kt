package com.jabs.lumen.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jabs.lumen.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A newer release published on GitHub. */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val notes: String,
)

/**
 * Self-update from the project's GitHub Releases. It asks the public Releases API for the latest
 * tag, and when that tag is newer than the installed build it downloads the APK and hands it to
 * the system installer. No token is needed because the repository is public.
 */
object Updater {

    private const val USER_AGENT = "Lumen-Updater"

    /** The latest release, or null when the installed build is already the newest one. */
    fun check(): UpdateInfo? {
        val connection = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = asset.optString("browser_download_url")
                    break
                }
            }
            if (url.isNullOrEmpty()) return null
            return UpdateInfo(tag, url, json.optString("body", ""))
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [info] into the app cache, reporting whole-percent progress as it goes. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "lumen-${info.version}.apk")
        val connection = open(info.downloadUrl)
        try {
            if (connection.responseCode != 200) error("Servidor respondeu ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    /** True when this app is allowed to hand an APK to the installer (unknown sources). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the settings screen where the user allows this app to install updates. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the downloaded APK to the system package installer. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** Semantic-version comparison: is [remote] strictly newer than [current]? */
    private fun isNewer(remote: String, current: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(v: String): List<Int> =
        v.trim().split('.', '-', '+').mapNotNull { it.toIntOrNull() }
}
