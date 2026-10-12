package com.abdownloadmanager.android.integration

import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.android.util.ABDMAppManager
import com.abdownloadmanager.android.util.CookieSites
import com.abdownloadmanager.android.util.remote.PcRemote
import com.abdownloadmanager.integration.IntegrationHandler
import com.abdownloadmanager.integration.model.AddDownloadsFromIntegration
import com.abdownloadmanager.integration.model.ApiDownloadModel
import com.abdownloadmanager.integration.model.ApiQueueModel
import com.abdownloadmanager.integration.model.HLSDownloadCredentialsFromIntegration
import com.abdownloadmanager.integration.model.HttpDownloadCredentialsFromIntegration
import com.abdownloadmanager.integration.model.IDownloadCredentialsFromIntegration
import com.abdownloadmanager.integration.model.NewDownloadTask
import com.abdownloadmanager.shared.ui.widget.NotificationType
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.queue.QueueManager
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * the browser extension of a browser on this device (like Kiwi) sends its downloads here,
 * together with the cookies of that browser, which this app can't read by itself
 */
class AndroidIntegrationHandler : IntegrationHandler, KoinComponent {
    private val pcRemote by inject<PcRemote>()
    private val appSettings by inject<AppSettingsStorage>()
    private val appManager by inject<ABDMAppManager>()
    private val queueManager by inject<QueueManager>()

    /**
     * shortcut: these downloads are only passed to the PC, add them to this device when it is needed
     */
    override suspend fun addDownloadByGui(request: AddDownloadsFromIntegration) {
        if (!pcRemote.isConfigured()) {
            notifyFailure("\"PC address\" is not set in the settings")
            // an error tells the browser extension to let the browser download it
            error("PC address is not set")
        }
        try {
            pcRemote.sendDownloads(request.items.map(::toCredentials))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notifyFailure(pcRemote.describeError(e))
            throw e
        }
        appManager.sendNotification(
            tag = request,
            title = "Sent to PC".asStringSource(),
            description = "${request.items.size} download(s)".asStringSource(),
            type = NotificationType.Success,
        )
    }

    private fun notifyFailure(reason: String) {
        appManager.sendNotification(
            tag = reason,
            title = "Can't send to PC".asStringSource(),
            description = reason.asStringSource(),
            type = NotificationType.Error,
        )
    }

    private fun toCredentials(item: IDownloadCredentialsFromIntegration): IDownloadCredentials {
        return when (item) {
            is HttpDownloadCredentialsFromIntegration -> HttpDownloadCredentials(
                link = item.link,
                headers = allowedHeaders(item.link, item.headers),
                downloadPage = item.downloadPage,
            )

            is HLSDownloadCredentialsFromIntegration -> HLSDownloadCredentials(
                link = item.link,
                headers = allowedHeaders(item.link, item.headers),
                downloadPage = item.downloadPage,
            )
        }
    }

    /**
     * the cookies of the browser are only passed for the websites that the user has chosen
     */
    private fun allowedHeaders(link: String, headers: Map<String, String>?): Map<String, String>? {
        if (headers == null || CookieSites.allows(appSettings.cookieSites.value, link)) {
            return headers
        }
        return headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
    }

    override fun listQueues(): List<ApiQueueModel> {
        return queueManager.getAll().map { downloadQueue ->
            val queueModel = downloadQueue.getQueueModel()
            ApiQueueModel(id = queueModel.id, name = queueModel.name)
        }
    }

    // nothing polls the downloads of this device
    override fun listDownloads(): List<ApiDownloadModel> = emptyList()

    override suspend fun addDownload(task: NewDownloadTask): Long {
        throw UnsupportedOperationException("Headless downloads are not supported on Android")
    }
}
