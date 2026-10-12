package com.abdownloadmanager.android.util.gofile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GofileFile(
    val link: String,
    val name: String,
)

data class GofilePage(
    val files: List<GofileFile>,
    val totalPages: Int,
)

private val gofileContentLink = Regex("""https?://(www\.)?gofile\.io/d/[\w-]+/?([?#].*)?""")

fun isGofileContentLink(link: String): Boolean {
    return gofileContentLink.matches(link.trim())
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

private fun JsonElement.toGofileFileOrNull(): GofileFile? {
    val item = this as? JsonObject ?: return null
    if (item.string("type") != "file") return null
    return GofileFile(
        link = item.string("link") ?: return null,
        name = item.string("name").orEmpty(),
    )
}

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
        val files = when (data.string("type")) {
            "file" -> listOfNotNull(data.toGofileFileOrNull())
            else -> when (val children = data["children"]) {
                is JsonObject -> children.values
                is JsonArray -> children
                else -> emptyList()
            }.mapNotNull { it.toGofileFileOrNull() }
        }
        GofilePage(
            files = files,
            totalPages = (root["metadata"] as? JsonObject)?.get("totalPages")?.jsonPrimitive?.int ?: 1,
        )
    }.getOrNull()
}
