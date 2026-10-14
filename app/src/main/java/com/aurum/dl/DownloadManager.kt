package com.aurum.dl

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.coroutines.cancellation.CancellationException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * キューとダウンロード実行を担当するシングルトン。
 * 実際の実行ループは [DownloadService] (フォアグラウンドサービス) が回すので、
 * アプリを閉じたりバックグラウンドへ移っても処理が続く。
 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _engine = MutableStateFlow(EngineState())
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _gallery = MutableStateFlow(EngineState())

    /** アカウント一括ダウンロードに使う gallery-dl の状態。 */
    val galleryEngine: StateFlow<EngineState> = _gallery.asStateFlow()

    /** 実行中の gallery-dl プロセス (項目 ID ごと)。一時停止・停止で終了させる。 */
    private val galleryProcs = ConcurrentHashMap<String, Process>()

    /** アカウント一括の項目ごとの保存結果。一時停止をまたいで引き継ぐ。 */
    private val accountRuns = ConcurrentHashMap<String, AccountRun>()

    private val cancelRequested = java.util.Collections.synchronizedSet(HashSet<String>())
    private val pauseRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    /** pauseRequested のうち、Wi-Fi 限定の設定による自動一時停止であるもの。 */
    private val wifiPauseRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var aria2Ready = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var previewProcessId: String? = null

    private val itemRegex = Regex("""Downloading (?:item|video) (\d+) of (\d+)""")
    private val formatSuffixRegex = Regex("""\.f\d+$""")

    private fun workRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "work")

    private fun workDir(context: Context, id: String): File = File(workRoot(context), id)

    // ------------------------------------------------------------ persistence

    @Volatile
    private var restored = false

    /** 保存済みのキューを読み戻す (アプリ起動のたびに 1 回だけ)。実行中だった項目は待機中に戻し、続きから再開する。 */
    @Synchronized
    @OptIn(FlowPreview::class)
    fun restore(context: Context) {
        if (restored) return
        restored = true
        val app = context.applicationContext
        appContext = app
        val saved = QueueStore.load(app).map {
            if (it.status == Status.RUNNING) {
                it.copy(status = Status.QUEUED, eta = -1L, message = "前回の続きから再開します")
            } else {
                it
            }
        }
        // 起動前に追加された項目があれば、その後ろに保存分を並べる
        if (saved.isNotEmpty()) _items.update { current -> current + saved.filter { s -> current.none { it.id == s.id } } }
        // 以後の変化は、間隔を空けて保存する (進捗の更新ごとには書かない)
        scope.launch { _items.sample(2000).collect { QueueStore.save(app, it) } }
    }

    /** 追加・削除など、失いたくない変化の直後に呼んで、すぐ保存する。 */
    private fun persistNow() {
        val app = appContext ?: return
        val snapshot = _items.value
        scope.launch { QueueStore.save(app, snapshot) }
    }

    /** 復元した項目のうち、再開が必要なものがあれば true。 */
    fun hasRestorable(): Boolean = _items.value.any { it.status == Status.QUEUED }

    // ---------------------------------------------------------------- engine

    suspend fun ensureInit(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                appContext = app
                restore(app)
                try {
                    // 使われていない作業フォルダ (キューに無い項目・終わった項目) と、期限切れの取得済み記録を掃除する
                    val live = _items.value.filter { !it.isFinished }.map { it.id }.toSet()
                    workRoot(app).listFiles()?.forEach { if (it.name !in live) it.deleteRecursively() }
                    Archive.purge(app)
                    YoutubeDL.getInstance().init(app)
                    FFmpeg.getInstance().init(app)
                    // aria2c は高速モード専用。初期化できなくても通常のダウンロードには影響させない
                    aria2Ready = runCatching { Aria2c.getInstance().init(app) }.isSuccess
                    initialized = true
                    _engine.value = EngineState(ready = true, version = readVersion(app), note = "準備完了")
                    prepareGallery(app)
                } catch (e: Throwable) {
                    _engine.value = EngineState(ready = false, note = "初期化に失敗: ${e.message?.take(120)}")
                    throw e
                }
            }
        }
    }

    private fun readVersion(context: Context): String =
        runCatching { YoutubeDL.versionName(context) ?: YoutubeDL.version(context) }.getOrNull() ?: "不明"

    /** yt-dlp 本体をアプリ内から最新版へ更新する。 */
    suspend fun updateEngine(context: Context, nightly: Boolean) {
        val app = context.applicationContext
        if (_items.value.any { it.status == Status.RUNNING }) {
            _engine.update { it.copy(note = "ダウンロード中は更新できません") }
            return
        }
        try {
            ensureInit(app)
        } catch (e: Throwable) {
            return
        }
        _engine.update { it.copy(busy = true, note = "更新を確認中") }
        withContext(Dispatchers.IO) {
            // 動いている版を控えておく。更新後に動かなければ、この版へ戻す
            val before = YtdlpUpdater.probeVersion()
            val backedUp = YtdlpUpdater.backup(app)
            try {
                // 端末の Python で動く最新版を調べる (PyPI の requires_python)。調べられなければ従来どおり最新版へ
                val py = GalleryDl.pythonVersion(app)
                val pick = py?.let { runCatching { PyCompat.pickLatest("yt-dlp", it) }.getOrNull() }
                var note: String
                if (pick != null && !pick.isLatest) {
                    // 最新版は端末の Python では動かないので、動く最新版を直接入れる (NIGHTLY は選べない)
                    val cur = PyCompat.versionKey(before)
                    val want = PyCompat.versionKey(pick.version)
                    note = if (cur != null && want != null && PyCompat.compare(cur, want) >= 0) {
                        "すでに、この端末で動く最新版です"
                    } else {
                        YtdlpUpdater.installRelease(app, pick.version)
                        "${pick.version} に更新しました"
                    }
                    note += "\n最新版 ${pick.latestOverall} は Python ${pick.latestRequires.ifBlank { "新しい版" }} が必要で、" +
                        "この端末の Python ${PyCompat.pythonLabel(py!!)} では動かないため、動く最新版を選びました"
                } else {
                    val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
                    val status = YoutubeDL.getInstance().updateYoutubeDL(app, channel)
                    note = when (status) {
                        YoutubeDL.UpdateStatus.DONE -> "最新版に更新しました"
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "すでに最新版です"
                        else -> "更新が完了しました"
                    }
                }
                // 更新した版を実際に起動して確かめる。動かなければ元の版に戻す
                val after = YtdlpUpdater.probeVersion()
                if (after == null && backedUp && YtdlpUpdater.restore(app)) {
                    val restored = YtdlpUpdater.probeVersion()
                    _engine.value = EngineState(
                        ready = true, busy = false, version = restored ?: before ?: readVersion(app),
                        note = "更新した版がこの端末で動かなかったため、元の版に戻しました",
                    )
                } else {
                    _engine.value = EngineState(
                        ready = true, busy = false, version = after ?: readVersion(app), note = note,
                    )
                }
            } catch (e: Throwable) {
                // 更新の途中で失敗したときも、控えていた版へ戻してから知らせる
                val rolledBack = backedUp && YtdlpUpdater.probeVersion() == null && YtdlpUpdater.restore(app)
                _engine.value = EngineState(
                    ready = true, busy = false, version = YtdlpUpdater.probeVersion() ?: before ?: readVersion(app),
                    note = "更新に失敗: ${e.message?.take(120)}" + if (rolledBack) " (元の版に戻しました)" else "",
                )
            } finally {
                YtdlpUpdater.dropBackup(app)
            }
        }
    }

    // ----------------------------------------------------------- gallery-dl

    /** 同梱の gallery-dl を端末内へ展開し、版を読み取る。失敗しても yt-dlp 側には影響しない。 */
    private fun prepareGallery(app: Context) {
        _gallery.value = try {
            GalleryDl.ensureInstalled(app)
            EngineState(ready = true, version = GalleryDl.version(app) ?: "不明", note = "準備完了")
        } catch (e: Throwable) {
            EngineState(ready = false, note = "初期化に失敗: ${e.message?.take(120)}")
        }
    }

    /** gallery-dl 本体をアプリ内から PyPI の最新版へ更新する。 */
    suspend fun updateGalleryEngine(context: Context) {
        val app = context.applicationContext
        try {
            ensureInit(app)
        } catch (e: Throwable) {
            return
        }
        if (_gallery.value.busy) return
        // 先に busy にして、新しいアカウント項目の開始を止めてから、実行中の項目が無いか確かめる
        _gallery.update { it.copy(busy = true, note = "更新を確認中") }
        if (_items.value.any { it.status == Status.RUNNING && it.account != null }) {
            _gallery.update { it.copy(busy = false, note = "アカウント一括のダウンロード中は更新できません") }
            return
        }
        withContext(Dispatchers.IO) {
            try {
                if (!_gallery.value.ready) prepareGallery(app)
                _gallery.update { it.copy(busy = true, note = "更新を確認中") }
                val note = GalleryDl.update(app)
                _gallery.value = EngineState(
                    ready = true,
                    busy = false,
                    version = GalleryDl.version(app) ?: "不明",
                    note = note,
                )
            } catch (e: Throwable) {
                _gallery.update { it.copy(busy = false, note = "更新に失敗: ${e.message?.take(120)}") }
            }
        }
    }

    // --------------------------------------------------------------- preview

    /** ダウンロード前に、タイトル・サムネイル・選べる画質・再生リストの中身を取得する。 */
    suspend fun fetchPreview(context: Context, url: String, playlist: Boolean): PreviewData =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            ensureInit(app)
            val pid = "preview_" + UUID.randomUUID()
            previewProcessId = pid
            val req = YoutubeDLRequest(url)
            req.addOption("--dump-single-json")
            req.addOption("--flat-playlist")
            req.addOption("--no-warnings")
            req.addOption(if (playlist) "--yes-playlist" else "--no-playlist")
            if (playlist) {
                // 100 件ごとの続きページの取得に失敗しても、それまでの分を捨てずに返す
                req.addOption("--ignore-errors")
                req.addOption("--extractor-retries", "5")
                // 利用できない動画の取り直し処理に入ると、101 件目以降の続きが取れなくなることがある
                req.addOption("--compat-options", "no-youtube-unavailable-videos")
            }
            val response = try {
                YoutubeDL.getInstance().execute(req, pid, null)
            } finally {
                if (previewProcessId == pid) previewProcessId = null
            }
            val line = response.out.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
                ?: throw IllegalStateException("情報を取得できませんでした")
            val data = parsePreview(url, JSONObject(line))
            if (data.isPlaylist) fillMissingTitles(app, url, data, pid) else data
        }

    /**
     * 101 件目以降を取り直す方法 1 つ分。args は yt-dlp への追加オプション (名前, 値)、
     * print が true なら 1 行ずつ出力を読む。urlFn は URL を書き換える方法。
     */
    private class FillStrategy(
        val name: String,
        val args: List<Pair<String, String?>>,
        val print: Boolean,
        val probeOnly: Boolean = false,
        val urlFn: ((String) -> String)? = null,
    )

    private const val COMPAT = "no-youtube-unavailable-videos"
    private val PRINT_FMT = "%(playlist_index)s\t%(title)s\t%(duration)s"
    private fun playlistId(url: String): String? = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

    private val fillStrategies = listOf(
        FillStrategy(
            "A:compat",
            listOf("--dump-single-json" to null, "--flat-playlist" to null, "--compat-options" to COMPAT),
            print = false,
        ),
        FillStrategy(
            "B:compat-lazy",
            listOf("--flat-playlist" to null, "--lazy-playlist" to null, "--compat-options" to COMPAT, "--print" to PRINT_FMT),
            print = true,
        ),
        FillStrategy(
            "C:compat-skipwebpage",
            listOf(
                "--dump-single-json" to null, "--flat-playlist" to null, "--compat-options" to COMPAT,
                "--extractor-args" to "youtubetab:skip=webpage",
            ),
            print = false,
        ),
        FillStrategy(
            "D:music-url",
            listOf("--dump-single-json" to null, "--flat-playlist" to null, "--compat-options" to COMPAT),
            print = false,
            urlFn = { u -> playlistId(u)?.let { "https://music.youtube.com/playlist?list=$it" } ?: u },
        ),
        FillStrategy(
            "E:clean-url",
            listOf("--dump-single-json" to null, "--flat-playlist" to null, "--compat-options" to COMPAT),
            print = false,
            urlFn = { u -> playlistId(u)?.let { "https://www.youtube.com/playlist?list=$it" } ?: u },
        ),
        FillStrategy(
            "F:plain",
            listOf("--dump-single-json" to null, "--flat-playlist" to null),
            print = false,
        ),
        // 診断用: 一覧ではなく動画を 1 本ずつ開いて取る (遅いので先頭 3 件だけ試す)
        FillStrategy(
            "G:full(3)",
            listOf("--skip-download" to null, "--compat-options" to COMPAT, "--print" to PRINT_FMT),
            print = true, probeOnly = true,
        ),
    )

    /**
     * 一覧が途中で切れて「#101」のような仮の項目になっている分を、100 件ずつ範囲指定で取り直してタイトルを埋める。
     * 複数の取得方法を順に試し、効いた方法をその後の範囲にも使う。
     * 全部だめだったときは、各方法の詳細ログを Download/KyuuriDL/ に保存する (件数は削らない)。
     */
    private fun fillMissingTitles(app: Context, url: String, data: PreviewData, pid: String): PreviewData {
        val entries = data.entries.toMutableList()
        var from = entries.indexOfFirst { it.durationSec == null && it.title == "#${it.index}" }
            .let { if (it < 0) return data else entries[it].index }
        val last = entries.last().index
        val diag = StringBuilder()
        diag.appendLine("KyuuriDL preview diagnostics")
        diag.appendLine("url=$url")
        diag.appendLine("yt-dlp=${runCatching { YtdlpUpdater.probeVersion() }.getOrNull()}")
        diag.appendLine("python=${runCatching { GalleryDl.pythonVersion(app)?.joinToString(".") }.getOrNull()}")
        diag.appendLine("listed=${data.entries.count { it.title != "#${it.index}" }} declared=$last")
        var preferred: FillStrategy? = null
        var failedRange: String? = null

        fun remaining(a: Int, b: Int) = entries.any { it.index in a..b && it.title == "#${it.index}" }
        fun apply(idx: Int, title: String?, dur: Long?): Boolean {
            if (title.isNullOrBlank() || title == "NA") return false
            val pos = entries.indexOfFirst { it.index == idx }
            if (pos < 0) return false
            entries[pos] = entries[pos].copy(title = title, durationSec = dur)
            return true
        }

        /** 1 つの方法で [a, b] を取る。1 件でもタイトルが取れたら true。 */
        fun attempt(st: FillStrategy, a: Int, b: Int, verbose: Boolean, cid: String): Boolean {
            var got = 0
            diag.appendLine("--- ${st.name}${if (verbose) " (-v)" else ""} $a:$b")
            try {
                val req = YoutubeDLRequest(st.urlFn?.invoke(url) ?: url)
                st.args.forEach { (k, v) -> if (v == null) req.addOption(k) else req.addOption(k, v) }
                req.addOption("--no-warnings")
                req.addOption("--yes-playlist")
                req.addOption("--ignore-errors")
                req.addOption("--extractor-retries", "5")
                req.addOption("--playlist-items", "$a:$b")
                if (verbose) req.addOption("-v")
                val res = YoutubeDL.getInstance().execute(req, cid, null)
                if (st.print) {
                    var n = 0
                    res.out.lineSequence().forEach { line ->
                        val p = line.split('\t')
                        if (p.size < 2) return@forEach
                        val idx = p[0].trim().toIntOrNull() ?: (a + n)
                        n++
                        if (apply(idx, p[1].trim(), p.getOrNull(2)?.trim()?.toDoubleOrNull()?.toLong())) got++
                    }
                } else {
                    val l = res.out.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
                    val arr = l?.let { JSONObject(it).optJSONArray("entries") }
                    diag.appendLine("entries=${arr?.length() ?: "none"}")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        if (apply(a + i, e.str("title"), e.durationOrNull())) got++
                    }
                }
                diag.appendLine("applied=$got exit=${res.exitCode}")
                val err = res.err.lines().filter { it.isNotBlank() }.takeLast(if (verbose) 120 else 8)
                if (err.isNotEmpty()) diag.appendLine("stderr:\n" + err.joinToString("\n").take(8000))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                diag.appendLine("exception: " + (e.message ?: e.javaClass.simpleName).takeLast(1500))
            }
            return got > 0
        }

        while (from <= last) {
            val to = minOf(from + 99, last)
            val cid = "$pid-$from"
            previewProcessId = cid
            try {
                val order = listOfNotNull(preferred) + fillStrategies.filter { it !== preferred && !it.probeOnly }
                var ok = false
                for ((k, st) in order.withIndex()) {
                    if (attempt(st, from, to, false, "$cid-$k")) { preferred = st; ok = true; break }
                }
                if (!ok && preferred == null) {
                    // 全部だめ。診断として -v 付きの A と、動画を直接開く方法を (先頭 3 件だけ) 試す
                    attempt(fillStrategies[0], from, minOf(from + 2, to), true, "$cid-v")
                    attempt(fillStrategies.last(), from, minOf(from + 2, to), false, "$cid-e")
                    failedRange = "$from:$to"
                    break
                }
            } finally {
                if (previewProcessId == cid) previewProcessId = null
            }
            from = to + 1
        }

        val missing = entries.filter { it.title == "#${it.index}" }
        if (missing.isNotEmpty() && failedRange != null) {
            // 取得できない項目は保存もできない (YouTube がその先を返さない)。一覧から外し、理由を画面に出す
            entries.removeAll { it.title == "#${it.index}" }
            val note = "この再生リストは ${last} 件ありますが、YouTube が ${entries.size} 件までしか返さないため、" +
                "${missing.first().index} 件目以降の ${missing.size} 件は取得できません。" +
                "(ログインしていない状態では、100 件を超える分が返らないことがあります)"
            return data.copy(entries = entries, limitNote = note, diagLog = diag.toString())
        }
        return data.copy(entries = entries)
    }

    fun cancelPreview() {
        val pid = previewProcessId ?: return
        scope.launch { YoutubeDL.getInstance().destroyProcessById(pid) }
    }

    private fun parsePreview(url: String, json: JSONObject): PreviewData {
        val isPlaylist = json.str("_type") == "playlist"
        val title = json.str("title") ?: json.str("id") ?: url
        val entries = mutableListOf<PreviewEntry>()
        val heights = sortedSetOf<Int>()
        var thumb: String? = null

        if (isPlaylist) {
            val arr = json.optJSONArray("entries")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    entries += PreviewEntry(
                        index = i + 1,
                        title = e.str("title") ?: e.str("id") ?: "#${i + 1}",
                        durationSec = e.durationOrNull(),
                    )
                    if (thumb == null) thumb = e.bestThumbnail()
                }
            }
            // 一覧が途中 (100 件など) で切れていても、総件数が分かれば残りの番号も選べるようにする。
            // 保存時は --playlist-items で番号指定するので、取得し直した全件から正しく選ばれる。
            val declared = maxOf(json.optInt("playlist_count", 0), json.optInt("n_entries", 0))
            for (n in entries.size + 1..declared) {
                entries += PreviewEntry(index = n, title = "#$n", durationSec = null)
            }
            if (thumb == null) thumb = json.bestThumbnail()
        } else {
            thumb = json.bestThumbnail()
            val formats = json.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    if (f.str("vcodec") == "none") continue
                    f.intOrNull("height")?.let { heights += it }
                }
            }
        }

        return PreviewData(
            url = url,
            title = title,
            uploader = json.str("uploader") ?: json.str("channel"),
            site = json.str("extractor_key") ?: json.str("extractor"),
            durationSec = if (isPlaylist) null else json.durationOrNull(),
            thumbnail = thumb?.let { loadBitmap(it) },
            isPlaylist = isPlaylist,
            entries = entries,
            heights = heights.sortedDescending(),
        )
    }

    private fun loadBitmap(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 800) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toInt()

    private fun JSONObject.durationOrNull(): Long? =
        if (isNull("duration")) null else optDouble("duration", Double.NaN).takeIf { !it.isNaN() }?.toLong()

    private fun JSONObject.bestThumbnail(): String? {
        str("thumbnail")?.let { return it }
        val arr = optJSONArray("thumbnails") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            arr.optJSONObject(i)?.str("url")?.let { return it }
        }
        return null
    }

    // ----------------------------------------------------------------- queue

    fun enqueue(
        context: Context,
        urls: List<String>,
        options: Options,
        playlistItems: String? = null,
        title: String? = null,
    ) {
        appContext = context.applicationContext
        val added = urls.map {
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = it,
                options = options,
                playlistItems = playlistItems,
                title = title,
            )
        }
        // 表示は新しい順、実行は古い順
        _items.update { added.reversed() + it }
        persistNow()
        applyNetworkPolicy(context)
        startService(context)
    }

    /** アカウント一括ダウンロード (gallery-dl) をキューに追加する。 */
    fun enqueueAccounts(context: Context, jobs: List<AccountJob>) {
        appContext = context.applicationContext
        val added = jobs.map {
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = it.target.url,
                options = Options(),
                title = it.target.folder,
                account = it,
            )
        }
        _items.update { added.reversed() + it }
        persistNow()
        applyNetworkPolicy(context)
        startService(context)
    }

    /** 同じアカウントが、まだ終わっていない項目として入っているか。 */
    fun hasUnfinishedAccount(url: String): Boolean =
        _items.value.any { it.account?.target?.url == url && !it.isFinished }

    fun startService(context: Context) {
        val app = context.applicationContext
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    fun nextQueued(): DownloadItem? = _items.value.lastOrNull { it.status == Status.QUEUED }

    /** 待機中の最古の項目を実行中にして返す。複数ワーカーから呼んでも二重取得されない。 */
    @Synchronized
    fun claimNext(): DownloadItem? {
        // Wi-Fi 限定の設定中にモバイル回線なら、新しい項目は開始しない
        if (policyBlocks()) {
            applyNetworkPolicy(appContext ?: return null)
            return null
        }
        val galleryBusy = _gallery.value.busy
        val next = _items.value.lastOrNull {
            it.status == Status.QUEUED && !(galleryBusy && it.account != null)
        } ?: return null
        // 前回の実行で取り残された要求を消してから実行中にする (これ以降の要求だけが有効)
        pauseRequested.remove(next.id)
        cancelRequested.remove(next.id)
        wifiPauseRequested.remove(next.id)
        mutate(next.id) { it.copy(status = Status.RUNNING, eta = -1L, message = "準備中") }
        return next
    }

    fun find(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun cancel(id: String) {
        val item = find(id) ?: return
        when (item.status) {
            Status.QUEUED -> mutate(id) { it.copy(status = Status.CANCELED, message = "") }
            Status.PAUSED -> {
                mutate(id) { it.copy(status = Status.CANCELED, eta = -1L, message = "") }
                accountRuns.remove(id)
                deleteWork(id)
            }
            Status.RUNNING -> {
                cancelRequested.add(id)
                scope.launch { stopProcess(id) }
            }
            else -> Unit
        }
    }

    fun cancelAll() {
        _items.value.filter { it.status == Status.QUEUED || it.status == Status.RUNNING || it.status == Status.PAUSED }
            .forEach { cancel(it.id) }
    }

    /** 実行中のプロセスを止める。途中のファイルは残るので、再開すると続きから取得する。 */
    fun pause(id: String) {
        val item = find(id) ?: return
        when (item.status) {
            Status.QUEUED -> mutate(id) { it.copy(status = Status.PAUSED, message = "一時停止中") }
            Status.RUNNING -> {
                wifiPauseRequested.remove(id)
                pauseRequested.add(id)
                mutate(id) { it.copy(message = "一時停止しています") }
                scope.launch { stopProcess(id) }
            }
            else -> Unit
        }
    }

    fun pauseAll() {
        _items.value.filter { it.isActive }.forEach { pause(it.id) }
    }

    /**
     * 再開する。Wi-Fi 限定の設定中にモバイル回線だった場合は開始せず、Wi-Fi 接続を待つ状態にして false を返す。
     */
    fun resume(context: Context, id: String): Boolean {
        if (find(id)?.status != Status.PAUSED) return true
        if (!networkAllowed(context)) {
            markWaitingForWifi(listOf(id))
            startService(context)
            return false
        }
        mutate(id) { it.copy(status = Status.QUEUED, waitingForWifi = false, message = "待機中") }
        startService(context)
        return true
    }

    fun resumeAll(context: Context): Boolean {
        val paused = _items.value.filter { it.status == Status.PAUSED }
        if (paused.isEmpty()) return true
        if (!networkAllowed(context)) {
            markWaitingForWifi(paused.map { it.id })
            startService(context)
            return false
        }
        paused.forEach { p ->
            mutate(p.id) { it.copy(status = Status.QUEUED, waitingForWifi = false, message = "待機中") }
        }
        startService(context)
        return true
    }

    /** 失敗・停止した項目を最初からやり直す。 */
    fun retry(context: Context, id: String) {
        val item = find(id) ?: return
        if (item.status != Status.FAILED && item.status != Status.CANCELED) return
        accountRuns.remove(id)
        mutate(id) {
            it.copy(status = Status.QUEUED, progress = 0f, eta = -1L, message = "待機中", savedFiles = emptyList())
        }
        applyNetworkPolicy(context)
        startService(context)
    }

    fun remove(id: String) {
        val item = find(id) ?: return
        if (item.status == Status.RUNNING) return
        if (item.status == Status.PAUSED) {
            accountRuns.remove(id)
            deleteWork(id)
        }
        _items.update { list -> list.filterNot { it.id == id } }
        persistNow()
    }

    fun clearFinished() {
        _items.update { list -> list.filterNot { it.isFinished } }
        persistNow()
    }

    // ------------------------------------------------------- wifi-only policy

    /** Wi-Fi (または有線 LAN / Wi-Fi 上の VPN) に接続しているか。 */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    /** いまダウンロードを実行してよい回線か。設定が OFF なら常に true。 */
    fun networkAllowed(context: Context): Boolean = !Prefs.wifiOnly(context) || isOnWifi(context)

    private fun policyBlocks(): Boolean {
        val ctx = appContext ?: return false
        return !networkAllowed(ctx)
    }

    fun hasWaitingForWifi(): Boolean =
        _items.value.any { it.status == Status.PAUSED && it.waitingForWifi }

    private const val WAIT_WIFI = "Wi-Fi接続待ち"

    private fun markWaitingForWifi(ids: List<String>) {
        ids.forEach { id ->
            mutate(id) { it.copy(status = Status.PAUSED, waitingForWifi = true, eta = -1L, message = WAIT_WIFI) }
        }
    }

    /**
     * 「Wi-Fi接続時のみ実行」を現在の回線に合わせて適用する。サービスのループから繰り返し呼ばれる。
     * - モバイル回線: 待機中の項目は Wi-Fi 待ちにし、実行中の項目は一時停止する
     * - Wi-Fi (または設定 OFF): Wi-Fi 待ちだった項目を自動で再開する
     */
    @Synchronized
    fun applyNetworkPolicy(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (networkAllowed(app)) {
            val waiting = _items.value.filter { it.status == Status.PAUSED && it.waitingForWifi }
            if (waiting.isEmpty()) return
            waiting.forEach { w ->
                mutate(w.id) { it.copy(status = Status.QUEUED, waitingForWifi = false, message = "待機中") }
            }
            startService(app)
            return
        }
        _items.value.forEach { item ->
            when (item.status) {
                Status.QUEUED -> markWaitingForWifi(listOf(item.id))
                Status.RUNNING -> {
                    val manualPause = item.id in pauseRequested && item.id !in wifiPauseRequested
                    // 変換中はネットワークを使わないので、そのまま完了させる
                    if (!manualPause && item.id !in cancelRequested && item.message != MSG_CONVERTING) {
                        pauseRequested.add(item.id)
                        wifiPauseRequested.add(item.id)
                        mutate(item.id) { it.copy(message = "Wi-Fi接続待ちのため一時停止しています") }
                        scope.launch { stopProcess(item.id) }
                    }
                }
                else -> Unit
            }
        }
    }

    private const val MSG_CONVERTING = "変換中"

    /** 実行中の yt-dlp / gallery-dl のプロセスを終了させる。 */
    private fun stopProcess(id: String) {
        galleryProcs[id]?.let { p ->
            p.destroy()
            runCatching { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
        }
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    private fun deleteWork(id: String) {
        val ctx = appContext ?: return
        scope.launch { workDir(ctx, id).deleteRecursively() }
    }

    private fun mutate(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    // ------------------------------------------------------------- execution

    suspend fun run(context: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        appContext = app
        mutate(item.id) { it.copy(status = Status.RUNNING, eta = -1L, message = "準備中") }

        // 項目ごとに固定の作業フォルダを使う。一時停止後の再開で .part ファイルが引き継がれる。
        val work = workDir(app, item.id).also { it.mkdirs() }
        var error: Throwable? = null

        try {
            ensureInit(app)
            if (item.account != null) runAccount(app, item, work) else runYtdlp(app, item, work)
        } catch (e: Throwable) {
            error = e
        }

        val noNewOk = emptyOk.remove(item.id)
        val paused = pauseRequested.remove(item.id)
        val wifiPaused = wifiPauseRequested.remove(item.id)
        val canceled = cancelRequested.remove(item.id)
        val interrupted = error is YoutubeDL.CanceledException
        var keepWork = false

        try {
            when {
                error != null && paused -> {
                    keepWork = true
                    mutate(item.id) {
                        it.copy(
                            status = Status.PAUSED,
                            eta = -1L,
                            waitingForWifi = wifiPaused,
                            message = if (wifiPaused) WAIT_WIFI else "一時停止中",
                        )
                    }
                }
                error != null && (canceled || interrupted) -> {
                    mutate(item.id) { it.copy(status = Status.CANCELED, eta = -1L, message = "") }
                }
                else -> {
                    val result = if (item.account != null) accountResult(item.id) else saveFiles(app, work)
                    val files = result.files
                    val saved = files.size
                    val head = result.head ?: "$saved 件を保存"
                    when {
                        saved > 0 && error == null -> mutate(item.id) {
                            it.copy(
                                status = Status.DONE, progress = 1f, eta = -1L, savedFiles = files,
                                message = "$head (${result.where})",
                            )
                        }
                        // 取得済みの記録により、新しい分が 1 件も無かった (失敗ではない)
                        saved == 0 && error == null && noNewOk -> mutate(item.id) {
                            it.copy(
                                status = Status.DONE, progress = 1f, eta = -1L, savedFiles = emptyList(),
                                message = "新しい分はありませんでした (取得済みはスキップ)",
                            )
                        }
                        saved > 0 -> mutate(item.id) {
                            it.copy(
                                status = Status.DONE, progress = 1f, eta = -1L, savedFiles = files,
                                message = if (item.account != null) "$head (一部は失敗: ${cleanError(error)})"
                                else "$head (一部は失敗)",
                            )
                        }
                        else -> mutate(item.id) {
                            it.copy(status = Status.FAILED, eta = -1L, message = cleanError(error))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            mutate(item.id) { it.copy(status = Status.FAILED, eta = -1L, message = "保存に失敗: ${e.message?.take(120)}") }
        } finally {
            if (!keepWork) {
                work.deleteRecursively()
                accountRuns.remove(item.id)
            }
        }
    }

    /** 「新しい分が無かった」ことを成功として扱ってよい項目 (取得済みの記録があった項目)。 */
    private val emptyOk = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 通常のダウンロード (yt-dlp)。
     * 通信の途切れなどで失敗したときは、同じ作業フォルダで自動的にやり直す (取得済みの .part から続きを取得する)。
     * 一時停止・停止の要求や、再試行しても直らない種類のエラー (動画が非公開など) のときはやり直さない。
     */
    private fun runYtdlp(app: Context, item: DownloadItem, work: File) {
        // 再生リストは取得済みの分を飛ばす。記録が元からあるときだけ「新しい分なし」を成功扱いにする
        val archive = Archive.forItem(app, item)
        val hadArchive = Archive.hasRecords(archive)
        Archive.touch(archive)
        try {
            runYtdlpLoop(app, item, work)
        } finally {
            Archive.touch(archive)
        }
        if (hadArchive) emptyOk += item.id
    }

    private fun runYtdlpLoop(app: Context, item: DownloadItem, work: File) {
        var attempt = 0
        while (true) {
            try {
                runYtdlpOnce(app, item, work)
                return
            } catch (e: Throwable) {
                attempt++
                val requested = pauseRequested.contains(item.id) || cancelRequested.contains(item.id)
                if (requested || e is YoutubeDL.CanceledException || attempt > MAX_AUTO_RETRY || !isRetryable(e, work)) {
                    throw e
                }
                val waitSec = minOf(5 * attempt, 60)
                mutate(item.id) { it.copy(eta = -1L, message = "接続が切れました。再試行します ($attempt/$MAX_AUTO_RETRY)") }
                // 待つ間も、一時停止・停止の要求にはすぐ応える
                var waited = 0
                while (waited < waitSec * 2) {
                    if (pauseRequested.contains(item.id) || cancelRequested.contains(item.id)) throw e
                    Thread.sleep(500)
                    waited++
                }
                // 回線が戻るまで待つ (最長 10 分)
                var offline = 0
                while (!hasInternet(app) && offline < 1200) {
                    if (pauseRequested.contains(item.id) || cancelRequested.contains(item.id)) throw e
                    mutate(item.id) { it.copy(message = "ネットワーク接続を待っています") }
                    Thread.sleep(500)
                    offline++
                }
            }
        }
    }

    private const val MAX_AUTO_RETRY = 8

    private fun hasInternet(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val retryHints = listOf(
        "timed out", "timeout", "connection", "reset by peer", "broken pipe", "incompleteread", "incomplete read",
        "unexpected eof", "eof occurred", "ssl", "http error 5", "http error 403", "http error 429",
        "unable to download", "temporary failure", "network is unreachable", "name resolution", "remote end closed",
        "fragment", "giving up after", "retrying",
    )

    /** 通信まわりの一時的な失敗か (動画が削除された・非公開などは直らないので、やり直さない)。 */
    private fun isRetryable(e: Throwable, work: File): Boolean {
        val msg = (e.message ?: "").lowercase()
        if (retryHints.any { it in msg }) return true
        // 取得途中のファイルが残っている = 途中までは進んでいたので、続きから取り直す価値がある
        return work.walkTopDown().any { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".ytdl")) }
    }

    private fun runYtdlpOnce(app: Context, item: DownloadItem, work: File) {
        val request = buildRequest(item, work, Archive.forItem(app, item), fastConnections(app))
        // 開始直前に一時停止・停止が要求されていたら、プロセスを起動せずに中断する
        if (pauseRequested.contains(item.id) || cancelRequested.contains(item.id)) {
            throw IllegalStateException("interrupted before start")
        }
        var index = 1
        var total = 1

        YoutubeDL.getInstance().execute(request, item.id) { percent, eta, line ->
            val m = itemRegex.find(line)
            if (m != null) {
                index = m.groupValues[1].toInt()
                total = m.groupValues[2].toInt()
                mutate(item.id) {
                    it.copy(message = "$index / $total", progress = (index - 1f) / total.coerceAtLeast(1))
                }
            }
            titleFrom(line)?.let { t -> mutate(item.id) { it.copy(title = t) } }
            if (line.startsWith("[ExtractAudio]") || line.startsWith("[Merger]")) {
                mutate(item.id) { it.copy(message = MSG_CONVERTING) }
            }
            if (percent >= 0f) {
                val overall = if (total > 1) ((index - 1) + percent / 100f) / total else percent / 100f
                mutate(item.id) { it.copy(progress = overall.coerceIn(0f, 1f), eta = eta) }
            }
        }
    }

    // --------------------------------------------------- account (gallery-dl)

    /** アカウント一括の、1 項目ぶんの保存結果。一時停止をまたいで引き継ぐ。 */
    private class AccountRun {
        val saved = ArrayList<SavedFile>()
        var images = 0
        var videos = 0
        var failed = 0

        @Volatile
        var where = ""

        fun counts(): String = listOfNotNull(
            if (images > 0) "画像 $images" else null,
            if (videos > 0) "動画 $videos" else null,
        ).joinToString(" / ")
    }

    /** 保存先の状態。選んだフォルダに書き込めればそこへ、だめなら標準の保存先へ退避する。 */
    private class AccountSink(val context: Context, val folder: String) {
        val tree: Uri? = SaveLocation.treeUri(context)
        val base: DocumentFile? =
            tree?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.exists() && it.canWrite() }
        val dirs = HashMap<FileKind, DocumentFile?>()
        var fellBack = false
    }

    /**
     * アカウント一括ダウンロード (gallery-dl)。
     * gallery-dl が 1 ファイル取得するたびに、その場で保存先の images / videos へ移す。
     * (全部取り終えてから移すと、大きなアカウントでは端末の空き容量が二重に必要になるため)
     */
    private fun runAccount(app: Context, item: DownloadItem, work: File) {
        val job = item.account ?: return
        val target = job.target
        if (CookieStore.requiresLogin(target.siteHost) && !CookieStore.loggedIn(app, target.siteHost)) {
            throw IllegalStateException(needLogin(target.siteHost))
        }
        // 開始直前に一時停止・停止が要求されていたら、プロセスを起動せずに中断する
        if (pauseRequested.contains(item.id) || cancelRequested.contains(item.id)) {
            throw IllegalStateException("interrupted before start")
        }

        val archive = Archive.forItem(app, item)
        val hadArchive = Archive.hasRecords(archive)
        Archive.touch(archive)
        val state = accountRuns.getOrPut(item.id) { AccountRun() }
        // 再開時は、前回 書き込めずに残ったファイルをもう一度試すので、失敗の数は数え直す
        state.failed = 0
        val raw = File(work, "raw").also { it.mkdirs() }
        // 再開時 (一時停止・アプリ再起動の後) は、前回までに保存済みの印のファイルが残っている
        val hadMarkers = raw.walkTopDown().any { it.isFile }
        val sink = AccountSink(app, target.folder)
        val rawPrefix = raw.absolutePath + File.separator
        val log = java.util.ArrayDeque<String>()

        val proc = GalleryDl.start(app, galleryArgs(app, job, raw, archive), work)
        galleryProcs[item.id] = proc
        try {
            // 起動の隙間に要求が入っていたら、すぐ止める
            if (pauseRequested.contains(item.id) || cancelRequested.contains(item.id)) proc.destroy()

            proc.inputStream.bufferedReader().forEachLine { text ->
                val line = text.trimEnd()
                if (line.isEmpty()) return@forEachLine
                // "# パス" は取得済みでスキップしたもの。保存し損ねて残っていれば、ここで保存し直す
                val path = if (line.startsWith("# ")) line.substring(2) else line
                if (path.startsWith(rawPrefix)) {
                    collectGalleryFile(app, item.id, job.media, state, sink, File(path))
                } else {
                    log.addLast(line)
                    if (log.size > 12) log.removeFirst()
                    if (line.contains("rate limit", ignoreCase = true)) {
                        mutate(item.id) { it.copy(message = "レート制限のため待機中 (自動で再開します)") }
                    }
                }
            }
            val exit = proc.waitFor()
            if (exit != 0) throw IOException(galleryError(log, exit))
            if (state.saved.isEmpty()) {
                // 取得済みの記録・保存済みの印があるなら、新しい分が無かっただけ (失敗ではない)
                if (hadArchive || hadMarkers) emptyOk += item.id
                else throw IOException("保存できる画像・動画が見つかりませんでした。アカウントの公開範囲やログイン状態を確認してください")
            }
            if (state.failed > 0) throw IOException("${state.failed} 件を保存先へ書き込めませんでした")
        } finally {
            galleryProcs.remove(item.id)
            Archive.touch(archive)
            proc.destroy()
            state.where = sinkWhere(sink)
        }
    }

    /** gallery-dl が取得し終えた 1 ファイルを、保存先の images / videos へ移す。 */
    private fun collectGalleryFile(
        app: Context,
        id: String,
        media: MediaKind,
        state: AccountRun,
        sink: AccountSink,
        file: File,
    ) {
        // 0 バイトは「保存済み」の目印 (下の markSaved)。何もしない
        if (!file.isFile || file.length() == 0L) return
        val kind = FileKind.of(file.extension) ?: return
        if (!media.includes(kind)) {
            markSaved(file)
            return
        }
        val saved = sinkSave(sink, file, kind)
        if (saved == null) {
            // 書き込めなかった。ファイルは残しておき、再開したときにもう一度試す
            state.failed++
            return
        }
        state.saved += saved
        if (kind == FileKind.IMAGE) state.images++ else state.videos++
        markSaved(file)
        mutate(id) { it.copy(message = state.counts() + " を保存済み") }
    }

    /**
     * 保存し終えたファイルを 0 バイトにする。中身は端末の容量を空け、ファイル自体は残すことで、
     * 一時停止からの再開時に gallery-dl が「取得済み」と判断して飛ばす (目印として使う)。
     */
    private fun markSaved(file: File) {
        try {
            RandomAccessFile(file, "rw").use { it.setLength(0) }
        } catch (e: Exception) {
            runCatching { file.delete() }
        }
    }

    private fun sinkSave(sink: AccountSink, file: File, kind: FileKind): SavedFile? {
        val rel = sink.folder + "/" + kind.folder
        val base = sink.base
        if (base != null) {
            val dir = sink.dirs.getOrPut(kind) { ensureDir(base, rel) }
            if (dir != null) putTreeFile(sink.context, dir, file)?.let { return it }
        }
        if (sink.tree != null) sink.fellBack = true
        return putMediaStore(sink.context, file, SaveLocation.DEFAULT_PATH + "/" + rel)
    }

    private fun sinkWhere(sink: AccountSink): String = when {
        sink.tree == null -> "${SaveLocation.DEFAULT_PATH}/${sink.folder}"
        sink.fellBack -> "指定フォルダに書き込めず ${SaveLocation.DEFAULT_PATH}/${sink.folder} へ保存"
        else -> "${SaveLocation.label(sink.context)}/${sink.folder}"
    }

    private fun accountResult(id: String): SaveResult {
        val st = accountRuns[id] ?: return SaveResult(emptyList(), "")
        val counts = st.counts()
        return SaveResult(st.saved.toList(), st.where, head = if (counts.isEmpty()) null else "$counts を保存")
    }

    private fun galleryArgs(app: Context, job: AccountJob, raw: File, archive: File?): List<String> {
        val args = ArrayList<String>()
        // 端末内の設定ファイルは読まない (アプリが渡す指定だけで動かす)
        args += "--config-ignore"
        args += listOf("-d", raw.absolutePath)
        // 取得済みの投稿は飛ばして、新着だけ取る
        archive?.let { args += listOf("--download-archive", it.absolutePath) }
        if (PixivAuth.isPixiv(job.target.siteHost)) {
            // Pixiv は Cookie ではなくリフレッシュトークンで認証する
            PixivAuth.token(app)?.let { args += listOf("-o", "refresh-token=$it") }
        } else {
            val cookies = CookieStore.file(app, job.target.siteHost)
            if (cookies.isFile && cookies.length() > 0) args += listOf("--cookies", cookies.absolutePath)
        }
        // 通信エラーは自動で再試行し、サイトに負荷をかけすぎないよう少し間隔を空ける
        args += listOf("-R", "4", "--http-timeout", "30", "--sleep-request", "0.5-1.5")
        // ファイル名の禁止文字 (: ? * など) を置き換える。保存先 (MediaStore / SAF) で書き込みに失敗しないように
        args += listOf("-o", "path-restrict=windows")
        when (job.media) {
            MediaKind.IMAGES -> args += listOf("--filter", FileKind.filterExpression(FileKind.IMAGE))
            MediaKind.VIDEOS -> args += listOf("--filter", FileKind.filterExpression(FileKind.VIDEO))
            MediaKind.BOTH -> Unit
        }
        // X は動画の取得そのものを止められる (画像のみのとき、動画の情報を調べる手間も省く)
        if (job.target.isTwitter && job.media == MediaKind.IMAGES) args += listOf("-o", "videos=false")
        args += job.target.url
        return args
    }

    /** gallery-dl のログの末尾から、利用者向けのエラー文を作る。 */
    private fun galleryError(log: Collection<String>, exit: Int): String {
        val line = log.lastOrNull { it.contains("error", ignoreCase = true) }
            ?: log.lastOrNull()
            ?: return "gallery-dl が異常終了しました (終了コード $exit)"
        val text = line.replace(Regex("^(\\[[^\\]]*\\])+\\s*"), "").trim()
        val lower = line.lowercase()
        val message = when {
            "no suitable extractor" in lower || "unsupported url" in lower -> "このURLには対応していません ($text)"
            "refresh-token" in lower || "refresh token" in lower ->
                "Pixivのログインが必要、または期限切れです。「ログイン」からログインし直してください ($text)"
            "login" in lower || "authoriz" in lower || "auth_token" in lower || "401" in lower ->
                "ログインが必要、または期限切れです。「ログイン」からログインし直してください ($text)"
            "404" in lower || "not found" in lower || "does not exist" in lower ->
                "アカウントが見つかりません ($text)"
            else -> text
        }
        return message.take(220)
    }

    private fun needLogin(siteHost: String): String =
        (if (siteHost == "x.com") "X" else "Pixiv") + "にログインしていません。入力欄の「ログイン」からログインしてください"

    /** 高速モードがONなら接続数 (4〜16)、OFFなら null。 */
    private fun fastConnections(context: Context): Int? =
        if (Prefs.fastMode(context)) Prefs.fastConnections(context) else null

    private fun buildRequest(item: DownloadItem, work: File, archive: File?, fast: Int?): YoutubeDLRequest {
        val o = item.options
        val req = YoutubeDLRequest(item.url)
        req.addOption("--no-mtime")
        req.addOption("--newline")
        req.addOption("--windows-filenames")
        // 高速モード: 断片は選んだ本数で並列に取り、1 本のファイル形式は aria2c で分割して取る
        req.addOption("--concurrent-fragments", (fast ?: 4).toString())
        if (fast != null && aria2Ready) {
            req.addOption("--downloader", "libaria2c.so")
            req.addOption("--downloader-args", "aria2c:-x $fast -s $fast -k 1M")
        }
        // 長い動画は途中で通信が揺らぎやすいので、無通信の検出と再試行を強めにする (続きは .part から再開される)
        req.addOption("--socket-timeout", "30")
        req.addOption("--retries", "20")
        req.addOption("--fragment-retries", "50")
        req.addOption("--extractor-retries", "5")
        req.addOption("--file-access-retries", "5")
        req.addOption("--retry-sleep", "http:exp=1:30")
        req.addOption("--retry-sleep", "fragment:exp=1:30")

        val base = work.absolutePath
        if (o.playlist) {
            req.addOption("--yes-playlist")
            req.addOption("--ignore-errors")
            req.addOption("--compat-options", "no-youtube-unavailable-videos")
            archive?.let { req.addOption("--download-archive", it.absolutePath) }
            item.playlistItems?.let { req.addOption("--playlist-items", it) }
            req.addOption(
                "-o",
                "$base/%(playlist_title|Singles).80B/%(playlist_index&{}. |)s%(title).120B.%(ext)s",
            )
        } else {
            req.addOption("--no-playlist")
            req.addOption("-o", "$base/%(title).120B.%(ext)s")
        }

        when (o.mode) {
            Mode.VIDEO -> {
                req.addOption("-f", "bv*+ba/b")
                req.addOption("-S", buildVideoSort(o))
                req.addOption("--merge-output-format", o.container.code)
            }
            Mode.AUDIO -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", o.audioFormat.code)
                req.addOption("--audio-quality", o.audioQuality.value)
                // タイトル・アーティスト等のタグと、動画のサムネイルをアルバムアートとして埋め込む。
                // サムネイルは webp で配信されることが多いので、どのプレーヤーでも読める jpg へ変換する。
                req.addOption("--embed-metadata")
                req.addOption("--embed-thumbnail")
                req.addOption("--convert-thumbnails", "jpg")
            }
        }
        return req
    }

    /**
     * yt-dlp の並べ替え指定 (-S)。解像度を最優先にし、その次にコーデック・音声形式の好みを反映する。
     * 指定した画質が無い場合は、近い画質が自動で選ばれる。
     */
    private fun buildVideoSort(o: Options): String {
        val parts = mutableListOf<String>()
        parts += o.quality.height?.let { "res:$it" } ?: "res"

        val videoCodec = when (o.codec) {
            VideoCodec.H264 -> "h264"
            VideoCodec.VP9 -> "vp9"
            VideoCodec.AV1 -> "av1"
            VideoCodec.AUTO -> when (o.container) {
                VideoContainer.MP4 -> "h264"
                VideoContainer.WEBM -> "vp9"
                VideoContainer.MKV -> null
            }
        }
        videoCodec?.let { parts += "vcodec:$it" }

        val audioCodec = when (o.container) {
            VideoContainer.MP4 -> "m4a"
            VideoContainer.WEBM -> "opus"
            VideoContainer.MKV -> null
        }
        audioCodec?.let { parts += "acodec:$it" }
        return parts.joinToString(",")
    }

    private fun titleFrom(line: String): String? {
        val marker = "Destination:"
        if (!(line.startsWith("[download]") || line.startsWith("[ExtractAudio]"))) return null
        val idx = line.indexOf(marker)
        if (idx < 0) return null
        val name = line.substring(idx + marker.length).trim().substringAfterLast('/')
        val noExt = name.substringBeforeLast('.', name)
        return noExt.replace(formatSuffixRegex, "").ifBlank { null }
    }

    private fun cleanError(e: Throwable?): String {
        val msg = e?.message ?: return "不明なエラー"
        val line = msg.lines().lastOrNull { it.contains("ERROR") } ?: msg.lines().lastOrNull { it.isNotBlank() } ?: msg
        return line.trim().take(200)
    }

    // ---------------------------------------------------------------- saving

    private val skipExtensions = setOf(
        "part", "ytdl", "temp", "tmp", "json", "description", "jpg", "jpeg", "png", "webp", "vtt", "srt", "info",
    )

    private class SaveResult(val files: List<SavedFile>, val where: String, val head: String? = null)

    /**
     * 作業フォルダの成果物を保存先へ移す。
     * 保存先は設定に従う (標準は Download/KyuuriDL、選んだフォルダがあればそちら)。
     * 選んだフォルダに書き込めない場合 (権限の取り消し・フォルダの削除など) は、標準の保存先へ退避する。
     */
    private fun saveFiles(context: Context, root: File): SaveResult {
        val tree = SaveLocation.treeUri(context)
        if (tree != null) {
            saveToTree(context, tree, root)?.let { return SaveResult(it, SaveLocation.label(context)) }
            return SaveResult(
                saveToMediaStore(context, root),
                "指定フォルダに書き込めず ${SaveLocation.DEFAULT_PATH} へ保存",
            )
        }
        return SaveResult(saveToMediaStore(context, root), SaveLocation.DEFAULT_PATH)
    }

    /** 保存対象のファイル。再生リストは番号順 (1, 2, ... 10) に並べる。 */
    private fun outputFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() !in skipExtensions && !it.name.contains(".part") }
            .sortedWith(
                compareBy<File>(
                    { it.parentFile?.path.orEmpty() },
                    { leadingNumber(it.name) ?: Int.MAX_VALUE },
                    { it.name },
                ),
            )
            .toList()

    /** 標準の保存先 Download/KyuuriDL へ移す (MediaStore 経由なので権限不要)。 */
    private fun saveToMediaStore(context: Context, root: File): List<SavedFile> {
        val saved = mutableListOf<SavedFile>()
        outputFiles(root).forEach { file ->
            val rel = file.parentFile?.relativeTo(root)?.path.orEmpty()
            val relativePath = buildString {
                append(SaveLocation.DEFAULT_PATH)
                if (rel.isNotEmpty()) append('/').append(rel)
            }
            putMediaStore(context, file, relativePath)?.let { saved += it }
        }
        return saved
    }

    /** 1 ファイルを MediaStore (Download 以下の relativePath) へ書き込む。失敗したら null。 */
    private fun putMediaStore(context: Context, file: File, relativePath: String): SavedFile? {
        val resolver = context.contentResolver
        val ext = file.extension.lowercase()
        // MediaStore へ登録する MIME は拡張子から導いたもの (食い違うとファイル名を書き換えられるため)
        val mime = mimeFor(ext)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("open failed")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedFile(uri.toString(), file.name, playerMime(ext, mime))
    }

    /** 1 ファイルを、選んだフォルダ内のディレクトリ dir へ書き込む。失敗したら null。 */
    private fun putTreeFile(context: Context, dir: DocumentFile, file: File): SavedFile? {
        val ext = file.extension.lowercase()
        val mime = mimeFor(ext)
        val doc = try { dir.createFile(mime, file.name) } catch (e: Exception) { null } ?: return null
        try {
            val out = context.contentResolver.openOutputStream(doc.uri) ?: throw IOException("open failed")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { doc.delete() }
            return null
        }
        return SavedFile(doc.uri.toString(), doc.name ?: file.name, playerMime(ext, mime))
    }

    private val extraMime = mapOf(
        "avif" to "image/avif", "heic" to "image/heic", "heif" to "image/heif", "m4v" to "video/x-m4v",
        "jpe" to "image/jpeg",
    )

    private fun mimeFor(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: extraMime[ext] ?: "application/octet-stream"

    /**
     * 利用者が選んだフォルダへ移す。フォルダが使えない、または 1 件も書き込めなかった場合は null を返す
     * (呼び出し側が標準の保存先へ退避する)。
     */
    private fun saveToTree(context: Context, treeUri: Uri, root: File): List<SavedFile>? {
        val base = DocumentFile.fromTreeUri(context, treeUri)
        if (base == null || !base.exists() || !base.canWrite()) return null
        val files = outputFiles(root)
        val saved = mutableListOf<SavedFile>()
        val dirs = HashMap<String, DocumentFile?>()

        for (file in files) {
            val rel = file.parentFile?.relativeTo(root)?.path.orEmpty()
            val dir = dirs.getOrPut(rel) { ensureDir(base, rel) } ?: continue
            putTreeFile(context, dir, file)?.let { saved += it }
        }
        return saved.takeIf { it.isNotEmpty() || files.isEmpty() }
    }

    /** 保存先の下に、再生リスト名などのサブフォルダを (無ければ) 作る。 */
    private fun ensureDir(base: DocumentFile, rel: String): DocumentFile? {
        var dir = base
        for (seg in rel.split('/', '\\').filter { it.isNotEmpty() }) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg) ?: return null
        }
        return dir
    }

    /** "3. タイトル.mp3" の先頭の番号。再生リストを 1,2,...,10 の順に並べるために使う。 */
    private fun leadingNumber(name: String): Int? =
        Regex("""^(\d+)\.""").find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 他アプリ (プレーヤー・共有先) へ渡す MIME タイプ。
     * Android の拡張子表は m4a や opus を正しく返さないことがあるので、主要な形式は明示する。
     */
    private fun playerMime(ext: String, fallback: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> fallback
    }
}
