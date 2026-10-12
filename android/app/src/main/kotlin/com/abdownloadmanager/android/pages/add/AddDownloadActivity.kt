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
import com.abdownloadmanager.android.util.activity.ABDMActivity
import com.abdownloadmanager.android.util.gofile.GofileResolver
import com.abdownloadmanager.android.util.gofile.gofilePageOf
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
            val credentials = resolveGofileLinks(credentialsFromIntent) ?: return@launch
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
     * gofile links (a page, or a direct link of a file) are replaced by direct links that carry the cookie they need
     * a gofile link is never passed as is, because gofile answers it with an html page
     * @return null if a gofile link can't be downloaded, the reason is shown to the user
     */
    private suspend fun resolveGofileLinks(credentials: List<IDownloadCredentials>): List<IDownloadCredentials>? {
        if (credentials.none { gofilePageOf(it.link) != null }) {
            return credentials
        }
        toast("Reading gofile links...")
        var unavailableCount = 0
        val resolved = credentials.flatMap {
            val page = gofilePageOf(it.link) ?: return@flatMap listOf(it)
            val result = GofileResolver.resolve(this, page, okHttpClient)
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
