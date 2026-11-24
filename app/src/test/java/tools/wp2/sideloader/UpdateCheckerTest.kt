package tools.wp2.sideloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun semverComparisonWorks() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.2", "1.0.3"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.2", "v1.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.9", "1.0.10"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.2", "2.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.2", "1.0.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.2", "v1.0.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.0", "1.1.9"))
    }

    @Test
    fun parsesGitHubReleaseJsonAndFindsApkAsset() {
        val sample = """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/MrMontic/wp2-sideloader/releases/tag/v1.0.5",
              "assets": [
                {
                  "name": "WP2-Sideloader.apk",
                  "browser_download_url": "https://github.com/MrMontic/wp2-sideloader/releases/download/v1.0.5/WP2-Sideloader.apk"
                }
              ]
            }
        """.trimIndent()
        val info = assertNotNull(UpdateChecker.parseReleaseJson(sample, "1.0.2"))
        assertEquals("1.0.5", info.latestVersion)
        assertEquals(
            "https://github.com/MrMontic/wp2-sideloader/releases/download/v1.0.5/WP2-Sideloader.apk",
            info.downloadUrl,
        )
        assertNull(UpdateChecker.parseReleaseJson(sample, "1.0.5"))
        assertNull(UpdateChecker.parseReleaseJson(sample, "1.1.0"))
    }
}
