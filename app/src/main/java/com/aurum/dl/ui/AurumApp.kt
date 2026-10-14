package com.aurum.dl.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.dl.BatteryOptimization
import com.aurum.dl.AudioFormat
import com.aurum.dl.AudioQuality
import com.aurum.dl.DownloadItem
import com.aurum.dl.EngineState
import com.aurum.dl.LoginActivity
import com.aurum.dl.MainViewModel
import com.aurum.dl.MediaKind
import com.aurum.dl.Mode
import com.aurum.dl.PreviewData
import com.aurum.dl.PreviewState
import com.aurum.dl.SavedFile
import com.aurum.dl.SourceMode
import com.aurum.dl.Status
import com.aurum.dl.VideoCodec
import com.aurum.dl.VideoContainer
import com.aurum.dl.VideoQuality

@Composable
fun AurumApp(vm: MainViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val galleryEngine by vm.galleryEngine.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        vm.toast.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Aurum.Bg,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Aurum.CardHigh,
                    contentColor = Aurum.SageDeep,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 20.dp,
                bottom = pad.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(settingsOpen) { settingsOpen = !settingsOpen } }
            item {
                AnimatedVisibility(visible = settingsOpen) {
                    SettingsCard(vm)
                }
            }
            item { InputCard(vm) }
            if (vm.preview != PreviewState.None) {
                item { PreviewCard(vm) }
            }
            item {
                QueueHeader(
                    count = items.size,
                    hasActive = items.any { it.isActive },
                    hasPaused = items.any { it.status == Status.PAUSED },
                    hasFinished = items.any { it.isFinished },
                    onPauseAll = vm::pauseAll,
                    onResumeAll = vm::resumeAll,
                    onClear = vm::clearFinished,
                )
            }
            if (items.isEmpty()) {
                item { EmptyState() }
            } else {
                items(items, key = { it.id }) { item ->
                    DownloadCard(
                        item,
                        onPause = { vm.pause(item.id) },
                        onResume = { vm.resume(item.id) },
                        onRetry = { vm.retry(item.id) },
                        onCancel = { vm.cancel(item.id) },
                        onRemove = { vm.remove(item.id) },
                        onPlay = { file -> vm.play(context, file) },
                        onShare = { files -> vm.share(context, files) },
                    )
                }
            }
            // エンジン (更新用) は一番下にまとめる
            item { GalleryEngineCard(galleryEngine, onUpdate = vm::updateGalleryEngine) }
            item { EngineCard(engine, vm.nightly, onChannel = vm::selectNightly, onUpdate = vm::updateEngine) }
        }
    }
}

// ------------------------------------------------------------------ sections

