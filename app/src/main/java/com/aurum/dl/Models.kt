package com.aurum.dl

import android.graphics.Bitmap

enum class Mode(val label: String) {
    VIDEO("動画"),
    AUDIO("音声のみ"),
}

/** 入力欄の使い方。MEDIA は従来どおり yt-dlp、ACCOUNT はアカウント一括 (gallery-dl)。 */
enum class SourceMode(val label: String) {
    MEDIA("動画・音声"),
    ACCOUNT("アカウント一括"),
}

/** アカウント一括ダウンロードで取得するメディアの種類。 */
enum class MediaKind(val label: String, val summary: String) {
    BOTH("画像と動画", "IMAGES + VIDEOS"),
    IMAGES("画像のみ", "IMAGES"),
    VIDEOS("動画のみ", "VIDEOS"),
    ;

    fun includes(kind: FileKind): Boolean = when (this) {
        BOTH -> true
        IMAGES -> kind == FileKind.IMAGE
        VIDEOS -> kind == FileKind.VIDEO
    }
}

/** 保存したファイルの種類。アカウント一括では images / videos フォルダに振り分ける。 */
enum class FileKind(val folder: String) {
    IMAGE("images"),
    VIDEO("videos"),
    ;

    companion object {
        private val imageExt = setOf("jpg", "jpeg", "jpe", "png", "gif", "webp", "avif", "bmp", "heic", "heif", "tif", "tiff")
        private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "flv", "ts", "wmv")

        /** gallery-dl の --filter に渡す、拡張子の判定式。 */
        fun filterExpression(kind: FileKind): String {
            val set = if (kind == IMAGE) imageExt else videoExt
            return "extension.lower() in (" + set.joinToString(",") { "'$it'" } + ")"
        }

        fun of(extension: String): FileKind? {
            val ext = extension.lowercase()
            return when (ext) {
                in imageExt -> IMAGE
                in videoExt -> VIDEO
                else -> null
            }
        }
    }
}

/** アカウント一括ダウンロードの 1 件分の指定。 */
data class AccountJob(
    val target: AccountUrl.Target,
    val media: MediaKind,
) {
    fun summary(): String = "ACCOUNT  ${target.siteHost.uppercase()}  /  ${media.summary}"
}

enum class VideoQuality(val label: String, val height: Int?) {
    BEST("BEST", null),
    P2160("2160P", 2160),
    P1440("1440P", 1440),
    P1080("1080P", 1080),
    P720("720P", 720),
    P480("480P", 480),
    P360("360P", 360),
}

enum class VideoContainer(val label: String, val code: String) {
    MP4("MP4", "mp4"),
    MKV("MKV", "mkv"),
    WEBM("WEBM", "webm"),
}

enum class VideoCodec(val label: String) {
    AUTO("自動"),
    H264("H.264"),
    VP9("VP9"),
    AV1("AV1"),
}

enum class AudioFormat(val code: String) {
    MP3("mp3"),
    M4A("m4a"),
    OPUS("opus"),
    FLAC("flac");

    val label: String get() = name
}

enum class AudioQuality(val label: String, val value: String) {
    BEST("最高", "0"),
    K192("192K", "192K"),
    K128("128K", "128K"),
}

enum class Status(val label: String) {
    QUEUED("待機中"),
    RUNNING("ダウンロード中"),
    PAUSED("一時停止"),
    DONE("完了"),
    FAILED("失敗"),
    CANCELED("停止"),
}

/** 1 回のダウンロードに適用する形式指定。 */
data class Options(
    val mode: Mode = Mode.VIDEO,
    val quality: VideoQuality = VideoQuality.BEST,
    val container: VideoContainer = VideoContainer.MP4,
    val codec: VideoCodec = VideoCodec.AUTO,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioQuality: AudioQuality = AudioQuality.BEST,
    val playlist: Boolean = false,
) {
    fun summary(withPlaylist: Boolean = true): String = buildString {
        if (mode == Mode.VIDEO) {
            append("VIDEO ").append(quality.label).append(" / ").append(container.label)
            if (codec != VideoCodec.AUTO) append(" / ").append(codec.label)
        } else {
            append("AUDIO ").append(audioFormat.label)
            if (audioFormat != AudioFormat.FLAC && audioQuality != AudioQuality.BEST) {
                append(" / ").append(audioQuality.label)
            }
        }
        if (withPlaylist && playlist) append("  /  PLAYLIST")
    }
}

/** 端末の Download フォルダへ保存したファイル。再生・共有に使う。 */
data class SavedFile(
    /** MediaStore の content:// URI。 */
    val uri: String,
    val name: String,
    /** 他アプリへ渡すときの MIME タイプ。 */
    val mime: String,
) {
    val isAudio: Boolean get() = mime.startsWith("audio/")
}

data class DownloadItem(
    val id: String,
    val url: String,
    val options: Options,
    /** 再生リストの一部だけを取る場合の yt-dlp --playlist-items 指定 (例 "1:3,7")。null なら全件。 */
    val playlistItems: String? = null,
    val title: String? = null,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val eta: Long = -1L,
    val message: String = "",
    /** 完了時に保存されたファイル (再生リストなら複数)。 */
    val savedFiles: List<SavedFile> = emptyList(),
    /** 「Wi-Fi接続時のみ実行」により自動で一時停止している。Wi-Fi に戻ると自動で再開する。 */
    val waitingForWifi: Boolean = false,
    /** アカウント一括ダウンロード (gallery-dl) の指定。null なら通常の yt-dlp ダウンロード。 */
    val account: AccountJob? = null,
) {
    val isFinished: Boolean
        get() = status == Status.DONE || status == Status.FAILED || status == Status.CANCELED

    val isActive: Boolean
        get() = status == Status.QUEUED || status == Status.RUNNING

    val optionLabel: String get() = account?.summary() ?: options.summary()

    val statusLabel: String
        get() = if (status == Status.PAUSED && waitingForWifi) "Wi-Fi待ち" else status.label
}

data class EngineState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val version: String = "---",
    val note: String = "初期化中",
)

// ------------------------------------------------------------------ preview

data class PreviewEntry(
    val index: Int,
    val title: String,
    val durationSec: Long?,
    val selected: Boolean = true,
)

data class PreviewData(
    val url: String,
    val title: String,
    val uploader: String?,
    val site: String?,
    val durationSec: Long?,
    val thumbnail: Bitmap?,
    val isPlaylist: Boolean,
    val entries: List<PreviewEntry>,
    /** 単体動画で選べる映像の高さ (大きい順)。 */
    val heights: List<Int>,
    /** 取得に失敗したときの診断ログ (コピーして報告できるようにする)。 */
    val diagLog: String? = null,
    /** 再生リストの一部が取得できないときの、利用者向けの説明。 */
    val limitNote: String? = null,
)

sealed interface PreviewState {
    data object None : PreviewState
    data class Loading(val url: String) : PreviewState
    data class Ready(val data: PreviewData) : PreviewState
    data class Error(val url: String, val message: String) : PreviewState
}
