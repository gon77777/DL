package com.aurum.dl

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * アカウント一括ダウンロードに使う gallery-dl (純 Python 製) の実行環境。
 *
 * - アプリに同梱した gallery-dl と依存ライブラリ (requests など) を、初回に端末内へ展開する
 * - yt-dlp と同じ Python (youtubedl-android に内蔵) で実行する
 * - 「最新版に更新」で、PyPI の最新の gallery-dl へ差し替えられる (X などの仕様変更への追従用)
 */
object GalleryDl {

    private const val ASSET = "gallerydl-libs.zip"
    private const val STAMP_FILE = ".app-version"

    /** gallery_dl / requests など、Python から import するパッケージの置き場所。 */
    fun libsDir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "gallerydl")

    // ---------------------------------------------------------------- install

    /** 展開済みでなければ、同梱の gallery-dl を端末内へ展開する。アプリを更新したときも展開し直す。 */
    @Synchronized
    fun ensureInstalled(context: Context): File {
        val app = context.applicationContext
        val dir = libsDir(app)
        val stamp = appStamp(app)
        val marker = File(dir, STAMP_FILE)
        if (File(dir, "gallery_dl/__init__.py").isFile && marker.isFile && marker.readText() == stamp) return dir

        val tmp = File(dir.parentFile, "gallerydl.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            app.assets.open(ASSET).use { unzip(it, tmp) }
            File(tmp, STAMP_FILE).writeText(stamp)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("gallery-dl を展開できませんでした")
        } finally {
            tmp.deleteRecursively()
        }
        return dir
    }

    private fun appStamp(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()

    private fun unzip(input: InputStream, dest: File) {
        val root = dest.canonicalPath + File.separator
        ZipInputStream(input).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("不正なパス: $name")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
    }

    fun version(context: Context): String? = runCatching {
        val text = File(libsDir(context), "gallery_dl/version.py").readText()
        Regex("__version__\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    }.getOrNull()

    // -------------------------------------------------------------------- run

    private class Runtime(val python: String, val env: Map<String, String>)

    /**
     * yt-dlp と同じ Python の起動方法を再現する (youtubedl-android の YoutubeDL.execute と同じ環境変数)。
     * ライブラリが保持している実際の値があれば優先し、取れなければ既定の配置から組み立てる。
     */
    private fun runtime(context: Context, pythonPath: List<File>): Runtime {
        val app = context.applicationContext
        val base = File(app.noBackupFilesDir, "youtubedl-android")
        val pythonDir = File(base, "packages/python")
        val ffmpegDir = File(base, "packages/ffmpeg")
        val aria2cDir = File(base, "packages/aria2c")
        val nativeDir = app.applicationInfo.nativeLibraryDir

        var python = File(nativeDir, "libpython.so").absolutePath
        var ld = listOf(pythonDir, ffmpegDir, aria2cDir).joinToString(":") { it.absolutePath + "/usr/lib" }
        var cert = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        var home = pythonDir.absolutePath + "/usr"

        fun field(name: String): Any? = runCatching {
            val f = YoutubeDL::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.get(null)
        }.getOrNull()
        (field("pythonPath") as? File)?.let { python = it.absolutePath }
        (field("ENV_LD_LIBRARY_PATH") as? String)?.let { ld = it }
        (field("ENV_SSL_CERT_FILE") as? String)?.let { cert = it }
        (field("ENV_PYTHONHOME") as? String)?.let { home = it }

        val env = mapOf(
            "LD_LIBRARY_PATH" to ld,
            "SSL_CERT_FILE" to cert,
            "REQUESTS_CA_BUNDLE" to cert,
            "PATH" to ((System.getenv("PATH") ?: "") + ":" + nativeDir),
            "PYTHONHOME" to home,
            "HOME" to home,
            "TMPDIR" to app.cacheDir.absolutePath,
            // gallery-dl のキャッシュ (~/.cache) を、Python 本体の領域ではなくアプリのキャッシュ領域へ置く
            "XDG_CACHE_HOME" to File(app.cacheDir, "gallerydl-cache").absolutePath,
            "PYTHONPATH" to pythonPath.joinToString(":") { it.absolutePath },
            "PYTHONUNBUFFERED" to "1",
            "PYTHONUTF8" to "1",
            "PYTHONIOENCODING" to "utf-8",
        )
        return Runtime(python, env)
    }

    /**
     * gallery-dl を起動する。標準出力に、保存したファイルのパスが 1 行ずつ出る
     * (取得済みでスキップしたものは "# " 付き、ログは "[" で始まる行)。
     */
    fun start(context: Context, args: List<String>, workDir: File): Process {
        val dir = ensureInstalled(context)
        val rt = runtime(context, listOf(dir))
        val command = ArrayList<String>()
        command += rt.python
        command += listOf("-m", "gallery_dl")
        command += args
        // 標準エラーも標準出力へ合流させる。読まれない stderr がパイプを埋めると、長時間の取得が途中で止まるため。
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(rt.env)
        return builder.start()
    }

    // ------------------------------------------------------- python version

    @Volatile
    private var cachedPython: IntArray? = null

    /** 端末に内蔵された Python のバージョン (major, minor, micro)。取得できなければ null。 */
    fun pythonVersion(context: Context): IntArray? {
        cachedPython?.let { return it }
        return try {
            val rt = runtime(context, emptyList())
            val builder = ProcessBuilder(rt.python, "-c", "import sys;print('%d.%d.%d' % sys.version_info[:3])")
                .redirectErrorStream(true)
            builder.environment().putAll(rt.env)
            val proc = builder.start()
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroy()
                return null
            }
            val text = proc.inputStream.bufferedReader().readText().trim().lineSequence().lastOrNull { it.isNotBlank() }
            val parts = text?.split('.')?.mapNotNull { it.trim().toIntOrNull() }
            if (parts == null || parts.size < 3) null else parts.take(3).toIntArray().also { cachedPython = it }
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- update

    /**
     * PyPI の最新版の gallery-dl へ更新する。結果を人が読める文で返す。
     * 差し替える前に、端末の Python で起動できるか確認する (動かない版で壊さないため)。
     */
    fun update(context: Context): String {
        val app = context.applicationContext
        ensureInstalled(app)
        val current = version(app)

        // 端末の Python で動く最新版を選ぶ (PyPI の requires_python を見る)。Python の版が分からなければ最新版にする
        val py = pythonVersion(app)
        val pick = py?.let { runCatching { PyCompat.pickLatest("gallery-dl", it) }.getOrNull() }
        val latest: String
        val wheelUrl: String?
        var extra = ""
        if (pick != null) {
            latest = pick.version
            wheelUrl = pick.wheelUrl()
            if (!pick.isLatest) {
                extra = "\n最新版 ${pick.latestOverall} は Python ${pick.latestRequires.ifBlank { "新しい版" }} が必要で、" +
                    "この端末の Python ${PyCompat.pythonLabel(py!!)} では動かないため、動く最新版を選びました"
            }
        } else {
            val meta = JSONObject(httpGet("https://pypi.org/pypi/gallery-dl/json"))
            latest = meta.getJSONObject("info").getString("version")
            var found: String? = null
            val files = meta.getJSONArray("urls")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                if (f.optString("packagetype") == "bdist_wheel" && f.optString("filename").endsWith("py3-none-any.whl")) {
                    found = f.optString("url")
                    break
                }
            }
            wheelUrl = found
        }
        val curKey = PyCompat.versionKey(current)
        val newKey = PyCompat.versionKey(latest)
        if (latest == current || (curKey != null && newKey != null && PyCompat.compare(curKey, newKey) >= 0)) {
            return "すでに最新版です ($current)$extra"
        }
        if (wheelUrl.isNullOrBlank()) return "更新ファイルが見つかりませんでした"

        val stage = File(app.cacheDir, "gallerydl-stage")
        val wheel = File(app.cacheDir, "gallerydl-update.whl")
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            download(wheelUrl, wheel)
            ZipFile(wheel).use { zip -> extractPackage(zip, "gallery_dl/", stage) }
            if (!File(stage, "gallery_dl/__init__.py").isFile) return "更新ファイルが正しくありません"
            if (!smokeTest(app, stage, latest)) return "$latest は端末の Python で動かないため、更新しませんでした"

            swapPackage(app, File(stage, "gallery_dl"))
        } finally {
            wheel.delete()
            stage.deleteRecursively()
        }
        return "$latest に更新しました$extra"
    }

    /** 新しい gallery_dl を横に置いてから名前を入れ替える。コピーの途中で失敗しても、動いていた版は残る。 */
    private fun swapPackage(context: Context, fresh: File) {
        val libs = libsDir(context)
        val target = File(libs, "gallery_dl")
        val next = File(libs, "gallery_dl.new")
        val old = File(libs, "gallery_dl.old")
        next.deleteRecursively()
        old.deleteRecursively()
        try {
            fresh.copyRecursively(next, overwrite = true)
            if (target.exists() && !target.renameTo(old)) throw IOException("gallery-dl を差し替えられませんでした")
            if (!next.renameTo(target)) {
                old.renameTo(target)
                throw IOException("gallery-dl を差し替えられませんでした")
            }
        } finally {
            next.deleteRecursively()
            old.deleteRecursively()
        }
    }

    private fun extractPackage(zip: ZipFile, prefix: String, dest: File) {
        val root = dest.canonicalPath + File.separator
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (!name.startsWith(prefix)) continue
            val out = File(dest, name)
            if (!out.canonicalPath.startsWith(root)) throw IOException("不正なパス: $name")
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            }
        }
    }

    /** 新しい gallery_dl を先頭に置いて `--version` を実行し、期待する版が表示されれば OK。 */
    private fun smokeTest(context: Context, stage: File, expected: String): Boolean = try {
        val rt = runtime(context, listOf(stage, libsDir(context)))
        val builder = ProcessBuilder(rt.python, "-m", "gallery_dl", "--version")
            .redirectErrorStream(true)
        builder.environment().putAll(rt.env)
        val proc = builder.start()
        if (!proc.waitFor(40, TimeUnit.SECONDS)) {
            proc.destroy()
            false
        } else {
            proc.exitValue() == 0 && proc.inputStream.bufferedReader().readText().trim() == expected
        }
    } catch (e: Exception) {
        false
    }

    internal fun httpGet(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = open(url)
        try {
            conn.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "KyuuriDL")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        return conn
    }
}