@Composable
private fun Header(settingsOpen: Boolean, onToggleSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        LeafBadge(Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "きゅうりDL",
                color = Aurum.SageDeep,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text("media downloader", color = Aurum.Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        // 設定の開閉ボタン
        val shape = CircleShape
        Box(
            Modifier
                .size(36.dp)
                .clip(shape)
                .background(if (settingsOpen) Aurum.Sage else Aurum.CardHigh.copy(alpha = 0.8f))
                .clickable(onClick = onToggleSettings),
            contentAlignment = Alignment.Center,
        ) {
            GearIcon(
                color = if (settingsOpen) Aurum.OnSage else Aurum.SageDeep,
                hole = if (settingsOpen) Aurum.Sage else Aurum.CardHigh,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EngineCard(
    engine: EngineState,
    nightly: Boolean,
    onChannel: (Boolean) -> Unit,
    onUpdate: () -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("YT-DLP ENGINE")
                Spacer(Modifier.height(5.dp))
                Text(
                    engine.version,
                    color = Aurum.SageDeep,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(engine.note, color = Aurum.Muted, fontSize = 10.5.sp, maxLines = 5)
            }
            Spacer(Modifier.width(12.dp))
            SageButton(
                text = if (engine.busy) "確認中" else "最新版に更新",
                enabled = engine.ready && !engine.busy,
                onClick = onUpdate,
                filled = false,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SageChip("STABLE", selected = !nightly, modifier = Modifier.weight(1f), enabled = !engine.busy) { onChannel(false) }
            SageChip("NIGHTLY", selected = nightly, modifier = Modifier.weight(1f), enabled = !engine.busy) { onChannel(true) }
        }
        Spacer(Modifier.height(10.dp))
        ChannelNote("STABLE", "動作確認を終えた正式版です。ふだんはこちらがおすすめ。更新は数週間に一度ほどです。", !nightly)
        Spacer(Modifier.height(6.dp))
        ChannelNote(
            "NIGHTLY",
            "毎日自動で作られる開発版です。サイトの仕様変更への修正がいち早く入ります。" +
                "STABLEで動画を取得できないときに試してください。ただし、不具合が混ざることもあります。",
            nightly,
        )
    }
}

/** STABLE / NIGHTLY の違いの説明。選んでいる側を濃く表示する。 */
@Composable
private fun ChannelNote(name: String, body: String, active: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            name,
            color = if (active) Aurum.SageDeep else Aurum.Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(body, color = if (active) Aurum.Text else Aurum.Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun GalleryEngineCard(engine: EngineState, onUpdate: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("GALLERY-DL ENGINE")
                Spacer(Modifier.height(6.dp))
                Text(
                    engine.version,
                    color = Aurum.SageDeep,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(engine.note, color = Aurum.Muted, fontSize = 11.sp, maxLines = 5)
            }
            Spacer(Modifier.width(12.dp))
            SageButton(
                text = if (engine.busy) "確認中" else "最新版に更新",
                enabled = !engine.busy,
                onClick = onUpdate,
                filled = false,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "アカウント一括ダウンロードに使います。Xなどの仕様変更で取得できなくなったときは、最新版へ更新してください。",
            color = Aurum.Muted,
            fontSize = 10.5.sp,
        )
    }
}

@Composable
private fun SettingsCard(vm: MainViewModel) {
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.setSaveFolder(uri)
    }
    val ctx = LocalContext.current
    var batteryOk by remember { mutableStateOf(BatteryOptimization.isIgnoring(ctx)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryOk = BatteryOptimization.isIgnoring(ctx)
    }
    SoftCard {
        SectionLabel("SETTINGS")
        Spacer(Modifier.height(10.dp))
        Text("保存先", color = Aurum.Text, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            vm.saveLocation + if (vm.saveCustom) "" else "  (標準)",
            color = Aurum.SageDeep,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "変更すると、これ以降のダウンロードから反映されます。" +
                "Androidの制限で、端末の最上位と「ダウンロード」フォルダ自体は選べません。専用のフォルダを作って選んでください。",
            color = Aurum.Muted,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            TextAction("フォルダを選ぶ") { folderLauncher.launch(null) }
            if (vm.saveCustom) TextAction("標準に戻す", color = Aurum.Muted) { vm.resetSaveFolder() }
        }
        Spacer(Modifier.height(13.dp))
        Text("バックグラウンドでの動作", color = Aurum.Text, fontSize = 13.sp)
        Text(
            if (batteryOk) "電池の最適化の対象外です。画面を消してもダウンロードを続けます"
            else "電池の最適化の対象です。画面を消すと通信が止められ、ダウンロードが途中で止まることがあります",
            color = if (batteryOk) Aurum.Muted else Aurum.SageDeep,
            fontSize = 10.5.sp,
        )
        if (!batteryOk) {
            Spacer(Modifier.height(4.dp))
            TextAction("制限しない設定にする") {
                val intent = BatteryOptimization.requestIntent(ctx)
                try {
                    batteryLauncher.launch(intent)
                } catch (e: Exception) {
                    runCatching { batteryLauncher.launch(BatteryOptimization.settingsIntent()) }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Text("同時ダウンロード数", color = Aurum.Text, fontSize = 13.sp)
        Text("回線や端末の負荷に合わせて調整できます", color = Aurum.Muted, fontSize = 10.5.sp)
        Spacer(Modifier.height(10.dp))
        ChipGrid(
            options = listOf(1, 2, 3, 4, 5),
            columns = 5,
            isSelected = { it == vm.maxConcurrent },
            label = { it.toString() },
            onSelect = vm::selectConcurrency,
        )
        Spacer(Modifier.height(13.dp))
        FastModeSetting(vm)
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi接続時のみ実行", color = Aurum.Text, fontSize = 13.sp)
                Text(
                    "モバイル回線では自動で一時停止し、Wi-Fiに接続すると再開します",
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }
            SageSwitch(vm.wifiOnly, vm::selectWifiOnly)
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("yt-dlpの自動更新", color = Aurum.Text, fontSize = 13.sp)
                Text(
                    "アプリ起動時に、選択中のチャンネル (STABLE / NIGHTLY) の最新版へ更新します",
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }
            SageSwitch(vm.autoUpdate, vm::selectAutoUpdate)
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("gallery-dlの自動更新", color = Aurum.Text, fontSize = 13.sp)
                Text(
                    "アプリ起動時に、アカウント一括ダウンロード用の gallery-dl を最新版へ更新します",
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }
            SageSwitch(vm.galleryAutoUpdate, vm::selectGalleryAutoUpdate)
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("取得済みの分をスキップ", color = Aurum.Text, fontSize = 13.sp)
                Text(
                    "再生リストとアカウント一括を取り直すとき、取得済みの分を飛ばして新着だけ保存します",
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }
            SageSwitch(vm.archiveEnabled, vm::selectArchiveEnabled)
        }
        if (vm.archiveEnabled) {
            Spacer(Modifier.height(10.dp))
            Text("記録を残す日数", color = Aurum.Text, fontSize = 12.sp)
            Text(
                if (vm.archiveDays == 0) "記録は自動では消えません。不要になったら下の「記録をすべて消去」で消せます"
                else "この日数のあいだ使われなかった記録は、自動で消えます (使うたびに延びます)",
                color = Aurum.Muted,
                fontSize = 10.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            ChipGrid(
                options = listOf(7, 30, 90, 0),
                columns = 4,
                isSelected = { it == vm.archiveDays },
                label = { if (it == 0) "無期限" else "${it}日" },
                onSelect = vm::selectArchiveDays,
            )
            Spacer(Modifier.height(2.dp))
            TextAction("記録をすべて消去", color = Aurum.Muted) { vm.clearArchive() }
        }
        Spacer(Modifier.height(14.dp))
        SoftDivider()
        Spacer(Modifier.height(12.dp))
        SupportedSites()
    }
}

/** 高速モード (分割ダウンロード) の設定。接続数をスライダーで選ぶ。 */
@Composable
private fun FastModeSetting(vm: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("高速モード (分割ダウンロード)", color = Aurum.Text, fontSize = 13.sp)
            Text(
                "1つの動画を複数の接続に分けて同時に取得します",
                color = Aurum.Muted,
                fontSize = 10.5.sp,
            )
        }
        SageSwitch(vm.fastMode, vm::selectFastMode)
    }
    if (vm.fastMode) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("同時接続数", color = Aurum.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${vm.fastConnections} 本", color = Aurum.SageDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = vm.fastConnections.toFloat(),
            onValueChange = { vm.selectFastConnections(Math.round(it)) },
            valueRange = 4f..16f,
            steps = 11,
            colors = SliderDefaults.colors(
                thumbColor = Aurum.Sage,
                activeTrackColor = Aurum.Sage,
                inactiveTrackColor = Aurum.Line,
                activeTickColor = Aurum.OnSage,
                inactiveTickColor = Aurum.Muted.copy(alpha = 0.5f),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("4 (控えめ)", color = Aurum.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("16 (最大)", color = Aurum.Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "まずは 8 本をおすすめします。",
            color = Aurum.SageDeep,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "・回線そのものの速度は超えられません。サイトが「1接続あたりの速度」を制限している場合に効果があります。\n" +
                "・同じサイトへ短時間に多くの接続をすると、サイトから制限されることがあります。\n" +
                "・制限されると、速度が極端に落ちる、途中で止まる、「HTTP Error 403 / 429」などで失敗する、" +
                "しばらくそのサイトからダウンロードできなくなる、といったことが起こります。" +
                "同じ回線 (IPアドレス) を使う他の端末にも影響する場合があります。" +
                "その場合は接続数を下げるか、高速モードをOFFにして、時間をおいてください。\n" +
                "・CPU・電池・通信量の使用も増えます。失敗してやり直すと、通信量はさらに増えます。\n" +
                "・分割できない配信形式では、効果がないことがあります。",
            color = Aurum.Muted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

/** ダウンロードできる主なサイトの一覧 (設定内)。 */
@Composable
private fun SupportedSites() {
    SectionLabel("SUPPORTED SITES")
    Spacer(Modifier.height(8.dp))
    SiteGroup(
        "動画・音声 (yt-dlp)",
        "YouTube / ニコニコ動画 / X (Twitter) / TikTok / Instagram / Facebook / Bilibili / Vimeo / " +
            "Twitch / Dailymotion / SoundCloud / Reddit / TVer など、1,000以上のサイトに対応します。",
    )
    Spacer(Modifier.height(8.dp))
    SiteGroup(
        "画像・アカウント一括 (gallery-dl)",
        "X (Twitter) / Instagram / Pixiv / Bluesky / Reddit / Tumblr / Pinterest / DeviantArt / Flickr / " +
            "Imgur / Misskey など、300以上のサイトに対応します。",
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "対応サイトの一覧は、各エンジンの最新版へ更新すると増えたり直ったりします。" +
            "サイトによってはログインが必要です。DRM (著作権保護) のかかった配信 (Netflix など) は保存できません。" +
            "ダウンロードは、各サイトの利用規約と著作権法に従って、私的利用の範囲でお使いください。",
        color = Aurum.Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    )
}

@Composable
private fun SiteGroup(title: String, body: String) {
    Text(title, color = Aurum.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(2.dp))
    Text(body, color = Aurum.Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
}

@Composable
private fun InputCard(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val accountMode = vm.sourceMode == SourceMode.ACCOUNT
    SoftCard {
        SectionLabel("TYPE")
        Spacer(Modifier.height(10.dp))
        ChipGrid(
            options = SourceMode.entries.toList(),
            columns = 2,
            isSelected = { it == vm.sourceMode },
            label = { it.label },
            onSelect = vm::selectSourceMode,
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel("SOURCE")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    if (accountMode) "アカウントのURL (例: https://x.com/ユーザー名)" else "URLを入力 (複数は改行で区切る)",
                    color = Aurum.Muted,
                    fontSize = 13.sp,
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Aurum.Text,
                unfocusedTextColor = Aurum.Text,
                focusedBorderColor = Aurum.Sage,
                unfocusedBorderColor = Aurum.Line,
                cursorColor = Aurum.Sage,
                focusedContainerColor = Aurum.Card,
                unfocusedContainerColor = Aurum.Card,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextAction("貼り付け") {
                val pasted = clipboard.getText()?.text
                if (!pasted.isNullOrBlank()) vm.addText(pasted)
            }
            TextAction("消去") { vm.url = "" }
        }

        if (accountMode) {
            AccountSection(vm)
        } else {
            Spacer(Modifier.height(14.dp))
            SectionLabel("FORMAT")
            Spacer(Modifier.height(10.dp))
            ChipGrid(
                options = Mode.entries.toList(),
                columns = 2,
                isSelected = { it == vm.mode },
                label = { it.label },
                onSelect = vm::selectMode,
            )

            Spacer(Modifier.height(14.dp))
            if (vm.mode == Mode.VIDEO) {
                SectionLabel("QUALITY")
                Spacer(Modifier.height(10.dp))
                ChipGrid(
                    options = VideoQuality.entries.toList(),
                    columns = 4,
                    isSelected = { it == vm.quality },
                    label = { it.label },
                    onSelect = vm::selectQuality,
                )
            } else {
                SectionLabel("AUDIO CODEC")
                Spacer(Modifier.height(10.dp))
                ChipGrid(
                    options = AudioFormat.entries.toList(),
                    columns = 4,
                    isSelected = { it == vm.audioFormat },
                    label = { it.label },
                    onSelect = vm::selectAudioFormat,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "タイトル・アーティスト等のタグと、動画のサムネイル (アルバムアート) を埋め込んで保存します。",
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }

            Spacer(Modifier.height(12.dp))
            TextAction(if (vm.advancedOpen) "詳細設定を隠す" else "詳細設定を表示") { vm.advancedOpen = !vm.advancedOpen }

            if (vm.advancedOpen) {
                Spacer(Modifier.height(10.dp))
                if (vm.mode == Mode.VIDEO) {
                    SectionLabel("CONTAINER")
                    Spacer(Modifier.height(10.dp))
                    ChipGrid(
                        options = VideoContainer.entries.toList(),
                        columns = 3,
                        isSelected = { it == vm.container },
                        label = { it.label },
                        onSelect = vm::selectContainer,
                    )
                    Spacer(Modifier.height(11.dp))
                    SectionLabel("VIDEO CODEC")
                    Spacer(Modifier.height(10.dp))
                    ChipGrid(
                        options = VideoCodec.entries.toList(),
                        columns = 4,
                        isSelected = { it == vm.codec },
                        label = { it.label },
                        onSelect = vm::selectCodec,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "自動の場合、MP4はH.264、WEBMはVP9を優先します。MKVは最も高画質な組み合わせを選びます。" +
                            "指定した画質が無い動画では、近い画質が自動で選ばれます。",
                        color = Aurum.Muted,
                        fontSize = 10.5.sp,
                    )
                } else {
                    SectionLabel("AUDIO BITRATE")
                    Spacer(Modifier.height(10.dp))
                    ChipGrid(
                        options = AudioQuality.entries.toList(),
                        columns = 3,
                        isSelected = { it == vm.audioQuality },
                        label = { it.label },
                        onSelect = vm::selectAudioQuality,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "FLACは可逆圧縮のため、ビットレートの指定は無視されます。",
                        color = Aurum.Muted,
                        fontSize = 10.5.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("再生リストをまとめて保存", color = Aurum.Text, fontSize = 13.sp)
                    Text("リスト全体を番号付きフォルダへ保存します", color = Aurum.Muted, fontSize = 10.5.sp)
                }
                SageSwitch(vm.playlist, vm::selectPlaylist)
            }

        }

        Spacer(Modifier.height(15.dp))
        if (accountMode) {
            SageButton(
                "アカウントを一括ダウンロード",
                enabled = true,
                onClick = vm::submit,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SageButton(
                    "プレビュー",
                    enabled = true,
                    onClick = vm::startPreview,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
                SageButton(
                    "ダウンロード",
                    enabled = true,
                    onClick = vm::submit,
                    filled = true,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

private data class LoginTarget(val host: String, val url: String)

/** アカウント一括ダウンロードの設定: 取得するメディアの種類と、ログイン。 */
@Composable
private fun AccountSection(vm: MainViewModel) {
    val context = LocalContext.current
    var loginHost by remember { mutableStateOf<String?>(null) }
    var manualHost by remember { mutableStateOf<String?>(null) }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onLoginResult(loginHost)
    }

    Spacer(Modifier.height(14.dp))
    SectionLabel("MEDIA")
    Spacer(Modifier.height(10.dp))
    ChipGrid(
        options = MediaKind.entries.toList(),
        columns = 3,
        isSelected = { it == vm.mediaKind },
        label = { it.label },
        onSelect = vm::selectMediaKind,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "アカウントの投稿から画像・動画をまとめて取得します。" +
            "保存先の「アカウント名」フォルダの中に、画像は images、動画は videos と分けて保存します。",
        color = Aurum.Muted,
        fontSize = 10.5.sp,
    )

    Spacer(Modifier.height(14.dp))
    SectionLabel("LOGIN")
    Spacer(Modifier.height(8.dp))
    val targets = vm.accountTargets()
    val hosts = if (targets.isEmpty()) {
        listOf(LoginTarget("x.com", "https://x.com/i/flow/login"))
    } else {
        targets.distinctBy { it.siteHost }.map { LoginTarget(it.siteHost, it.loginUrl) }
    }
    hosts.forEach { h ->
        val loggedIn = vm.isLoggedIn(h.host)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(h.host, color = Aurum.Text, fontSize = 13.sp)
                Text(
                    if (loggedIn) "ログイン済み" else "未ログイン",
                    color = if (loggedIn) Aurum.Sage else Aurum.Muted,
                    fontSize = 10.5.sp,
                )
            }
            TextAction(if (loggedIn) "再ログイン" else "ログイン") {
                loginHost = h.host
                loginLauncher.launch(
                    Intent(context, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_URL, h.url)
                        .putExtra(LoginActivity.EXTRA_SITE, h.host),
                )
            }
            Spacer(Modifier.width(12.dp))
            TextAction("手動で入力", color = Aurum.Muted) { manualHost = h.host }
            if (loggedIn) {
                Spacer(Modifier.width(12.dp))
                TextAction("ログアウト", color = Aurum.Muted) { vm.logout(h.host) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "アプリ内のブラウザでログインします。パスワードはアプリでは扱わず、ログイン情報だけをこの端末内に保存します。" +
            "X と Pixiv は、ログインしないと取得できません (動画サイトの単体ダウンロードとは別の仕組みのため)。" +
            "ログインしたアカウントで見られる範囲が対象で、X の仕様により古い投稿まで取得できない場合があります。" +
            "ログイン画面が動かないときは「手動で入力」から、Cookie (X) やトークン (Pixiv) を貼り付けられます。" +
            "他のサイトは、必要なときだけログインしてください。",
        color = Aurum.Muted,
        fontSize = 10.5.sp,
    )

    manualHost?.let { host ->
        ManualLoginDialog(
            host = host,
            onSave = { text ->
                vm.saveManualLogin(host, text)
                manualHost = null
            },
            onDismiss = { manualHost = null },
        )
    }
}

/** ログイン情報の手動入力。ログイン画面が使えないときの代わり。 */
@Composable
private fun ManualLoginDialog(host: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var ct0 by remember { mutableStateOf("") }
    val pixiv = host == "pixiv.net" || host == "www.pixiv.net"
    val twitter = host == "x.com"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Aurum.Card,
        shape = RoundedCornerShape(20.dp),
        title = { SectionLabel(if (pixiv) "PIXIV  TOKEN" else "$host  COOKIE") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        pixiv ->
                            "通常は、この画面を使わず「ログイン」ボタンだけで自動的に登録されます。" +
                                "どうしてもログインできないときだけ、リフレッシュトークンの値を貼り付けてください。"
                        twitter ->
                            "「ログイン」がうまくいかないときの方法です。パソコンのブラウザ (Chrome など) で x.com にログインしたあと、" +
                                "次の手順で2つの値をコピーして貼り付けます。\n" +
                                "1. x.com を開いた状態で F12 キーを押す\n" +
                                "2. 上のタブから「アプリケーション (Application)」を選ぶ (「コンソール」ではありません)\n" +
                                "3. 左の「Cookie」→「https://x.com」を選ぶ\n" +
                                "4. 一覧の auth_token と ct0 の「値 (Value)」をダブルクリックしてコピーし、下の欄へ貼り付ける"
                        else ->
                            "「名前=値; 名前=値」の形の Cookie、または cookies.txt の中身を貼り付けてください。"
                    },
                    color = Aurum.Muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (twitter) Text("auth_token", color = Aurum.Text, fontSize = 11.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (twitter) 1 else 3,
                    maxLines = if (twitter) 2 else 6,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Aurum.Text,
                        unfocusedTextColor = Aurum.Text,
                        focusedBorderColor = Aurum.Sage,
                        unfocusedBorderColor = Aurum.Line,
                        cursorColor = Aurum.Sage,
                        focusedContainerColor = Aurum.Card,
                        unfocusedContainerColor = Aurum.Card,
                    ),
                )
                if (twitter) {
                    Spacer(Modifier.height(8.dp))
                    Text("ct0", color = Aurum.Text, fontSize = 11.sp)
                    OutlinedTextField(
                        value = ct0,
                        onValueChange = { ct0 = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 2,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Aurum.Text,
                            unfocusedTextColor = Aurum.Text,
                            focusedBorderColor = Aurum.Sage,
                            unfocusedBorderColor = Aurum.Line,
                            cursorColor = Aurum.Sage,
                            focusedContainerColor = Aurum.Card,
                            unfocusedContainerColor = Aurum.Card,
                        ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "貼り付けた内容は、この端末の中にだけ保存されます。他の人には見せないでください (ログイン情報と同じ扱いです)。",
                    color = Aurum.Muted,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextAction("保存") {
                onSave(if (twitter) "auth_token=${text.trim()}; ct0=${ct0.trim()}" else text)
            }
        },
        dismissButton = { TextAction("キャンセル", color = Aurum.Muted, onClick = onDismiss) },
    )
}

@Composable
private fun PreviewCard(vm: MainViewModel) {
    SoftCard {
        SectionLabel("PREVIEW")
        Spacer(Modifier.height(10.dp))
        when (val state = vm.preview) {
            PreviewState.None -> Unit
            is PreviewState.Loading -> {
                Text(state.url, color = Aurum.Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Aurum.Sage,
                    trackColor = Aurum.Line.copy(alpha = 0.35f),
                )
                Spacer(Modifier.height(4.dp))
                Text("情報を取得しています", color = Aurum.Muted, fontSize = 11.sp)
                TextAction("キャンセル", onClick = vm::closePreview)
            }
            is PreviewState.Error -> {
                Text("取得に失敗しました", color = Aurum.Danger, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(state.message, color = Aurum.Muted, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                TextAction("閉じる", onClick = vm::closePreview)
            }
            is PreviewState.Ready -> PreviewContent(state.data, vm)
        }
    }
}

@Composable
private fun PreviewContent(data: PreviewData, vm: MainViewModel) {
    Row(verticalAlignment = Alignment.Top) {
        val thumb = data.thumbnail
        val shape = RoundedCornerShape(12.dp)
        if (thumb != null) {
            val bitmap = remember(thumb) { thumb.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(128.dp)
                    .height(72.dp)
                    .clip(shape)
                    .border(1.dp, Aurum.Line, shape),
            )
        } else {
            Box(
                Modifier
                    .width(128.dp)
                    .height(72.dp)
                    .border(1.dp, Aurum.Line.copy(alpha = 0.5f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text("NO IMAGE", color = Aurum.Muted, fontSize = 9.sp, letterSpacing = 0.8.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(data.title, color = Aurum.Text, fontSize = 13.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val meta = listOfNotNull(
                data.uploader,
                data.durationSec?.let { formatDuration(it) },
                data.site,
            ).joinToString("  /  ")
            if (meta.isNotBlank()) {
                Text(meta, color = Aurum.Muted, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    SoftDivider()
    Spacer(Modifier.height(12.dp))

    if (data.isPlaylist) {
        val selected = data.entries.count { it.selected }
        data.limitNote?.let { note ->
            Text(note, color = Aurum.Danger, fontSize = 11.5.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
        }
        data.diagLog?.let { log ->
            val clip = LocalClipboardManager.current
            val ctx = LocalContext.current
            TextAction("診断ログをコピー") {
                clip.setText(androidx.compose.ui.text.AnnotatedString(log))
                android.widget.Toast.makeText(ctx, "コピーしました。会話に貼り付けてください", android.widget.Toast.LENGTH_LONG).show()
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$selected / ${data.entries.size} 件を選択中",
                color = Aurum.SageDeep,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextAction("全選択") { vm.selectAllEntries(true) }
            Spacer(Modifier.width(16.dp))
            TextAction("全解除") { vm.selectAllEntries(false) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .border(1.dp, Aurum.Line.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        ) {
            items(data.entries, key = { it.index }) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleEntry(entry.index) }
                        .padding(end = 12.dp),
                ) {
                    Checkbox(
                        checked = entry.selected,
                        onCheckedChange = { vm.toggleEntry(entry.index) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Aurum.Sage,
                            uncheckedColor = Aurum.Line,
                            checkmarkColor = Aurum.OnSage,
                        ),
                    )
                    Text(
                        "${entry.index}. ${entry.title}",
                        color = Aurum.Text,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    entry.durationSec?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(formatDuration(it), color = Aurum.Muted, fontSize = 10.5.sp)
                    }
                }
            }
        }
    } else if (data.heights.isNotEmpty()) {
        SectionLabel("AVAILABLE")
        Spacer(Modifier.height(6.dp))
        Text(
            data.heights.joinToString("   ") { "${it}P" },
            color = Aurum.SageDeep,
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
        )
    } else {
        Text("画質の一覧は取得できませんでした (保存時に自動で選択されます)", color = Aurum.Muted, fontSize = 11.sp)
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "保存設定: " + vm.currentOptions().copy(playlist = data.isPlaylist).summary(),
        color = Aurum.Muted,
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp,
    )
    Spacer(Modifier.height(10.dp))
    SageButton(
        "この内容でダウンロード",
        enabled = !data.isPlaylist || data.entries.any { it.selected },
        onClick = vm::downloadPreview,
        filled = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextAction("閉じる", color = Aurum.Muted, onClick = vm::closePreview)
    }
}

@Composable
private fun QueueHeader(
    count: Int,
    hasActive: Boolean,
    hasPaused: Boolean,
    hasFinished: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        SectionLabel("QUEUE  ($count)")
        if (hasActive || hasPaused || hasFinished) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (hasActive) TextAction("すべて一時停止", onClick = onPauseAll)
                if (hasPaused) TextAction("すべて再開", onClick = onResumeAll)
                if (hasFinished) TextAction("完了分を消去", color = Aurum.Muted, onClick = onClear)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, Aurum.Line.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("キューは空です", color = Aurum.Muted, fontSize = 12.sp, letterSpacing = 0.8.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onPlay: (SavedFile) -> Unit,
    onShare: (List<SavedFile>) -> Unit,
) {
    // 複数ファイル (再生リスト) のときだけ、どれを開くか選ぶダイアログを出す
    var picker by remember { mutableStateOf<PickAction?>(null) }
    val statusColor = when (item.status) {
        Status.RUNNING -> Aurum.SageDeep
        Status.DONE -> Aurum.Sage
        Status.PAUSED -> Aurum.Sage.copy(alpha = 0.6f)
        Status.FAILED -> Aurum.Danger
        else -> Aurum.Muted
    }
    val isAccount = item.account != null
    SoftCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title ?: item.url,
                    color = Aurum.Text,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(item.optionLabel, color = Aurum.Muted, fontSize = 9.5.sp, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(item.statusLabel, color = statusColor, fontSize = 10.5.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
        }

        if (item.status == Status.RUNNING || item.status == Status.DONE || item.status == Status.PAUSED) {
            Spacer(Modifier.height(10.dp))
            // アカウント一括は全体の件数が事前に分からないので、実行中は進捗の目盛りを出さずに動かし続ける
            if (isAccount && item.status == Status.RUNNING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Aurum.Sage,
                    trackColor = Aurum.Line.copy(alpha = 0.35f),
                )
            } else {
                SageProgress(if (isAccount && item.status != Status.DONE) 0f else item.progress)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (!isAccount || item.status == Status.DONE) {
                    Text("${(item.progress * 100).toInt()}%", color = Aurum.SageDeep, fontSize = 11.sp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    item.message,
                    color = Aurum.Muted,
                    fontSize = 11.sp,
                    maxLines = if (isAccount) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.status == Status.RUNNING && item.eta > 0) {
                    Text("残り ${formatEta(item.eta)}", color = Aurum.Muted, fontSize = 11.sp)
                }
            }
        } else if (item.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(item.message, color = statusColor.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            when (item.status) {
                Status.QUEUED, Status.RUNNING -> {
                    TextAction("一時停止", onClick = onPause)
                    TextAction("停止", color = Aurum.Danger, onClick = onCancel)
                }
                Status.PAUSED -> {
                    TextAction("再開", onClick = onResume)
                    TextAction("停止", color = Aurum.Danger, onClick = onCancel)
                }
                Status.FAILED, Status.CANCELED -> {
                    TextAction("再試行", onClick = onRetry)
                    TextAction("削除", color = Aurum.Muted, onClick = onRemove)
                }
                Status.DONE -> {
                    val files = item.savedFiles
                    if (files.isNotEmpty()) {
                        TextAction(if (isAccount) "開く" else "再生") {
                            if (files.size == 1) onPlay(files[0]) else picker = PickAction.PLAY
                        }
                        TextAction("共有") {
                            if (files.size == 1) onShare(files) else picker = PickAction.SHARE
                        }
                    }
                    TextAction("削除", color = Aurum.Muted, onClick = onRemove)
                }
            }
        }
    }

    picker?.let { kind ->
        val files = item.savedFiles
        FilePickerDialog(
            title = if (kind == PickAction.PLAY) (if (isAccount) "OPEN" else "PLAY") else "SHARE",
            files = files,
            // 数百件を一度に渡すと共有先へ受け渡せないので、件数が多いときは一括共有を出さない
            allLabel = if (kind == PickAction.SHARE && files.size <= 100) "すべて共有 (${files.size} 件)" else null,
            onPick = { file ->
                picker = null
                if (kind == PickAction.PLAY) onPlay(file) else onShare(listOf(file))
            },
            onAll = {
                picker = null
                onShare(files)
            },
            onDismiss = { picker = null },
        )
    }
}

private enum class PickAction { PLAY, SHARE }

@Composable
private fun FilePickerDialog(
    title: String,
    files: List<SavedFile>,
    allLabel: String?,
    onPick: (SavedFile) -> Unit,
    onAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Aurum.Card,
        shape = RoundedCornerShape(20.dp),
        title = { SectionLabel(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (allLabel != null) {
                    item {
                        Text(
                            allLabel,
                            color = Aurum.SageDeep,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onAll)
                                .padding(vertical = 12.dp),
                        )
                        SoftDivider()
                    }
                }
                items(files, key = { it.uri }) { file ->
                    Text(
                        file.name,
                        color = Aurum.Text,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(file) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextAction("閉じる", color = Aurum.Muted, onClick = onDismiss) },
    )
}

private fun formatEta(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// --------------------------------------------------------------- components

@Composable
private fun SoftCard(highlight: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = if (highlight) Aurum.Tint else Aurum.Card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (highlight) Aurum.SageSoft else Aurum.Line.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** 小さなグリーンの点を添えた、英字の見出し。 */
@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(Aurum.SageSoft),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = Aurum.SageDeep,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SoftDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Aurum.Line.copy(alpha = 0.8f)),
    )
}

@Composable
private fun SageProgress(progress: Float) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(shape)
            .background(Aurum.Line.copy(alpha = 0.7f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(shape)
                .background(Aurum.sageBrush),
        )
    }
}

@Composable
private fun SageSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.scale(0.85f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Aurum.OnSage,
            checkedTrackColor = Aurum.Sage,
            checkedBorderColor = Aurum.Sage,
            uncheckedThumbColor = Aurum.Card,
            uncheckedTrackColor = Aurum.Line,
            uncheckedBorderColor = Aurum.Line,
        ),
    )
}

/** 選択肢を指定の列数で折り返して並べる。 */
@Composable
private fun <T> ChipGrid(
    options: List<T>,
    columns: Int,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { option ->
                    SageChip(
                        label(option),
                        selected = isSelected(option),
                        modifier = Modifier.weight(1f),
                    ) { onSelect(option) }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SageChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Aurum.Sage else Aurum.CardHigh.copy(alpha = 0.7f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Aurum.OnSage else if (enabled) Aurum.Text else Aurum.Muted,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun SageButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val base = modifier
        .clip(shape)
        .then(
            if (filled) Modifier.background(if (enabled) Aurum.sageBrush else Brush.linearGradient(listOf(Aurum.Line, Aurum.Line)))
            else Modifier.border(1.dp, if (enabled) Aurum.Sage else Aurum.Line, shape),
        )
        .clickable(enabled = enabled, onClick = onClick)
        .padding(vertical = if (filled) 12.dp else 10.dp, horizontal = 16.dp)
    Box(base, contentAlignment = Alignment.Center) {
        Text(
            text,
            color = when {
                filled -> Aurum.OnSage
                enabled -> Aurum.SageDeep
                else -> Aurum.Muted
            },
            fontSize = if (filled) 13.sp else 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TextAction(text: String, color: Color = Aurum.SageDeep, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
    )
}

// -------------------------------------------------------------------- icons

/** 設定ボタン用の歯車。外部のアイコン素材に頼らず Canvas で描く。 */
@Composable
private fun GearIcon(color: Color, hole: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val body = r * 0.66f
        val toothW = r * 0.34f
        val toothH = r * 0.42f
        for (i in 0 until 8) {
            rotate(degrees = i * 45f, pivot = c) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - toothW / 2f, c.y - r),
                    size = Size(toothW, toothH + (r - body) * 0.4f),
                    cornerRadius = CornerRadius(toothW * 0.3f),
                )
            }
        }
        drawCircle(color = color, radius = body, center = c)
        drawCircle(color = hole, radius = body * 0.42f, center = c)
    }
}

/** ロゴ: くすみグリーンの丸に、きゅうりの葉を想わせる葉っぱ。 */
@Composable
private fun LeafBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(brush = Aurum.sageBrush, radius = w / 2f)
        val leaf = Path().apply {
            moveTo(w * 0.28f, h * 0.72f)
            cubicTo(w * 0.22f, h * 0.38f, w * 0.48f, h * 0.22f, w * 0.76f, h * 0.26f)
            cubicTo(w * 0.80f, h * 0.52f, w * 0.62f, h * 0.76f, w * 0.28f, h * 0.72f)
            close()
        }
        drawPath(leaf, color = Aurum.OnSage)
        drawLine(
            color = Aurum.Sage,
            start = Offset(w * 0.30f, h * 0.70f),
            end = Offset(w * 0.64f, h * 0.38f),
            strokeWidth = w * 0.045f,
            cap = StrokeCap.Round,
        )
    }
}
