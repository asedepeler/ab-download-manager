package com.abdownloadmanager.android.util.gofile

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class GofileResult(
    // direct links with the cookie that gofile requires
    val files: List<HttpDownloadCredentials>,
    // files that gofile doesn't serve to free accounts at the moment
    val unavailableCount: Int,
)

/**
 * gofile signs its api requests with a script that changes often,
 * so we let the page do its own request in a hidden webview and repeat that request for each page of the folder
 * shortcut: files in sub folders and password protected folders are not resolved, support them when needed
 */
object GofileResolver {
    private const val CONTENTS_API = "https://api.gofile.io/contents/"
    private const val SITE = "https://gofile.io"
    private val tokenInCookie = Regex("""accountToken=([A-Za-z0-9]+)""")

    // 100 files per page
    private const val MAX_PAGES = 50

    /**
     * @param pageLink see [gofilePageOf]
     * @return the files of this gofile page, or null if they can't be resolved
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context,
        pageLink: String,
        client: OkHttpClient,
    ): GofileResult? {
        return withTimeoutOrNull(40.seconds) {
            withContext(Dispatchers.Main) {
                val result = CompletableDeferred<GofileResult?>()
                val handled = AtomicBoolean(false)
                val webView = WebView(context)
                try {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    val userAgent = webView.settings.userAgentString
                    webView.webViewClient = object : WebViewClient() {
                        // this is called in a background thread
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (
                                request.method == "GET"
                                && url.startsWith(CONTENTS_API)
                                && handled.compareAndSet(false, true)
                            ) {
                                result.complete(
                                    runCatching {
                                        fetchAllPages(client, url, request.requestHeaders, userAgent, pageLink)
                                    }.onFailure { it.printStackTrace() }.getOrNull()
                                )
                            }
                            return null
                        }
                    }
                    webView.loadUrl(pageLink)
                    result.await()
                } finally {
                    webView.destroy()
                }
            }
        }
    }

    private fun fetchAllPages(
        client: OkHttpClient,
        firstPageUrl: String,
        headers: Map<String, String>,
        userAgent: String,
        pageLink: String,
    ): GofileResult? {
        // the page sends the token of its account in this header, and keeps it in a cookie too
        val token = headers.entries
            .firstOrNull { it.key.equals("Authorization", true) }
            ?.value?.removePrefix("Bearer")?.trim()?.takeIf { it.isNotEmpty() }
            ?: CookieManager.getInstance().getCookie(SITE)
                ?.let { tokenInCookie.find(it) }
                ?.groupValues?.get(1)
            ?: return null
        val files = mutableListOf<GofileFile>()
        var unavailableCount = 0
        var page = 1
        while (true) {
            val request = Request.Builder()
                .url(
                    firstPageUrl.toHttpUrl().newBuilder()
                        .setQueryParameter("page", page.toString())
                        .build()
                )
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .apply {
                    headers.forEach { (name, value) ->
                        // let okhttp handle the compression
                        if (!name.equals("Accept-Encoding", true)) {
                            header(name, value)
                        }
                    }
                }
                .build()
            val body = client.newCall(request).execute().use { it.body.string() }
            // a missing page must fail the whole thing instead of silently dropping files
            val parsed = parseGofileContents(body) ?: return null
            files += parsed.files
            unavailableCount += parsed.unavailableCount
            if (page >= parsed.totalPages || page >= MAX_PAGES) break
            page++
        }
        return GofileResult(
            files = files.map {
                HttpDownloadCredentials(
                    link = it.link,
                    // gofile redirects to an html page without the cookie of an account that opened this page
                    headers = mapOf("Cookie" to "accountToken=$token"),
                    downloadPage = pageLink,
                )
            },
            unavailableCount = unavailableCount,
        )
    }
}
