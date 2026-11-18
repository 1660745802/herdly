package dev.herdwatch.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks GitHub Releases for a newer build.
 *
 * Deliberately dependency-free: the project has no JSON library and pulling one in for four
 * fields would be a poor trade. The parser below is a strict, escape-aware reader for a single
 * key, not a general JSON parser — it is unit tested against real payload shapes (escaped
 * newlines in release notes, extra keys, absent assets).
 *
 * The check is manual and opt-in. Herdly makes exactly one outbound request when the user asks,
 * never at launch, and never sends anything about the device or the hosts.
 */
class UpdateChecker(
    private val currentVersion: String,
    private val fetch: suspend () -> String = ::fetchLatestRelease,
) {

    sealed interface Result {
        /** The published release is newer than this build. */
        data class Newer(val release: Release) : Result

        /** This build is current (or ahead, e.g. a local build). */
        data class Current(val version: String) : Result

        /** The check itself failed; [reason] is already user-facing. */
        data class Failed(val reason: String) : Result
    }

    data class Release(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
        val publishedAt: String,
    )

    suspend fun check(): Result {
        val body = try {
            fetch()
        } catch (e: IOException) {
            return Result.Failed(e.message ?: "network")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "network")
        }
        val release = ReleaseParser.parse(body)
            ?: return Result.Failed("no release")
        return if (ReleaseParser.isNewer(release.version, currentVersion)) {
            Result.Newer(release)
        } else {
            Result.Current(currentVersion)
        }
    }

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/1660745802/herdly/releases/latest"
        private const val TIMEOUT_MS = 12_000

        /**
         * Fetches the latest release document. Uses the platform HTTP stack so proxied or
         * corporate networks behave the same as the SSH transport does.
         */
        suspend fun fetchLatestRelease(): String = withContext(Dispatchers.IO) {
            val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Herdly")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) throw IOException("no release")
                if (code == 403) throw IOException("rate limited")
                if (code !in 200..299) throw IOException("http $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * Just enough JSON to read the four fields of a release document.
 *
 * A full parser would be more code than the feature it serves, and a regex over the whole body
 * breaks as soon as release notes contain quotes — which they do. This scans for a key at object
 * level and then decodes one JSON string properly, escapes included.
 */
internal object ReleaseParser {

    fun parse(json: String): UpdateChecker.Release? {
        val version = stringValue(json, "tag_name")?.removePrefix("v").orEmpty().ifBlank { return null }
        val pageUrl = stringValue(json, "html_url").orEmpty()
        val notes = stringValue(json, "body").orEmpty()
        val published = stringValue(json, "published_at").orEmpty()
        val apk = firstApkAssetUrl(json)
        return UpdateChecker.Release(
            version = version,
            notes = notes,
            pageUrl = pageUrl,
            apkUrl = apk,
            publishedAt = published,
        )
    }

    /** Reads the first JSON string value for [key], or null when the key is absent. */
    fun stringValue(json: String, key: String): String? {
        var index = 0
        val needle = "\"$key\""
        while (true) {
            val at = json.indexOf(needle, index)
            if (at < 0) return null
            index = at + needle.length
            var cursor = index
            while (cursor < json.length && json[cursor].isWhitespace()) cursor++
            if (cursor >= json.length || json[cursor] != ':') continue
            cursor++
            while (cursor < json.length && json[cursor].isWhitespace()) cursor++
            if (cursor >= json.length || json[cursor] != '"') continue
            return readString(json, cursor)
        }
    }

    /** Decodes a JSON string starting at the opening quote [start]. */
    private fun readString(json: String, start: Int): String? {
        val out = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            when (val c = json[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    i++
                    if (i >= json.length) return null
                    when (val e = json[i]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 >= json.length) return null
                            val hex = json.substring(i + 1, i + 5)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        '"', '\\', '/' -> out.append(e)
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return null
    }

    /** First `.apk` download URL among the release assets. */
    private fun firstApkAssetUrl(json: String): String? {
        var index = 0
        while (true) {
            val url = stringValue(json.substring(index.coerceAtMost(json.length)), "browser_download_url")
                ?: return null
            if (url.endsWith(".apk", ignoreCase = true)) return url
            val at = json.indexOf(url, index)
            if (at < 0) return null
            index = at + url.length
        }
    }

    /**
     * Compares dotted versions numerically. A pre-release tag (`1.2.0-rc1`) sorts below its
     * release, and a build without a version sorts lowest so a fresh install never nags.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return remote.isNotBlank()
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Equal numbers: the pre-release suffix decides, and a plain release wins.
        val remotePre = remote.contains('-')
        val localPre = local.contains('-')
        return !remotePre && localPre
    }

    private fun parseVersion(value: String): List<Int>? {
        val core = value.removePrefix("v").substringBefore('-').substringBefore('+')
        if (core.isBlank()) return null
        val parts = core.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: return null }
        return if (parts.isEmpty()) null else parts
    }
}
