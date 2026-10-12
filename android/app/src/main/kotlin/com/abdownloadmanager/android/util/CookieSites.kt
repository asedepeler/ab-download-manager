package com.abdownloadmanager.android.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * cookies are only sent with the downloads of the websites that the user has chosen
 */
object CookieSites {
    private val separators = Regex("""[\s,;]+""")

    /**
     * @param sites what the user typed, like "gofile.io a.com, https://www.b.com/"
     */
    fun parse(sites: String): List<String> {
        return sites.split(separators).mapNotNull { site ->
            site.lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .removePrefix("*.")
                .removePrefix("www.")
                .trim('.')
                .takeIf { it.isNotEmpty() }
        }
    }

    /**
     * @return true if [url] belongs to one of the [sites] or to one of their subdomains
     */
    fun allows(sites: String, url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        return parse(sites).any { host == it || host.endsWith(".$it") }
    }
}
