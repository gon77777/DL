package com.aurum.dl

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 「取得済みの記録」。再生リスト・アカウントを取り直すとき、取得済みの分を飛ばして新着だけ取るために使う。
 * yt-dlp / gallery-dl のダウンロード記録 (download archive) を、取得元の URL ごとに 1 ファイル持つ。
 * 設定した日数のあいだ使われなかった記録は、アプリ起動時に自動で消える (使うたびに期限は延びる)。
 */
object Archive {

    private fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, "archive")

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** この項目で使う記録ファイル。使わない設定・種類なら null。単体の動画は毎回取得するので対象外。 */
    fun forItem(context: Context, item: DownloadItem): File? {
        if (!Prefs.archiveEnabled(context)) return null
        val account = item.account
        val (key, ext) = when {
            account != null -> account.target.url to "sqlite3"
            item.options.playlist -> item.url to "txt"
            else -> return null
        }
        val d = dir(context).also { it.mkdirs() }
        return File(d, hash(key) + "." + ext)
    }

    fun hasRecords(file: File?): Boolean = file != null && file.isFile && file.length() > 0

    /** 使ったことを記録して、期限を延ばす。 */
    fun touch(file: File?) {
        if (file != null && file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    /** 期限の切れた記録を消す。 */
    fun purge(context: Context) {
        val days = Prefs.archiveDays(context)
        if (days <= 0) return // 無期限
        val limit = System.currentTimeMillis() - days * DAY_MS
        dir(context).listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    fun clear(context: Context): Int {
        val files = dir(context).listFiles() ?: return 0
        return files.count { it.delete() }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
