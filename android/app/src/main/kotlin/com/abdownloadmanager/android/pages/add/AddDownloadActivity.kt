package com.abdownloadmanager.android.pages.add

import android.content.Intent
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import arrow.core.firstOrNone
import arrow.core.getOrElse
import com.abdownloadmanager.android.pages.add.multiple.AddMultiDownloadActivity
import com.abdownloadmanager.android.pages.add.single.AddSingleDownloadActivity
import com.abdownloadmanager.android.pages.onboarding.permissions.PermissionManager
import com.abdownloadmanager.android.ui.MainActivity
import com.abdownloadmanager.android.util.BrowserHeaders
import com.abdownloadmanager.android.util.activity.ABDMActivity
import com.abdownloadmanager.android.util.gofile.GofileResolver
import com.abdownloadmanager.android.util.gofile.gofilePageOf
import com.abdownloadmanager.android.util.gofile.isGofileFileLink
import com.abdownloadmanager.android.util.remote.PcRemote
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadConfig
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.util.extractors.linkextractor.DefaultDownloadCredentialsExtractor
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.core.component.inject

class AddDownloadActivity : ABDMActivity() {
    val json: Json by inject()
    val permissionManager: PermissionManager by inject()
    private val pcRemote: PcRemote by inject()
    private val okHttpClient: OkHttpClient by inject()
    private val scope = MainScope()
    private fun createDownloaderInUiProps(
        credentials: IDownloadCredentials
    ): AddDownloadCredentialsInUiProps {
        return AddDownloadCredentialsInUiProps(
            credentials,
            AddDownloadCredentialsInUiProps.Configs(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!permissionManager.isReady()) {
            // user not opened the app at least once. we must redirect it to the permission page first
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        val credentialsFromIntent = getDownloadCredentialsFromIntent(intent)
        scope.launch {
            val credentials = prepareCredentials(credentialsFromIntent) ?: return@launch
            if (credentials.isNotEmpty() && pcRemote.isConfigured()) {
                askWhereToDownload(credentials)
            } else {
                openAddDownloadPage(credentials)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showErrorAndFinish(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Can't download from gofile")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * like the browser extension does, a link is sent with the cookies that the browser of this app has for it
     */
    private fun withBrowserCookies(credentials: IDownloadCredentials): IDownloadCredentials {
        if (credentials !is HttpDownloadCredentials || credentials.headers != null) {
            return credentials
        }
        val headers = BrowserHeaders.of(this, credentials.link) ?: return credentials
        return credentials.copy(headers = headers)
    }

    /**
     * adds the cookies that the links need
     * gofile only serves a file to the browser that has opened its page, so that page is opened in the background first
     * a gofile link is never passed as is, because gofile answers it with an html page
     * @return null if a gofile link can't be downloaded, the reason is shown to the user
     */
    private suspend fun prepareCredentials(credentials: List<IDownloadCredentials>): List<IDownloadCredentials>? {
        if (credentials.none { gofilePageOf(it.link) != null }) {
            return credentials.map(::withBrowserCookies)
        }
        toast("Reading gofile links...")
        var unavailableCount = 0
        val resolved = credentials.flatMap {
            val page = gofilePageOf(it.link) ?: return@flatMap listOf(withBrowserCookies(it))
            if (isGofileFileLink(it.link)) {
                val fileLink = it.link.trim()
                val headers = GofileResolver.headersOfFile(this, fileLink, page, okHttpClient)
                if (headers == null) {
                    showErrorAndFinish(
                        "gofile doesn't serve this file right now. Its server may be busy " +
                                "(gofile pauses free downloads then), or the file was removed. Try again later."
                    )
                    return null
                }
                return@flatMap listOf(
                    HttpDownloadCredentials(link = fileLink, headers = headers, downloadPage = page)
                )
            }
            val result = GofileResolver.listFiles(this, page, okHttpClient)
            if (result == null) {
                showErrorAndFinish(
                    "Can't read $page. It may be removed, private or password protected, " +
                            "or there is no internet connection."
                )
                return null
            }
            unavailableCount += result.unavailableCount
            result.files
        }
        if (resolved.isEmpty()) {
            showErrorAndFinish(
                if (unavailableCount > 0) {
                    "gofile paused the free downloads of this file because its server is busy. Try again later."
                } else {
                    "No files found in this gofile link. Files inside sub folders are not supported."
                }
            )
            return null
        }
        if (unavailableCount > 0) {
            toast("$unavailableCount file(s) skipped, gofile paused their free downloads for now")
        }
        return resolved
    }

    private fun askWhereToDownload(credentials: List<IDownloadCredentials>) {
        AlertDialog.Builder(this)
            .setTitle("Download ${credentials.size} file(s) on")
            .setItems(arrayOf("This phone", "PC")) { _, which ->
                if (which == 0) {
                    openAddDownloadPage(credentials)
                } else {
                    sendToPc(credentials)
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun sendToPc(credentials: List<IDownloadCredentials>) {
        scope.launch {
            val error = try {
                pcRemote.sendDownloads(credentials)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pcRemote.describeError(e)
            }
            if (error == null) {
                toast("Sent to PC")
                finish()
                return@launch
            }
            AlertDialog.Builder(this@AddDownloadActivity)
                .setTitle("Can't send to PC")
                .setMessage(error)
                .setPositiveButton("Download on this phone") { _, _ -> openAddDownloadPage(credentials) }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun openAddDownloadPage(credentials: List<IDownloadCredentials>) {
        val intent = if (credentials.size > 1) {
            AddMultiDownloadActivity.createIntent(
                this,
                AddDownloadConfig.MultipleAddConfig(
                    newDownloads = credentials.map(::createDownloaderInUiProps),
                    importOptions = ImportOptions(),
                ),
                json = json,
            )
        } else {
            AddSingleDownloadActivity.createIntent(
                this,
                AddDownloadConfig.SingleAddConfig(
                    newDownload = credentials
                        .firstOrNone()
                        .getOrElse { HttpDownloadCredentials("") }
                        .let(::createDownloaderInUiProps),
                    importOptions = ImportOptions(),
                ),
                json = json,
            )
        }
        startActivity(intent)
        finish()
    }

    private fun getDownloadCredentialsFromIntent(intent: Intent): List<IDownloadCredentials> {
        val links = when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            }

            else -> {
                // action view etc...
                intent.data?.toString().orEmpty()
            }
        }
        return DefaultDownloadCredentialsExtractor
            .extract(links)
            .distinctBy { it.link }
    }
}
