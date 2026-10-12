package com.abdownloadmanager.android.util.gofile

import android.annotation.SuppressLint
import android.content.Context
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

/**
 * gofile signs its api requests with a script that changes often,
 * so we let the page do its own request in a hidden webview and repeat that request for each page of the folder
 * shortcut: files in sub folders and password protected folders are not resolved, support them when needed
 */
object GofileResolver {
    private const val CONTENTS_API = "https://api.gofile.io/contents/"

    // 100 files per page
    private const val MAX_PAGES = 50

    /**
     * @return direct links of the files in this gofile link, or null if they can't be resolved
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context,
        link: String,
        client: OkHttpClient,
    ): List<HttpDownloadCredentials>? {
        return withTimeoutOrNull(40.seconds) {
            withContext(Dispatchers.Main) {
                val result = CompletableDeferred<List<HttpDownloadCredentials>?>()
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
                                        fetchAllPages(client, url, request.requestHeaders, userAgent, link)
                                    }.onFailure { it.printStackTrace() }.getOrNull()
                                )
                            }
                            return null
                        }
                    }
                    webView.loadUrl(link)
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
    ): List<HttpDownloadCredentials>? {
        val token = headers.entries
            .firstOrNull { it.key.equals("Authorization", true) }
            ?.value?.removePrefix("Bearer")?.trim()
            ?: return null
        val files = mutableListOf<GofileFile>()
        var page = 1
        while (true) {
            val request = Request.Builder()
                .url(
                    firstPageUrl.toHttpUrl().newBuilder()
                        .setQueryParameter("page", page.toString())
                        .build()
                )
                .header("User-Agent", userAgent)
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
            if (page >= parsed.totalPages || page >= MAX_PAGES) break
            page++
        }
        return files.map {
            HttpDownloadCredentials(
                link = it.link,
                headers = mapOf("Cookie" to "accountToken=$token"),
                downloadPage = pageLink,
            )
        }
    }
}
