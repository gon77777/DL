package com.aurum.dl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * 保存先の管理。
 * 標準は MediaStore 経由の Download/KyuuriDL。利用者がフォルダを選んだ場合は、
 * そのフォルダ (Storage Access Framework のツリー URI) へ保存する。
 */
object SaveLocation {

    const val DEFAULT_PATH = "Download/KyuuriDL"

    fun treeUri(context: Context): Uri? = Prefs.saveTreeUri(context)?.let(Uri::parse)

    fun isCustom(context: Context): Boolean = Prefs.saveTreeUri(context) != null

    /** 画面や完了メッセージに出す、人が読める保存先。 */
    fun label(context: Context): String {
        val uri = treeUri(context) ?: return DEFAULT_PATH
        if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            if (docId != null) {
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "")
                val name = if (volume == "primary") "内部ストレージ" else "SDカード"
                return if (path.isEmpty()) name else "$name/$path"
            }
        }
        return DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
    }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
