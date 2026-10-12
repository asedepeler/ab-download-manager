package com.abdownloadmanager.android.util.gofile

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.abdownloadmanager.android.util.BrowserHeaders
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

class GofileResult(
    // direct links with the cookie that gofile requires
    val files: List<HttpDownloadCredentials>,
    // files that gofile doesn't serve to free accounts at the moment
    val unavailableCount: Int,
)

/**
 * gofile only serves a file to the browser that has opened the page of that file (it is known by its cookie),
 * otherwise it redirects to an html page
 * so we open the page in a hidden webview and then download the file with the cookies of that webview
 * shortcut: files in sub folders and password protected folders are not resolved, support them when needed
 */
object GofileResolver {
    private const val CONTENTS_API = "https://api.gofile.io/contents/"
    private const val SITE = "https://gofile.io"
    private val tokenInCookie = Regex("""accountToken=([A-Za-z0-9]+)""")

    // 100 files per page
    private const val MAX_PAGES = 50

    // the request that the page sends to list its contents
    private class ContentsRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * keeps a gofile page open in a hidden webview while [block] is running
     * @return null if [block] doesn't finish in time
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun <T> withPage(
        context: Context,
        pageLink: String,
        block: suspend (contentsRequest: Deferred<ContentsRequest>) -> T,
    ): T? {
        return withTimeoutOrNull(40.seconds) {
            withContext(Dispatchers.Main) {
                val contentsRequest = CompletableDeferred<ContentsRequest>()
                val webView = WebView(context)
                try {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.webViewClient = object : WebViewClient() {
                        // this is called in a background thread
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (request.method == "GET" && url.startsWith(CONTENTS_API)) {
                                contentsRequest.complete(
                                    ContentsRequest(url, request.requestHeaders.orEmpty().toMap())
                                )
                            }
                            return null
                        }
                    }
                    webView.loadUrl(pageLink)
                    block(contentsRequest)
                } finally {
                    webView.destroy()
                }
            }
        }
    }

    /**
     * for a direct link of a file
     * @param pageLink see [gofilePageOf]
     * @return the headers (the cookies of the browser) that make gofile serve this file,
     * or null if gofile doesn't serve it at the moment
     */
    suspend fun headersOfFile(
        context: Context,
        fileLink: String,
        pageLink: String,
        client: OkHttpClient,
    ): Map<String, String>? {
        val probeClient = client.newBuilder()
            .followRedirects(false)
            .build()
        return withPage(context, pageLink) {
            awaitServed(context, probeClient, fileLink)
        }
    }

    /**
     * we don't guess when the page is ready, we ask gofile until it really serves the file with our cookies
     */
    private suspend fun awaitServed(
        context: Context,
        probeClient: OkHttpClient,
        fileLink: String,
    ): Map<String, String> {
        while (true) {
            val headers = BrowserHeaders.of(context, fileLink)
            if (headers != null && withContext(Dispatchers.IO) { isServed(probeClient, fileLink, headers) }) {
                return headers
            }
            delay(1.seconds)
        }
    }

    private fun isServed(
        probeClient: OkHttpClient,
        fileLink: String,
        headers: Map<String, String>,
    ): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(fileLink)
                // we only want to know the kind of the response
                .header("Range", "bytes=0-0")
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }
                .build()
            probeClient.newCall(request).execute().use {
                it.isSuccessful && it.header("Content-Type")?.startsWith("text/html", ignoreCase = true) != true
            }
        }.getOrDefault(false)
    }

    /**
     * for a link of a gofile page
     * the page signs its api requests with a script that changes often,
     * so we repeat the request that the page itself sends, for each page of the folder
     * @return the files of this gofile page, or null if they can't be listed
     */
    suspend fun listFiles(
        context: Context,
        pageLink: String,
        client: OkHttpClient,
    ): GofileResult? {
        return withPage(context, pageLink) { contentsRequest ->
            val request = contentsRequest.await()
            val userAgent = WebSettings.getDefaultUserAgent(context)
            withContext(Dispatchers.IO) {
                runCatching {
                    fetchAllPages(client, request, userAgent, pageLink)
                }.onFailure { it.printStackTrace() }.getOrNull()
            }
        }
    }

    private fun fetchAllPages(
        client: OkHttpClient,
        firstPage: ContentsRequest,
        userAgent: String,
        pageLink: String,
    ): GofileResult? {
        // the page sends the token of its account in this header, and keeps it in a cookie too
        val token = firstPage.headers.entries
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
                    firstPage.url.toHttpUrl().newBuilder()
                        .setQueryParameter("page", page.toString())
                        .build()
                )
                // gofile refuses the request if this is not the one of the page
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .apply {
                    firstPage.headers.forEach { (name, value) ->
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
                    headers = mapOf(
                        "Cookie" to "accountToken=$token",
                        "User-Agent" to userAgent,
                    ),
                    downloadPage = pageLink,
                )
            },
            unavailableCount = unavailableCount,
        )
    }
}
