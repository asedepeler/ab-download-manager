package com.abdownloadmanager.android.util.gofile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GofileContentsTest {
    @Test
    fun parsesFilesOfFolderAndSkipsSubFolders() {
        val page = parseGofileContents(
            """
            {"status":"ok","data":{"type":"folder","children":{
              "a":{"type":"file","name":"a.mp4","link":"https://store1.gofile.io/download/web/a/a.mp4"},
              "b":{"type":"folder","name":"sub"},
              "c":{"type":"file","name":"c.zip","link":"https://store1.gofile.io/download/web/c/c.zip"}
            }},"metadata":{"totalPages":3}}
            """
        )
        assertEquals(
            listOf(
                GofileFile("https://store1.gofile.io/download/web/a/a.mp4", "a.mp4"),
                GofileFile("https://store1.gofile.io/download/web/c/c.zip", "c.zip"),
            ),
            page?.files,
        )
        assertEquals(3, page?.totalPages)
    }

    @Test
    fun parsesSingleFile() {
        val page = parseGofileContents(
            """{"status":"ok","data":{"type":"file","name":"a.mp4","link":"https://x/a.mp4"}}"""
        )
        assertEquals(listOf(GofileFile("https://x/a.mp4", "a.mp4")), page?.files)
        assertEquals(1, page?.totalPages)
        assertEquals(0, page?.unavailableCount)
    }

    @Test
    fun failedResponseIsNull() {
        assertNull(parseGofileContents("""{"status":"error-notFound","data":{}}"""))
        assertNull(parseGofileContents("<html>"))
    }

    @Test
    fun skipsFilesThatAreNotServedToFreeAccounts() {
        val page = parseGofileContents(
            """
            {"status":"ok","data":{"type":"folder","children":{
              "a":{"type":"file","name":"a.mp4","link":"https://x/a.mp4","overloaded":true},
              "b":{"type":"file","name":"b.mp4","link":"https://x/b.mp4","isFrozen":true},
              "c":{"type":"file","name":"c.mp4","link":"https://x/c.mp4","overloaded":false}
            }},"metadata":{}}
            """
        )
        assertEquals(listOf(GofileFile("https://x/c.mp4", "c.mp4")), page?.files)
        assertEquals(2, page?.unavailableCount)
    }

    @Test
    fun pageLinksAreKept() {
        assertEquals("https://gofile.io/d/AbC123", gofilePageOf("https://gofile.io/d/AbC123"))
        assertEquals("https://www.gofile.io/d/AbC123/", gofilePageOf(" https://www.gofile.io/d/AbC123/ "))
    }

    @Test
    fun directLinkOfFileIsMappedToItsPage() {
        val page = "https://gofile.io/d/6fcdb3a1-2bf4-4d3d-8ee8-aa7fac399819"
        assertEquals(
            page,
            gofilePageOf("https://cold-eu-par-2.gofile.io/download/web/6fcdb3a1-2bf4-4d3d-8ee8-aa7fac399819/A%20b%20-%20c.mp4"),
        )
        assertEquals(
            page,
            gofilePageOf("https://store1.gofile.io/download/6fcdb3a1-2bf4-4d3d-8ee8-aa7fac399819/a.zip"),
        )
    }

    @Test
    fun otherLinksAreNotGofileLinks() {
        assertNull(gofilePageOf("https://gofile.io/premium"))
        assertNull(gofilePageOf("https://example.com/download/web/6fcdb3a1-2bf4-4d3d-8ee8-aa7fac399819/a.mp4"))
        assertNull(gofilePageOf("https://store1.gofile.io/download/web/not-an-id/a.mp4"))
    }
}
