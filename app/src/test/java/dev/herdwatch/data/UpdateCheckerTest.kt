package dev.herdwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release document is the only place Herdly talks to a service it does not control, so the
 * parser is pinned against the shapes GitHub actually returns (escaped notes, extra keys,
 * assets in any order, missing release).
 */
class UpdateCheckerTest {

    private val payload = """
        {
          "url": "https://api.github.com/repos/1660745802/herdly/releases/1",
          "tag_name": "v0.2.0",
          "name": "Herdly 0.2.0",
          "published_at": "2026-11-20T09:00:00Z",
          "html_url": "https://github.com/1660745802/herdly/releases/tag/v0.2.0",
          "body": "第二个公开版本\n- 终端支持横向滚动\n- 设置页可检查更新 \"安静\"",
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
            {"name": "herdly-0.2.0.apk", "browser_download_url": "https://example.com/herdly-0.2.0.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads tag, page, notes and apk asset`() {
        val release = ReleaseParser.parse(payload)!!
        assertEquals("0.2.0", release.version)
        assertEquals("https://github.com/1660745802/herdly/releases/tag/v0.2.0", release.pageUrl)
        assertEquals("https://example.com/herdly-0.2.0.apk", release.apkUrl)
        assertTrue(release.notes.contains("横向滚动"))
        assertTrue(release.notes.contains("第二个公开版本\n"))
        assertTrue("quotes survive decoding", release.notes.contains("\"安静\""))
    }

    @Test
    fun `release without an apk asset yields no download url`() {
        val asset = "{\"name\": \"notes.txt\", \"browser_download_url\": \"https://example.com/notes.txt\"}"
        val noApk = "{\"tag_name\": \"v0.2.0\", \"assets\": [$asset]}"
        assertNull(ReleaseParser.parse(noApk)!!.apkUrl)
    }

    @Test
    fun `missing tag is treated as no release`() {
        assertNull(ReleaseParser.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun `version comparison is numeric not lexical`() {
        assertTrue(ReleaseParser.isNewer("0.10.0", "0.9.0"))
        assertTrue(ReleaseParser.isNewer("1.0.0", "0.9.9"))
        assertTrue(ReleaseParser.isNewer("0.2.1", "0.2"))
        assertFalse(ReleaseParser.isNewer("0.2.0", "0.2.0"))
        assertFalse(ReleaseParser.isNewer("0.1.9", "0.2.0"))
        assertFalse("v prefix tolerated", ReleaseParser.isNewer("v0.2.0", "0.2.0"))
        assertTrue("build suffix must not hide a newer tag", ReleaseParser.isNewer("0.2.0", "0.1.0"))
    }

    @Test
    fun `prerelease sorts below its release`() {
        assertFalse(ReleaseParser.isNewer("0.2.0-rc1", "0.2.0"))
        assertTrue(ReleaseParser.isNewer("0.2.0", "0.2.0-rc1"))
    }

    @Test
    fun `unparseable local version accepts any published release`() {
        assertTrue(ReleaseParser.isNewer("0.2.0", ""))
    }
}
