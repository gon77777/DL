package com.aurum.dl

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    // ---- 入力と形式指定 (前回の選択を引き継ぐ)
    var url by mutableStateOf("")
    var mode by mutableStateOf(Prefs.getEnum(app, "mode", Mode.VIDEO))
        private set
    var quality by mutableStateOf(Prefs.getEnum(app, "quality", VideoQuality.BEST))
        private set
    var container by mutableStateOf(Prefs.getEnum(app, "container", VideoContainer.MP4))
        private set
    var codec by mutableStateOf(Prefs.getEnum(app, "codec", VideoCodec.AUTO))
        private set
    var audioFormat by mutableStateOf(Prefs.getEnum(app, "audio_format", AudioFormat.MP3))
        private set
    var audioQuality by mutableStateOf(Prefs.getEnum(app, "audio_quality", AudioQuality.BEST))
        private set
    var playlist by mutableStateOf(Prefs.getBool(app, "playlist", false))
        private set
    var nightly by mutableStateOf(Prefs.getBool(app, "nightly", false))
        private set
    var advancedOpen by mutableStateOf(false)

    // ---- アカウント一括 (gallery-dl)
    var sourceMode by mutableStateOf(Prefs.getEnum(app, "source_mode", SourceMode.MEDIA))
        private set
    var mediaKind by mutableStateOf(Prefs.getEnum(app, "media_kind", MediaKind.BOTH))
        private set
    var galleryAutoUpdate by mutableStateOf(Prefs.galleryAutoUpdate(app))
        private set

    /** ログイン状態の表示を更新するための合図 (Cookie の保存・削除のたびに増やす)。 */
    private var loginTick by mutableIntStateOf(0)

    // ---- 設定
    var maxConcurrent by mutableStateOf(Prefs.maxConcurrent(app))
        private set
    var fastMode by mutableStateOf(Prefs.fastMode(app))
        private set
    var fastConnections by mutableIntStateOf(Prefs.fastConnections(app))
        private set
    var archiveEnabled by mutableStateOf(Prefs.archiveEnabled(app))
        private set
    var archiveDays by mutableStateOf(Prefs.archiveDays(app))
        private set
    var wifiOnly by mutableStateOf(Prefs.wifiOnly(app))
        private set
    var autoUpdate by mutableStateOf(Prefs.autoUpdate(app))
        private set
    var saveLocation by mutableStateOf(SaveLocation.label(app))
        private set
    var saveCustom by mutableStateOf(SaveLocation.isCustom(app))
        private set

    // ---- プレビュー
    var preview by mutableStateOf<PreviewState>(PreviewState.None)
        private set

    val items = DownloadManager.items
    val engine = DownloadManager.engine
    val galleryEngine = DownloadManager.galleryEngine

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val urlRegex = Regex("""https?://\S+""")
    private var previewJob: Job? = null

    init {
        // 前回のキューを読み戻す。待機中だった項目があれば、すぐ続きから再開する
        DownloadManager.restore(app)
        if (DownloadManager.hasRestorable()) {
            DownloadManager.applyNetworkPolicy(app)
            DownloadManager.startService(app)
        }
        viewModelScope.launch {
            runCatching { DownloadManager.ensureInit(app) }
            // 起動時の自動更新。Wi-Fi限定の設定中にモバイル回線なら、通信量を使わないよう見送る
            if (autoUpdate && DownloadManager.networkAllowed(app)) {
                DownloadManager.updateEngine(app, nightly)
            }
            // gallery-dl も同じ設定の考え方で、起動時に自動更新する (X などの仕様変更に追従するため)
            if (galleryAutoUpdate && DownloadManager.networkAllowed(app)) {
                DownloadManager.updateGalleryEngine(app)
            }
        }
    }

    // ------------------------------------------------------------- selections

    fun selectMode(v: Mode) { mode = v; Prefs.putEnum(app, "mode", v) }
    fun selectQuality(v: VideoQuality) { quality = v; Prefs.putEnum(app, "quality", v) }
    fun selectContainer(v: VideoContainer) { container = v; Prefs.putEnum(app, "container", v) }
    fun selectCodec(v: VideoCodec) { codec = v; Prefs.putEnum(app, "codec", v) }
    fun selectAudioFormat(v: AudioFormat) { audioFormat = v; Prefs.putEnum(app, "audio_format", v) }
    fun selectAudioQuality(v: AudioQuality) { audioQuality = v; Prefs.putEnum(app, "audio_quality", v) }
    fun selectPlaylist(v: Boolean) { playlist = v; Prefs.putBool(app, "playlist", v) }
    fun selectNightly(v: Boolean) { nightly = v; Prefs.putBool(app, "nightly", v) }
    fun selectConcurrency(v: Int) { maxConcurrent = v; Prefs.setMaxConcurrent(app, v) }
    fun selectSourceMode(v: SourceMode) {
        sourceMode = v
        Prefs.putEnum(app, "source_mode", v)
        if (v == SourceMode.ACCOUNT) closePreview()
    }
    fun selectMediaKind(v: MediaKind) { mediaKind = v; Prefs.putEnum(app, "media_kind", v) }
    fun selectGalleryAutoUpdate(v: Boolean) {
        galleryAutoUpdate = v
        Prefs.putBool(app, "gallery_auto_update", v)
    }
    /** フォルダ選択の結果を受け取る。以降のダウンロードから、そのフォルダへ保存する。 */
    fun setSaveFolder(uri: Uri?) {
        if (uri == null) return
        val doc = DocumentFile.fromTreeUri(app, uri)
        if (doc == null || !doc.canWrite()) {
            _toast.tryEmit("このフォルダには書き込めません。別のフォルダを選んでください")
            return
        }
        // アプリを閉じても、再起動後も書き込めるように権限を保持する
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            _toast.tryEmit("このフォルダの権限を保持できませんでした。別のフォルダを選んでください")
            return
        }
        releaseSaveFolderPermission(except = uri.toString())
        Prefs.setSaveTreeUri(app, uri.toString())
        refreshSaveLocation()
        _toast.tryEmit("保存先を変更しました: $saveLocation")
    }

    fun resetSaveFolder() {
        releaseSaveFolderPermission(except = null)
        Prefs.setSaveTreeUri(app, null)
        refreshSaveLocation()
        _toast.tryEmit("保存先を標準 (${SaveLocation.DEFAULT_PATH}) に戻しました")
    }

    private fun refreshSaveLocation() {
        saveLocation = SaveLocation.label(app)
        saveCustom = SaveLocation.isCustom(app)
    }

    /** 使わなくなった以前のフォルダの権限を手放す。 */
    private fun releaseSaveFolderPermission(except: String?) {
        val old = Prefs.saveTreeUri(app) ?: return
        if (old == except) return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun selectAutoUpdate(v: Boolean) {
        autoUpdate = v
        Prefs.putBool(app, "auto_update", v)
    }

    fun selectWifiOnly(v: Boolean) {
        wifiOnly = v
        Prefs.putBool(app, "wifi_only", v)
        // 切り替えをすぐ反映する (OFF にすれば Wi-Fi 待ちの項目が再開、ON にしてモバイルなら一時停止)
        DownloadManager.applyNetworkPolicy(app)
    }

    fun selectFastMode(v: Boolean) {
        fastMode = v
        Prefs.putBool(app, "fast_mode", v)
    }

    fun selectFastConnections(v: Int) {
        fastConnections = v
        Prefs.setFastConnections(app, v)
    }

    fun selectArchiveEnabled(v: Boolean) {
        archiveEnabled = v
        Prefs.putBool(app, "archive_enabled", v)
    }

    fun selectArchiveDays(v: Int) {
        archiveDays = v
        Prefs.setArchiveDays(app, v)
    }

    /** 取得済みの記録をすべて消す。次の取得では全件が対象になる。 */
    fun clearArchive() {
        viewModelScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Archive.clear(app) }
            _toast.tryEmit(if (n > 0) "取得済みの記録を消去しました" else "消去する記録はありません")
        }
    }

    fun currentOptions() = Options(mode, quality, container, codec, audioFormat, audioQuality, playlist)

    fun addText(text: String) {
        url = if (url.isBlank()) text.trim() else url.trimEnd() + "\n" + text.trim()
    }

    private fun urlsInInput(): List<String> =
        urlRegex.findAll(url).map { it.value }.distinct().toList()

    // --------------------------------------------------------------- download

    fun submit() {
        if (sourceMode == SourceMode.ACCOUNT) {
            submitAccounts()
            return
        }
        val urls = urlsInInput()
        if (urls.isEmpty()) {
            _toast.tryEmit("有効なURLを入力してください")
            return
        }
        DownloadManager.enqueue(app, urls, currentOptions())
        url = ""
        _toast.tryEmit(
            if (DownloadManager.networkAllowed(app)) "${urls.size} 件をキューに追加しました"
            else "${urls.size} 件を追加しました。Wi-Fiに接続するまで待機します",
        )
    }

    // ---------------------------------------------------------------- account

    private fun inputTokens(): List<String> = url.split(Regex("\\s+")).filter { it.isNotBlank() }

    /** 入力欄から読み取れたアカウント (ログイン対象の表示に使う)。 */
    fun accountTargets(): List<AccountUrl.Target> =
        inputTokens().mapNotNull { AccountUrl.parse(it) }.distinctBy { it.url }

    fun isLoggedIn(siteHost: String): Boolean {
        val tick = loginTick
        return tick >= 0 && CookieStore.loggedIn(app, siteHost)
    }

    /** ログイン画面から戻ったときに呼ぶ。 */
    fun onLoginResult(siteHost: String?) {
        loginTick++
        if (siteHost != null && CookieStore.loggedIn(app, siteHost)) _toast.tryEmit("ログインしました")
    }

    /** ログイン画面を使わず、貼り付けた Cookie / トークンを保存する。 */
    fun saveManualLogin(siteHost: String, text: String) {
        val ok = runCatching { CookieStore.saveManual(app, siteHost, text) }.getOrDefault(false)
        loginTick++
        _toast.tryEmit(if (ok) "ログイン情報を保存しました" else "読み取れませんでした。内容と形式を確認してください")
    }

    fun logout(siteHost: String) {
        CookieStore.clear(app, siteHost)
        // アプリ内ブラウザに残ったログイン情報も消す (次のログインで別のアカウントを使えるように)
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        loginTick++
        _toast.tryEmit("ログアウトしました")
    }

    private fun submitAccounts() {
        val tokens = inputTokens()
        if (tokens.isEmpty()) {
            _toast.tryEmit("アカウントのURLを入力してください")
            return
        }
        val targets = ArrayList<AccountUrl.Target>()
        for (token in tokens) {
            val t = AccountUrl.parse(token)
            if (t == null) {
                _toast.tryEmit("アカウントのURLとして読み取れません: ${token.take(60)}")
                return
            }
            if (targets.none { it.url == t.url }) targets += t
        }
        // X は、ログインしていないとアカウントの投稿を取得できない
        targets.firstOrNull { CookieStore.requiresLogin(it.siteHost) && !CookieStore.loggedIn(app, it.siteHost) }?.let {
            _toast.tryEmit("先に${if (it.isTwitter) "X" else "Pixiv"}へログインしてください (下の「ログイン」から)")
            return
        }
        val fresh = targets.filterNot { DownloadManager.hasUnfinishedAccount(it.url) }
        if (fresh.isEmpty()) {
            _toast.tryEmit("このアカウントはすでにキューにあります")
            return
        }
        DownloadManager.enqueueAccounts(app, fresh.map { AccountJob(it, mediaKind) })
        url = ""
        val skipped = targets.size - fresh.size
        val base = "${fresh.size} 件のアカウントをキューに追加しました" +
            if (skipped > 0) " (キューにある $skipped 件は除く)" else ""
        _toast.tryEmit(
            if (DownloadManager.networkAllowed(app)) base
            else "$base。Wi-Fiに接続するまで待機します",
        )
    }

    // ---------------------------------------------------------------- preview

    fun startPreview() {
        if (sourceMode == SourceMode.ACCOUNT) return
        val target = urlsInInput().firstOrNull()
        if (target == null) {
            _toast.tryEmit("有効なURLを入力してください")
            return
        }
        previewJob?.cancel()
        DownloadManager.cancelPreview()
        preview = PreviewState.Loading(target)
        previewJob = viewModelScope.launch {
            preview = try {
                PreviewState.Ready(DownloadManager.fetchPreview(app, target, playlist))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PreviewState.Error(target, friendlyError(e))
            }
        }
    }

    fun closePreview() {
        previewJob?.cancel()
        DownloadManager.cancelPreview()
        preview = PreviewState.None
    }

    fun toggleEntry(index: Int) {
        val ready = preview as? PreviewState.Ready ?: return
        val entries = ready.data.entries.map { if (it.index == index) it.copy(selected = !it.selected) else it }
        preview = PreviewState.Ready(ready.data.copy(entries = entries))
    }

    fun selectAllEntries(selected: Boolean) {
        val ready = preview as? PreviewState.Ready ?: return
        val entries = ready.data.entries.map { it.copy(selected = selected) }
        preview = PreviewState.Ready(ready.data.copy(entries = entries))
    }

    /** プレビューした内容を、いまの形式指定でキューに追加する。 */
    fun downloadPreview() {
        val data = (preview as? PreviewState.Ready)?.data ?: return
        var playlistItems: String? = null
        if (data.isPlaylist) {
            val selected = data.entries.filter { it.selected }
            if (selected.isEmpty()) {
                _toast.tryEmit("保存する項目を選んでください")
                return
            }
            if (selected.size != data.entries.size) playlistItems = toRanges(selected.map { it.index })
        }
        val options = currentOptions().copy(playlist = data.isPlaylist)
        DownloadManager.enqueue(app, listOf(data.url), options, playlistItems, data.title)
        url = url.replace(data.url, "").trim()
        closePreview()
        _toast.tryEmit(
            if (DownloadManager.networkAllowed(app)) "キューに追加しました"
            else "追加しました。Wi-Fiに接続するまで待機します",
        )
    }

    /** [1,2,3,5] -> "1:3,5" */
    private fun toRanges(indices: List<Int>): String {
        val sorted = indices.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in sorted.drop(1)) {
            if (i == prev + 1) {
                prev = i
            } else {
                parts += if (start == prev) "$start" else "$start:$prev"
                start = i
                prev = i
            }
        }
        parts += if (start == prev) "$start" else "$start:$prev"
        return parts.joinToString(",")
    }

    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        val line = msg.lines().lastOrNull { it.contains("ERROR") } ?: msg.lines().lastOrNull { it.isNotBlank() }
        return (line ?: "情報を取得できませんでした").trim().take(200)
    }

    // ----------------------------------------------------------------- engine

    fun updateEngine() {
        viewModelScope.launch { DownloadManager.updateEngine(app, nightly) }
    }

    fun updateGalleryEngine() {
        viewModelScope.launch { DownloadManager.updateGalleryEngine(app) }
    }

    // ------------------------------------------------------------------ queue

    fun cancel(id: String) = DownloadManager.cancel(id)
    fun pause(id: String) = DownloadManager.pause(id)
    fun resume(id: String) {
        if (!DownloadManager.resume(app, id)) _toast.tryEmit(WIFI_BLOCKED)
    }

    fun retry(id: String) {
        DownloadManager.retry(app, id)
        if (!DownloadManager.networkAllowed(app)) _toast.tryEmit(WIFI_BLOCKED)
    }

    fun remove(id: String) = DownloadManager.remove(id)
    fun pauseAll() = DownloadManager.pauseAll()
    fun resumeAll() {
        if (!DownloadManager.resumeAll(app)) _toast.tryEmit(WIFI_BLOCKED)
    }
    fun clearFinished() = DownloadManager.clearFinished()

    // ------------------------------------------------------------ play / share

    fun play(context: Context, file: SavedFile) {
        when (FileActions.open(context, file)) {
            FileActions.Result.OK -> Unit
            FileActions.Result.MISSING -> _toast.tryEmit(MISSING_FILE)
            FileActions.Result.NO_APP -> _toast.tryEmit("このファイルを開けるアプリが見つかりません")
            FileActions.Result.FAILED -> _toast.tryEmit("ファイルを開けませんでした")
        }
    }

    fun share(context: Context, files: List<SavedFile>) {
        when (FileActions.share(context, files)) {
            FileActions.Result.OK -> Unit
            FileActions.Result.MISSING -> _toast.tryEmit(MISSING_FILE)
            FileActions.Result.NO_APP -> _toast.tryEmit("共有できるアプリが見つかりません")
            FileActions.Result.FAILED -> _toast.tryEmit("共有を開始できませんでした")
        }
    }

    private companion object {
        const val WIFI_BLOCKED = "Wi-Fiに接続すると再開します (設定で「Wi-Fi接続時のみ実行」をOFFにすると今すぐ実行できます)"
        const val MISSING_FILE = "ファイルが見つかりません。移動または削除された可能性があります"
    }
}
