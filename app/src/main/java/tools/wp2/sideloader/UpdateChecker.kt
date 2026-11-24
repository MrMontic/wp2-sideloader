package tools.wp2.sideloader

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/MrMontic/wp2-sideloader/releases/latest"

    /** Returns true if [latest] is strictly newer than [current] using numeric semver parts. */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val cur = parseParts(current)
        val lat = parseParts(latest)
        val len = maxOf(cur.size, lat.size)
        for (i in 0 until len) {
            val c = cur.getOrElse(i) { 0 }
            val l = lat.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    internal fun parseReleaseJson(jsonText: String, currentVersion: String): UpdateInfo? {
        val obj = JSONObject(jsonText)
        val rawTag = obj.optString("tag_name", "").trim()
        if (rawTag.isEmpty()) return null
        val cleanVersion = rawTag.removePrefix("v").removePrefix("V")
        if (!isNewerVersion(currentVersion, cleanVersion)) return null

        val releaseUrl = obj.optString(
            "html_url",
            "https://github.com/MrMontic/wp2-sideloader/releases/latest",
        )
        var apkUrl: String? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                    apkUrl = url
                    break
                }
            }
        }
        return UpdateInfo(
            latestVersion = cleanVersion,
            downloadUrl = apkUrl ?: releaseUrl,
            releaseUrl = releaseUrl,
        )
    }

    /** Blocking network call — invoke from Dispatchers.IO. Returns UpdateInfo if newer, else null. */
    fun checkLatest(currentVersion: String): UpdateInfo? {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WP2-Sideloader/$currentVersion")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseReleaseJson(body, currentVersion)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}
