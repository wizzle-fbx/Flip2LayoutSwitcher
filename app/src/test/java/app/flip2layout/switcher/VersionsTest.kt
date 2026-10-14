package app.flip2layout.switcher

import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    @Test fun numericPartsCompareAsNumbers() {
        assertTrue(Versions.compare("0.10.0", "0.9.0") > 0)
        assertTrue(Versions.compare("v1.0.0", "0.9.9") > 0)
        assertEquals(0, Versions.compare("v0.3.0-beta", "0.3.0-beta"))
    }
    @Test fun releaseIsNewerThanItsPreRelease() {
        assertTrue(Versions.compare("0.3.0", "0.3.0-beta") > 0)
        assertTrue(Versions.compare("0.3.0-beta.2", "0.3.0-beta.1") > 0)
        assertTrue(Versions.compare("0.3.0-rc", "0.3.0-beta") > 0)
        assertTrue(Versions.compare("0.4.0-beta", "0.3.0") > 0)
    }
    @Test fun newestSkipsDraftsAndReleasesWithoutApk() {
        val json = """[
            {"tag_name":"v0.5.0","draft":true,"assets":[{"name":"a.apk","browser_download_url":"d"}]},
            {"tag_name":"v0.4.0-beta","draft":false,"body":"notes","assets":[{"name":"Flip2LayoutSwitcher-0.4.0-beta.apk","browser_download_url":"u4"}]},
            {"tag_name":"v0.4.1","draft":false,"assets":[{"name":"source.zip","browser_download_url":"z"}]},
            {"tag_name":"v0.3.0-beta","draft":false,"assets":[{"name":"x.apk","browser_download_url":"u3"}]}
        ]"""
        val newest = Versions.newest(json)!!
        assertEquals("v0.4.0-beta", newest.version)
        assertEquals("u4", newest.apkUrl)
        assertEquals("notes", newest.notes)
        assertNull(Versions.newest("[]"))
    }
}
