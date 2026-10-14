package com.aurum.dl

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Pixiv のログイン。gallery-dl の Pixiv は Cookie ではなく「リフレッシュトークン」(OAuth) を必要とする。
 * アプリ内ブラウザで Pixiv にログインし、返ってきた認可コードをトークンに交換して端末内に保存する。
 * (gallery-dl の `oauth:pixiv` と同じ手順。パスワードはアプリでは扱わない)
 */
object PixivAuth {

    private const val CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
    private const val CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
    private const val REDIRECT = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"

    fun isPixiv(siteHost: String): Boolean = siteHost == "pixiv.net" || siteHost == "www.pixiv.net"

    class Pkce(val verifier: String, val challenge: String)

    fun newPkce(): Pkce {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val verifier = Base64.encodeToString(bytes, flags)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Pkce(verifier, Base64.encodeToString(digest, flags))
    }

    fun loginUrl(p: Pkce): String =
        "https://app-api.pixiv.net/web/v1/login?code_challenge=${p.challenge}&code_challenge_method=S256&client=pixiv-android"

    /** 認可コードをリフレッシュトークンに交換する。失敗したら例外。 */
    fun exchange(code: String, verifier: String): String {
        val form = mapOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "include_policy" to "true",
            "redirect_uri" to REDIRECT,
        ).entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = URL("https://oauth.secure.pixiv.net/auth/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "PixivAndroidApp/5.0.234 (Android 11; Pixel 5)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(form.toByteArray()) }
            val ok = conn.responseCode in 200..299
            val body = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IllegalStateException("HTTP ${conn.responseCode}")
            val token = JSONObject(body).optString("refresh_token")
            if (token.isBlank()) throw IllegalStateException("トークンを取得できませんでした")
            return token
        } finally {
            conn.disconnect()
        }
    }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, "pixiv_refresh_token.txt")

    fun token(context: Context): String? =
        file(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun save(context: Context, token: String) {
        file(context).writeText(token.trim())
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
