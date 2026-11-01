package com.lehuynhanhtu.cloudstreamcopier

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class TvPlugin(
    val name: String,
    val remotePath: String? = null,
    val size: Long = 0,
    val url: String? = null,
    val version: Int? = null,
    val online: Boolean = false,
)
data class BackupPlugin(val name: String, val file: File, val size: Long, val sha256: String, val source: String)

class PluginStore(private val context: Context) {
    private val backupDir = File(context.filesDir, "plugin-backups").apply { mkdirs() }

    fun listBackups(): List<BackupPlugin> = backupDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("cs3", true) }
        ?.map { file -> BackupPlugin(file.name, file, file.length(), sha256(file.readBytes()), "Điện thoại") }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun save(name: String, bytes: ByteArray, source: String): BackupPlugin {
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").let {
            if (it.endsWith(".cs3", true)) it else "${it}.cs3"
        }
        val file = File(backupDir, safe)
        file.writeBytes(bytes)
        return BackupPlugin(file.name, file, file.length(), sha256(bytes), source)
    }

    fun download(url: String): BackupPlugin {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "CloudStream-TV-Plugin-Copier/1.0")
        conn.connect()
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val bytes = conn.inputStream.use { it.readBytes() }
        val name = URL(conn.url.toString()).path.substringAfterLast('/').ifBlank { "plugin.cs3" }
        return save(name, bytes, url)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
