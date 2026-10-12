package com.abdownloadmanager.android.util

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings

/**
 * like the browser extension does, a link is downloaded with the cookies that the browser has for it
 * here the browser is the webview of this app
 */
object BrowserHeaders {
    /**
     * @return null if the browser has no cookie for this url
     */
    fun of(context: Context, url: String): Map<String, String>? {
        return runCatching {
            val cookie = CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            mapOf(
                "Cookie" to cookie,
                "User-Agent" to WebSettings.getDefaultUserAgent(context),
            )
        }.getOrNull()
    }
}
