package com.abdownloadmanager.android.util.gofile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    }

    @Test
    fun failedResponseIsNull() {
        assertNull(parseGofileContents("""{"status":"error-notFound","data":{}}"""))
        assertNull(parseGofileContents("<html>"))
    }

    @Test
    fun detectsContentLinks() {
        assertTrue(isGofileContentLink("https://gofile.io/d/AbC123"))
        assertTrue(isGofileContentLink(" https://www.gofile.io/d/AbC123/ "))
        assertFalse(isGofileContentLink("https://store1.gofile.io/download/web/a/a.mp4"))
        assertFalse(isGofileContentLink("https://gofile.io/premium"))
    }
}
