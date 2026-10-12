package com.abdownloadmanager.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieSitesTest {
    @Test
    fun parsesWhatUsersType() {
        assertEquals(
            listOf("gofile.io", "a.com", "b.com", "c.com", "d.com"),
            CookieSites.parse(" gofile.io a.com,https://www.b.com/path;*.c.com\nD.com:8080 "),
        )
        assertEquals(emptyList(), CookieSites.parse("  "))
    }

    @Test
    fun allowsTheSiteAndItsSubdomains() {
        val sites = "gofile.io a.com"
        assertTrue(CookieSites.allows(sites, "https://gofile.io/d/abc"))
        assertTrue(CookieSites.allows(sites, "https://cold-eu-par-2.gofile.io/download/web/id/a.mp4"))
        assertTrue(CookieSites.allows(sites, "http://A.com/file.zip"))
    }

    @Test
    fun refusesOtherSites() {
        val sites = "gofile.io a.com"
        assertFalse(CookieSites.allows(sites, "https://b.com/file.zip"))
        // not a subdomain, only ends with the same text
        assertFalse(CookieSites.allows(sites, "https://notgofile.io/file.zip"))
        assertFalse(CookieSites.allows(sites, "https://gofile.io.evil.com/file.zip"))
        assertFalse(CookieSites.allows("", "https://gofile.io/d/abc"))
        assertFalse(CookieSites.allows(sites, "not a link"))
    }
}
