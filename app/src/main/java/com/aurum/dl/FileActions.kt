package com.aurum.dl

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 保存済みファイルを、端末の標準プレーヤーで開く / 他アプリへ共有する。 */
object FileActions {

    enum class Result { OK, MISSING, NO_APP, FAILED }

    /** 端末に登録されている標準のプレーヤー (ギャラリー・音楽アプリなど) で開く。 */
    fun open(context: Context, file: SavedFile): Result {
        val uri = Uri.parse(file.uri)
        if (!exists(context, uri)) return Result.MISSING
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(context, intent)
    }

    /** 共有シートを開く。複数ファイルはまとめて渡す。 */
    fun share(context: Context, files: List<SavedFile>): Result {
        val present = files.filter { exists(context, Uri.parse(it.uri)) }
        if (present.isEmpty()) return Result.MISSING

        val uris = present.map { Uri.parse(it.uri) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(present[0].mime)
                .putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(commonType(present))
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // 共有先アプリに読み取り権限を渡すには ClipData が必要
        send.clipData = ClipData.newRawUri(present[0].name, uris[0]).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return launch(context, Intent.createChooser(send, null))
    }

    private fun launch(context: Context, intent: Intent): Result = try {
        context.startActivity(intent)
        Result.OK
    } catch (e: ActivityNotFoundException) {
        Result.NO_APP
    } catch (e: Exception) {
        Result.FAILED
    }

    /** 利用者が Download フォルダから消した場合などに備えて、開く前に確認する。 */
    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** 全ファイルが同じ種類なら "audio/*" のように、混在するなら "*/*" を返す。 */
    private fun commonType(files: List<SavedFile>): String {
        val tops = files.map { it.mime.substringBefore('/') }.distinct()
        return if (tops.size == 1) "${tops[0]}/*" else "*/*"
    }
}
