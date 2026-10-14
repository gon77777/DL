package com.aurum.dl

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * yt-dlp 本体の更新まわりの補助。
 * 更新の前に元の版を控えておき、更新後に動かなければ元に戻す。
 * 最新版が端末の Python で動かないときは、動く最新版を GitHub のリリースから直接入れる。
 */
object YtdlpUpdater {

    private fun baseDir(context: Context) = File(context.applicationContext.noBackupFilesDir, "youtubedl-android")

    /** yt-dlp 本体のファイル。ライブラリが保持している値があれば優先し、取れなければ既定の配置を使う。 */
    fun ytdlpFile(context: Context): File {
        val fromLib = runCatching {
            val f = YoutubeDL::class.java.getDeclaredField("ytdlpPath")
            f.isAccessible = true
            f.get(null) as? File
        }.getOrNull()
        return fromLib ?: File(baseDir(context), "yt-dlp/yt-dlp")
    }

    private fun ytdlpDir(context: Context): File = ytdlpFile(context).parentFile ?: File(baseDir(context), "yt-dlp")

    private fun backupDir(context: Context) = File(baseDir(context), "yt-dlp.backup")

    /** 動いている版を控える。控えられなければ false (その場合は戻せない)。 */
    fun backup(context: Context): Boolean = runCatching {
        val src = ytdlpDir(context)
        if (!src.isDirectory) return false
        val dst = backupDir(context)
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        true
    }.getOrDefault(false)

    /** 控えた版へ戻す。 */
    fun restore(context: Context): Boolean = runCatching {
        val src = backupDir(context)
        if (!src.isDirectory) return false
        val dst = ytdlpDir(context)
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        true
    }.getOrDefault(false)

    fun dropBackup(context: Context) {
        backupDir(context).deleteRecursively()
    }

    /** yt-dlp を実際に起動して `--version` を読む。動かなければ null。 */
    fun probeVersion(): String? = try {
        val req = YoutubeDLRequest(emptyList<String>())
        req.addOption("--version")
        val out = YoutubeDL.getInstance().execute(req, "probe_" + UUID.randomUUID(), null).out.trim()
        out.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.takeIf { Regex("""\d{4}\.\d{2}\.\d{2}.*""").matches(it) }
    } catch (e: Throwable) {
        null
    }

    /** GitHub のリリース名 (2025.1.15 → 2025.01.15) へ直す。 */
    private fun tagOf(version: String): String {
        val parts = version.split('.')
        return parts.mapIndexed { i, p -> if (i in 1..2) p.padStart(2, '0') else p }.joinToString(".")
    }

    /** 指定の版の yt-dlp を GitHub のリリースから取って差し替える。 */
    fun installRelease(context: Context, version: String) {
        val target = ytdlpFile(context)
        if (!target.isFile) throw IllegalStateException("yt-dlp の場所を特定できませんでした")
        val tmp = File(target.parentFile, "yt-dlp.download")
        try {
            val url = "https://github.com/yt-dlp/yt-dlp/releases/download/${tagOf(version)}/yt-dlp"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "KyuuriDL")
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() < 100_000) throw IllegalStateException("ダウンロードしたファイルが正しくありません")
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("yt-dlp を差し替えられませんでした")
            }
        } finally {
            tmp.delete()
        }
    }
}
