package com.aurum.dl

import android.content.Context
import android.content.SharedPreferences

/** 設定と前回の選択内容を端末に保存する。 */
object Prefs {

    fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("aurum_prefs", Context.MODE_PRIVATE)

    fun maxConcurrent(context: Context): Int = sp(context).getInt("max_concurrent", 2).coerceIn(1, 5)

    fun setMaxConcurrent(context: Context, value: Int) {
        sp(context).edit().putInt("max_concurrent", value.coerceIn(1, 5)).apply()
    }

    /** Wi-Fi 接続時のみダウンロードを実行する。初期値は ON。 */
    fun wifiOnly(context: Context): Boolean = getBool(context, "wifi_only", true)

    /** アプリ起動時に yt-dlp を自動で更新する。初期値は OFF。 */
    fun autoUpdate(context: Context): Boolean = getBool(context, "auto_update", false)

    /** アプリ起動時に gallery-dl を自動で更新する。初期値は OFF。 */
    fun galleryAutoUpdate(context: Context): Boolean = getBool(context, "gallery_auto_update", false)

    /** 利用者が選んだ保存先フォルダのツリー URI。null なら標準 (Download/KyuuriDL)。 */
    fun saveTreeUri(context: Context): String? = sp(context).getString("save_tree_uri", null)

    fun setSaveTreeUri(context: Context, value: String?) {
        sp(context).edit().apply { if (value == null) remove("save_tree_uri") else putString("save_tree_uri", value) }.apply()
    }

    fun getBool(context: Context, key: String, default: Boolean): Boolean = sp(context).getBoolean(key, default)

    fun putBool(context: Context, key: String, value: Boolean) {
        sp(context).edit().putBoolean(key, value).apply()
    }

    /** 高速モード (分割ダウンロード)。初期値は OFF。 */
    fun fastMode(context: Context): Boolean = getBool(context, "fast_mode", false)

    /** 高速モードで同時に使う接続数 (4〜16)。 */
    fun fastConnections(context: Context): Int = sp(context).getInt("fast_connections", 8).coerceIn(4, 16)

    fun setFastConnections(context: Context, value: Int) {
        sp(context).edit().putInt("fast_connections", value.coerceIn(4, 16)).apply()
    }

    /** 再生リスト・アカウントの取得済みの分を飛ばす。初期値は ON。 */
    fun archiveEnabled(context: Context): Boolean = getBool(context, "archive_enabled", true)

    /** 取得済みの記録を残す日数。使われないまま過ぎると記録は消える。0 は無期限 (自動では消さない)。 */
    fun archiveDays(context: Context): Int = sp(context).getInt("archive_days", 30).coerceIn(0, 365)

    fun setArchiveDays(context: Context, value: Int) {
        sp(context).edit().putInt("archive_days", value.coerceIn(0, 365)).apply()
    }

    inline fun <reified T : Enum<T>> getEnum(context: Context, key: String, default: T): T =
        runCatching { enumValueOf<T>(sp(context).getString(key, default.name) ?: default.name) }
            .getOrDefault(default)

    fun putEnum(context: Context, key: String, value: Enum<*>) {
        sp(context).edit().putString(key, value.name).apply()
    }
}
