package com.abdownloadmanager.android.util.gofile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GofileFile(
    val link: String,
    val name: String,
)

data class GofilePage(
    val files: List<GofileFile>,
    // files that gofile doesn't serve to free accounts at the moment (busy server or cold storage)
    val unavailableCount: Int,
    val totalPages: Int,
)

private val gofilePageLink = Regex("""https?://(www\.)?gofile\.io/d/[\w-]+/?([?#].*)?""")

// like https://store1.gofile.io/download/web/{file id}/{file name}
private val gofileFileLink = Regex(
    """https?://[\w-]+\.gofile\.io/download/(?:\w+/)?([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})/.*"""
)

/**
 * @return true if it is a direct link of a single gofile file
 */
fun isGofileFileLink(link: String): Boolean {
    return gofileFileLink.matches(link.trim())
}

/**
 * a direct link of a gofile file only works with the cookie of an account that has opened its page,
 * without it gofile redirects to an html page
 * @return the gofile page that lists the file(s) of this link, or null if it is not a gofile link
 */
fun gofilePageOf(link: String): String? {
    val trimmed = link.trim()
    if (gofilePageLink.matches(trimmed)) {
        return trimmed
    }
    val fileId = gofileFileLink.matchEntire(trimmed)?.groupValues?.get(1) ?: return null
    return "https://gofile.io/d/$fileId"
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

private fun JsonObject.isTrue(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/**
 * parses the response of `GET api.gofile.io/contents/{id}`
 * sub folders are skipped
 * @return null if the response is not a successful one
 */
fun parseGofileContents(body: String): GofilePage? {
    return runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        if (root.string("status") != "ok") return null
        val data = root.getValue("data").jsonObject
        val items = when (data.string("type")) {
            "file" -> listOf(data)
            else -> when (val children = data["children"]) {
                is JsonObject -> children.values
                is JsonArray -> children
                else -> emptyList()
            }.filterIsInstance<JsonObject>()
        }.filter { it.string("type") == "file" }
        val (unavailable, available) = items.partition { it.isTrue("overloaded") || it.isTrue("isFrozen") }
        GofilePage(
            files = available.mapNotNull { item ->
                item.string("link")?.let { GofileFile(link = it, name = item.string("name").orEmpty()) }
            },
            unavailableCount = unavailable.size,
            totalPages = (root["metadata"] as? JsonObject)?.get("totalPages")?.jsonPrimitive?.int ?: 1,
        )
    }.getOrNull()
}
