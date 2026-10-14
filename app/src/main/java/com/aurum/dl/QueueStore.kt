package com.aurum.dl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * キューを端末に保存・復元する。アプリが強制終了されても、待機中・一時停止中の項目を失わないため。
 * 保存先は端末内の専用領域 (バックアップ対象外)。
 */
object QueueStore {

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, "queue.json")

    /** 完了済みの項目は中身が変わらないので、JSON を作り直さず使い回す (保存ファイルが多い項目の負荷対策)。 */
    private val finishedCache = HashMap<String, Pair<DownloadItem, JSONObject>>()

    @Synchronized
    fun save(context: Context, items: List<DownloadItem>) {
        runCatching {
            val arr = JSONArray()
            val seen = HashSet<String>()
            for (item in items) {
                seen += item.id
                val json = if (item.isFinished) {
                    val cached = finishedCache[item.id]
                    if (cached != null && cached.first === item) cached.second
                    else toJson(item).also { finishedCache[item.id] = item to it }
                } else {
                    toJson(item)
                }
                arr.put(json)
            }
            finishedCache.keys.retainAll(seen)
            val dest = file(context)
            val tmp = File(dest.parentFile, "queue.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
        }
    }

    /** 保存済みのキューを読む。読めない項目は飛ばす。 */
    fun load(context: Context): List<DownloadItem> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ json

    private fun toJson(i: DownloadItem): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("url", i.url)
        put("options", optionsToJson(i.options))
        put("playlistItems", i.playlistItems ?: JSONObject.NULL)
        put("title", i.title ?: JSONObject.NULL)
        put("status", i.status.name)
        put("progress", i.progress.toDouble())
        put("message", i.message)
        put("waitingForWifi", i.waitingForWifi)
        put(
            "savedFiles",
            JSONArray().also { a ->
                i.savedFiles.forEach { f ->
                    a.put(JSONObject().put("uri", f.uri).put("name", f.name).put("mime", f.mime))
                }
            },
        )
        i.account?.let { a ->
            put(
                "account",
                JSONObject()
                    .put("media", a.media.name)
                    .put("url", a.target.url)
                    .put("folder", a.target.folder)
                    .put("siteHost", a.target.siteHost)
                    .put("loginUrl", a.target.loginUrl)
                    .put("isTwitter", a.target.isTwitter),
            )
        }
    }

    private fun optionsToJson(o: Options) = JSONObject()
        .put("mode", o.mode.name)
        .put("quality", o.quality.name)
        .put("container", o.container.name)
        .put("codec", o.codec.name)
        .put("audioFormat", o.audioFormat.name)
        .put("audioQuality", o.audioQuality.name)
        .put("playlist", o.playlist)

    private inline fun <reified T : Enum<T>> JSONObject.enumOr(key: String, default: T): T =
        runCatching { enumValueOf<T>(getString(key)) }.getOrDefault(default)

    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun fromJson(j: JSONObject): DownloadItem {
        val o = j.getJSONObject("options")
        val options = Options(
            mode = o.enumOr("mode", Mode.VIDEO),
            quality = o.enumOr("quality", VideoQuality.BEST),
            container = o.enumOr("container", VideoContainer.MP4),
            codec = o.enumOr("codec", VideoCodec.AUTO),
            audioFormat = o.enumOr("audioFormat", AudioFormat.MP3),
            audioQuality = o.enumOr("audioQuality", AudioQuality.BEST),
            playlist = o.optBoolean("playlist", false),
        )
        val files = j.optJSONArray("savedFiles")?.let { a ->
            (0 until a.length()).map {
                val f = a.getJSONObject(it)
                SavedFile(f.getString("uri"), f.getString("name"), f.getString("mime"))
            }
        } ?: emptyList()
        val account = j.optJSONObject("account")?.let { a ->
            AccountJob(
                AccountUrl.Target(
                    url = a.getString("url"),
                    folder = a.getString("folder"),
                    siteHost = a.getString("siteHost"),
                    loginUrl = a.getString("loginUrl"),
                    isTwitter = a.optBoolean("isTwitter", false),
                ),
                a.enumOr("media", MediaKind.BOTH),
            )
        }
        return DownloadItem(
            id = j.getString("id"),
            url = j.getString("url"),
            options = options,
            playlistItems = j.strOrNull("playlistItems"),
            title = j.strOrNull("title"),
            status = j.enumOr("status", Status.QUEUED),
            progress = j.optDouble("progress", 0.0).toFloat(),
            message = j.optString("message", ""),
            savedFiles = files,
            waitingForWifi = j.optBoolean("waitingForWifi", false),
            account = account,
        )
    }
}
