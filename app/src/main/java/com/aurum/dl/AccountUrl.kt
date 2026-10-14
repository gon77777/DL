package com.aurum.dl

import android.net.Uri

/**
 * アカウントの URL を解釈して、取得に使う URL・保存フォルダ名・ログイン先を決める。
 *
 * - X (Twitter): プロフィール URL を「メディア」タブ (/media) に直す。画像・動画つきの投稿を最も漏れなく辿れる。
 * - それ以外のサイト: 入力された URL をそのまま使う (対応可否は gallery-dl が判断する)。
 */
object AccountUrl {

    data class Target(
        /** gallery-dl に渡す URL。 */
        val url: String,
        /** 保存先の下に作るフォルダ名 (例: x_username)。この下に images / videos を作る。 */
        val folder: String,
        /** Cookie の保存キー兼、ログイン対象のホスト (例: x.com)。 */
        val siteHost: String,
        /** ログイン画面として開く URL。 */
        val loginUrl: String,
        val isTwitter: Boolean,
    )

    private val twitterHosts = setOf("x.com", "twitter.com", "fixupx.com", "fxtwitter.com", "vxtwitter.com")

    private val twitterReserved = setOf(
        "i", "home", "explore", "search", "settings", "intent", "share", "hashtag",
        "messages", "notifications", "compose", "login", "tos", "privacy",
    )

    /** プロフィール本体とみなす X の第 2 階層 (これらは /media に直す)。 */
    private val twitterProfileTabs = setOf("media", "with_replies", "tweets", "highlights")

    private val handleRegex = Regex("^[A-Za-z0-9_]{1,15}$")

    fun parse(raw: String): Target? {
        val text = raw.trim()
        if (text.isEmpty() || text.contains(' ') || text.contains('\n')) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val rawHost = uri.host?.lowercase() ?: return null
        val host = rawHost.removePrefix("www.").removePrefix("mobile.")
        if (!host.contains('.')) return null
        val segments = uri.pathSegments.filter { it.isNotBlank() }

        if (host in twitterHosts) return parseTwitter(segments)

        if (segments.isEmpty()) return null
        val parts = host.split('.')
        val label = if (parts.size >= 2) parts[parts.size - 2] else parts[0]
        val pathPart = segments.take(3).joinToString("_").replace("@", "")
        return Target(
            url = withScheme,
            folder = sanitize("${label}_$pathPart"),
            siteHost = host,
            loginUrl = "https://$rawHost/",
            isTwitter = false,
        )
    }

    private fun parseTwitter(segments: List<String>): Target? {
        val handle = segments.firstOrNull() ?: return null
        if (!handleRegex.matches(handle) || handle.lowercase() in twitterReserved) return null
        val second = segments.getOrNull(1)?.lowercase()
        val url = if (second == null || second in twitterProfileTabs) {
            "https://x.com/$handle/media"
        } else {
            // /status/ID (1 件の投稿) や /likes などは、そのまま gallery-dl に任せる
            "https://x.com/" + segments.joinToString("/")
        }
        return Target(
            url = url,
            folder = sanitize("x_$handle"),
            siteHost = "x.com",
            loginUrl = "https://x.com/i/flow/login",
            isTwitter = true,
        )
    }

    private fun sanitize(name: String): String =
        Regex("[^A-Za-z0-9._-]").replace(name, "_").trim('.', '_').take(60).ifEmpty { "account" }
}
