package com.aurum.dl

import android.content.Context
import java.io.File

/**
 * サイトごとのログイン情報 (Cookie) を、この端末のアプリ専用領域に保存する。
 * gallery-dl が読める Netscape 形式のファイルとして書き出す。
 */
object CookieStore {

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "cookies").also { it.mkdirs() }

    fun file(context: Context, siteHost: String): File =
        File(dir(context), Regex("[^A-Za-z0-9.-]").replace(siteHost, "_") + ".txt")

    /** ログイン済みか。X は認証用の Cookie (auth_token) が入っているかまで確認する。 */
    fun loggedIn(context: Context, siteHost: String): Boolean {
        // Pixiv は Cookie ではなくリフレッシュトークンで認証する
        if (PixivAuth.isPixiv(siteHost)) return PixivAuth.token(context) != null
        val f = file(context, siteHost)
        if (!f.isFile || f.length() == 0L) return false
        if (siteHost == "x.com") return runCatching { f.readText().contains("\tauth_token\t") }.getOrDefault(false)
        return true
    }

    fun clear(context: Context, siteHost: String) {
        file(context, siteHost).delete()
        if (PixivAuth.isPixiv(siteHost)) PixivAuth.clear(context)
    }

    /** ログインしないと取得できないサイトか (X と Pixiv)。 */
    fun requiresLogin(siteHost: String): Boolean = siteHost == "x.com" || PixivAuth.isPixiv(siteHost)

    /**
     * 利用者が貼り付けた内容を保存する (ログイン画面が使えないときの代わり)。
     * - Pixiv: リフレッシュトークン
     * - それ以外: "名前=値; 名前=値" の形、または cookies.txt (Netscape 形式) の中身
     * 保存できてログイン済みになれば true。
     */
    fun saveManual(context: Context, siteHost: String, input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return false
        if (PixivAuth.isPixiv(siteHost)) {
            if (text.any { it.isWhitespace() }) return false
            PixivAuth.save(context, text)
            return true
        }
        if (text.contains('\t')) {
            file(context, siteHost).writeText(
                (if (text.startsWith("#")) "" else "# Netscape HTTP Cookie File\n") + text + "\n",
            )
        } else {
            val header = text.removePrefix("Cookie:").trim()
            if (save(context, siteHost, header) == 0) return false
        }
        return loggedIn(context, siteHost)
    }

    /**
     * WebView から取り出した Cookie 文字列 ("a=b; c=d") を保存する。
     * 保存した Cookie の個数を返す (0 なら何も書かない)。
     */
    fun save(context: Context, siteHost: String, cookieHeader: String): Int {
        val expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val domain = ".$siteHost"
        val lines = cookieHeader.split(';').mapNotNull { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) {
                null
            } else {
                val name = p.substring(0, i).trim()
                val value = p.substring(i + 1).trim()
                if (name.isEmpty() || value.contains('\t') || value.contains('\n')) null
                else "$domain\tTRUE\t/\tTRUE\t$expires\t$name\t$value"
            }
        }
        if (lines.isEmpty()) return 0
        file(context, siteHost).writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        return lines.size
    }
}
