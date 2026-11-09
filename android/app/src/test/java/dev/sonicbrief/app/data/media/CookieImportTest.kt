package dev.sonicbrief.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieImportTest {
    private fun line(domain: String, name: String) = listOf(domain, "TRUE", "/", "TRUE", "0", name, "v").joinToString("\t")

    @Test
    fun keepsOnlyAllowedDomains() {
        val raw = listOf(
            "# Netscape HTTP Cookie File",
            line(".youtube.com", "SID"),
            line(".bilibili.com", "SESSDATA"),
            line(".example.com", "tracker"),
            line("accounts.google.com", "LSID"),
            "#HttpOnly_" + line(".youtube.com", "HSID"),
        ).joinToString("\n")
        val filtered = CookieImport.filter(raw)!!
        assertTrue(filtered.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(filtered.contains("SID") && filtered.contains("SESSDATA") && filtered.contains("LSID") && filtered.contains("HSID"))
        assertTrue(!filtered.contains("tracker"))
        assertEquals(5, filtered.trim().lines().size)
    }

    @Test
    fun rejectsLookalikeDomainsAndEmptyResults() {
        assertNull(CookieImport.filter(line(".notyoutube.com", "x") + "\n" + line("evil-google.com", "y")))
        assertNull(CookieImport.filter("garbage"))
        assertNotNull(CookieImport.filter(line("youtube.com", "ok")))
    }
}
