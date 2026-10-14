package com.aurum.dl

import org.json.JSONObject

/**
 * PyPI の「必要な Python のバージョン」(requires_python) を読んで、端末の Python で動く最新版を選ぶ。
 */
object PyCompat {

    class Pick(
        /** 端末で動く最新版。 */
        val version: String,
        /** PyPI 上の最新版 (端末で動かないこともある)。 */
        val latestOverall: String,
        /** 最新版が要求する Python (例 ">=3.13")。 */
        val latestRequires: String,
        /** 選んだ版の配布ファイル。 */
        val files: List<JSONObject>,
    ) {
        val isLatest: Boolean get() = version == latestOverall

        /** py3-none-any の wheel の URL。 */
        fun wheelUrl(): String? = files.firstOrNull {
            it.optString("packagetype") == "bdist_wheel" && it.optString("filename").endsWith("py3-none-any.whl")
        }?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /** "2025.01.15" や "1.32.16" を数字の並びにする。正式版でない (a / b / rc / dev) 版は null。 */
    fun versionKey(v: String?): List<Int>? {
        if (v.isNullOrBlank() || !Regex("""\d+(\.\d+)*""").matches(v.trim())) return null
        return v.trim().split('.').map { it.toInt() }
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private val specRegex = Regex("""^(>=|<=|==|!=|~=|>|<)\s*(\d+(?:\.\d+)*)(\.\*)?$""")

    /** requires_python (例 ">=3.8,!=3.9.*") を、端末の Python (major, minor, micro) が満たすか。読めない指定は満たすとみなす。 */
    fun satisfies(spec: String?, py: IntArray): Boolean {
        if (spec.isNullOrBlank()) return true
        val ver = py.toList()
        for (raw in spec.split(',')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val m = specRegex.find(part) ?: continue
            val op = m.groupValues[1]
            val num = m.groupValues[2].split('.').map { it.toInt() }
            val wildcard = m.groupValues[3].isNotEmpty()
            val ok = when (op) {
                ">=" -> compare(ver, num) >= 0
                ">" -> compare(ver, num) > 0
                "<=" -> compare(ver, num) <= 0
                "<" -> compare(ver, num) < 0
                "==" -> if (wildcard) ver.take(num.size) == num else compare(ver, num) == 0
                "!=" -> if (wildcard) ver.take(num.size) != num else compare(ver, num) != 0
                "~=" -> compare(ver, num) >= 0 && ver.take(maxOf(num.size - 1, 1)) == num.take(maxOf(num.size - 1, 1))
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    /** PyPI の全リリースから、端末の Python で動く最新の正式版を選ぶ。 */
    fun pickLatest(pkg: String, py: IntArray): Pick {
        val meta = JSONObject(GalleryDl.httpGet("https://pypi.org/pypi/$pkg/json"))
        val releases = meta.getJSONObject("releases")
        class Rel(val name: String, val key: List<Int>, val files: List<JSONObject>, val requires: String)

        val list = ArrayList<Rel>()
        val names = releases.keys()
        while (names.hasNext()) {
            val name = names.next()
            val key = versionKey(name) ?: continue
            val arr = releases.getJSONArray(name)
            val files = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("yanked", false) }
            if (files.isEmpty()) continue
            list += Rel(name, key, files, files.first().optString("requires_python"))
        }
        if (list.isEmpty()) throw IllegalStateException("版の一覧を読み取れませんでした")
        list.sortWith { a, b -> compare(b.key, a.key) }
        val newest = list.first()
        val usable = list.firstOrNull { satisfies(it.requires, py) }
            ?: throw IllegalStateException("端末の Python で動く版が見つかりませんでした")
        return Pick(usable.name, newest.name, newest.requires, usable.files)
    }

    fun pythonLabel(py: IntArray): String = "${py[0]}.${py[1]}"
}
