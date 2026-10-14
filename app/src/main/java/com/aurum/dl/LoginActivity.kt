package com.aurum.dl

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * アカウント一括ダウンロード用のログイン画面。
 * アプリ内のブラウザ (WebView) で対象サイトにログインしてもらい、「完了」でその Cookie を端末内に保存する。
 * パスワードそのものはアプリでは扱わない。
 */
class LoginActivity : ComponentActivity() {

    private var web: WebView? = null
    private var siteHost: String = ""

    /** Pixiv のログインに使う PKCE。画面を回転しても同じ値を使うため保存する。 */
    private var pkce: PixivAuth.Pkce? = null
    private val pixivMode: Boolean get() = PixivAuth.isPixiv(siteHost)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        var startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        siteHost = intent.getStringExtra(EXTRA_SITE).orEmpty()
        if (startUrl.isBlank() || siteHost.isBlank()) {
            finish()
            return
        }
        if (pixivMode) {
            val v = savedInstanceState?.getString("pkce_v")
            val c = savedInstanceState?.getString("pkce_c")
            pkce = if (v != null && c != null) PixivAuth.Pkce(v, c) else PixivAuth.newPkce()
            startUrl = PixivAuth.loginUrl(pkce!!)
        }

        val gold = Color.parseColor("#55704F")
        val muted = Color.parseColor("#8D907D")
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun barButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(color)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
        }

        val hint = TextView(this).apply {
            maxLines = 2
            text = if (pixivMode) "ログインすると自動で完了します" else "ログインが終わったら「完了」"
            setTextColor(muted)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F9F5EB"))
            addView(barButton("キャンセル", muted) { finish() })
            addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // ログインが進まないときは、PC 向け表示に切り替えると通ることがある
            val toggle = barButton("PC表示", gold) {}
            toggle.setOnClickListener {
                desktop = !desktop
                toggle.text = if (desktop) "スマホ表示" else "PC表示"
                web?.let { applyBrowserMode(it) }
                web?.reload()
            }
            addView(toggle)
            addView(barButton("完了", gold) { finishLogin(false) })
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            // WebView だと分かる印 ("; wv" と "Version/4.0") があると、ログインを受け付けないサイトがある (X など)。
            // 普通の Chrome と同じ形の User-Agent にする
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@LoginActivity)
                .replace("; wv", "").replace(" Version/4.0", "")
            // 送信するたびに付く「X-Requested-With: アプリ名」も、WebView の目印になるので外す
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
            }
            // 画面のエラーを、下の案内欄に出す (ログインが進まない原因を知る手がかり)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        hint.text = "画面のエラー: " + m.message().take(70)
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // X: ログインできて認証 Cookie が入ったら、「完了」を押さなくても自動で保存する
                    if (siteHost == "x.com") finishLogin(true)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    val path = request.url.path.orEmpty()
                    if (errorResponse.statusCode >= 400 && (request.isForMainFrame || "task" in path || "login" in path || "auth" in path || "token" in path)) {
                        hint.text = "通信エラー ${errorResponse.statusCode}: ${path.takeLast(40)}"
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    // Pixiv: ログイン後に pixiv://account/login?code=... へ移る。そこで認可コードを受け取る
                    if (pixivMode && u.scheme == "pixiv") {
                        onPixivCode(u.getQueryParameter("code"))
                        return true
                    }
                    return false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // 万一 shouldOverrideUrlLoading を通らずに pixiv:// へ移った場合も、認可コードを受け取る
                    if (pixivMode && request.url.scheme == "pixiv") {
                        onPixivCode(request.url.getQueryParameter("code"))
                        return
                    }
                    if (request.isForMainFrame) {
                        Toast.makeText(
                            this@LoginActivity,
                            "ページを開けませんでした (${error.description})。通信状況を確認してください",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        web = webView

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1EBDD"))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // 端末全体表示のとき、ステータスバー・ナビゲーションバー・キーボードに重ならないようにする
        ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        applyBrowserMode(webView)
        if (savedInstanceState == null) webView.loadUrl(startUrl) else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
        pkce?.let { outState.putString("pkce_v", it.verifier); outState.putString("pkce_c", it.challenge) }
    }

    /** Pixiv の認可コードをトークンに交換して保存する。 */
    private fun onPixivCode(code: String?) {
        val verifier = pkce?.verifier
        if (code.isNullOrBlank() || verifier == null) {
            Toast.makeText(this, "ログインを確認できませんでした。もう一度お試しください", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "ログインを確認しています", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { PixivAuth.exchange(code, verifier) }
            runOnUiThread {
                result.onSuccess { token ->
                    PixivAuth.save(this, token)
                    Toast.makeText(this, "ログイン情報を保存しました", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { e ->
                    // 認可コードは一度きり・短時間のみ有効。失敗したら最初からやり直す
                    Toast.makeText(this, "ログインに失敗しました (${e.message})。もう一度お試しください", Toast.LENGTH_LONG).show()
                    pkce = PixivAuth.newPkce().also { web?.loadUrl(PixivAuth.loginUrl(it)) }
                }
            }
        }.start()
    }

    /** false: スマホ向けの表示 / true: PC 向けの表示 (User-Agent を Linux の Chrome にする)。 */
    private var desktop = false

    private fun applyBrowserMode(w: WebView) {
        val base = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(" Version/4.0", "")
        val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/120.0.0.0"
        w.settings.userAgentString =
            if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
            else base
        w.settings.useWideViewPort = desktop
        w.settings.loadWithOverviewMode = desktop
        w.settings.setSupportZoom(true)
        w.settings.builtInZoomControls = true
        w.settings.displayZoomControls = false
    }

    /** サイトごとに、いくつかの URL の Cookie をまとめて集める (ログイン先が別ホストでも取りこぼさないため)。 */
    private fun collectCookies(): String {
        val cm = CookieManager.getInstance()
        val urls = mutableListOf("https://$siteHost", "https://www.$siteHost")
        if (siteHost == "x.com") urls += listOf("https://api.x.com", "https://twitter.com")
        val seen = LinkedHashMap<String, String>()
        for (u in urls) {
            cm.getCookie(u)?.split(';')?.forEach { part ->
                val p = part.trim()
                val i = p.indexOf('=')
                if (i > 0) seen.putIfAbsent(p.substring(0, i), p.substring(i + 1))
            }
        }
        return seen.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    private var finished = false

    /** auto=true: ページ表示のたびの自動確認 (未ログインでも何も表示しない)。 */
    private fun finishLogin(auto: Boolean) {
        if (finished) return
        if (pixivMode) {
            if (!auto) Toast.makeText(this, "Pixivは、ログインすると自動で完了します", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = CookieManager.getInstance()
        cm.flush()
        val header = collectCookies()
        // X は認証 Cookie (auth_token) が入るまで保存しない (ログイン途中の自動確認で空のファイルを作らないため)
        if (siteHost == "x.com" && !header.contains("auth_token=")) {
            if (!auto) {
                Toast.makeText(this, "ログインが確認できません。ログインを済ませてから「完了」を押してください", Toast.LENGTH_LONG).show()
            }
            return
        }
        val count = CookieStore.save(this, siteHost, header)
        if (count == 0 || !CookieStore.loggedIn(this, siteHost)) {
            if (!auto) {
                Toast.makeText(this, "ログインが確認できません。ログインを済ませてから「完了」を押してください", Toast.LENGTH_LONG).show()
            }
            return
        }
        finished = true
        Toast.makeText(this, "ログイン情報を保存しました", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SITE = "site"
    }
}
