package com.abdownloadmanager.android.util.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abdownloadmanager.android.R
import com.abdownloadmanager.android.pages.remote.PcDownloadsActivity
import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.IHttpBasedDownloadCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * mirrors ApiDownloadModel of the integration server
 */
@Serializable
data class PcDownload(
    val id: Long,
    val name: String,
    val status: String,
    val percent: Int? = null,
    val speed: Long = 0,
    val contentLength: Long = 0,
) {
    val isDownloading get() = status == "downloading"
    val isFinished get() = status == "finished"
}

/**
 * talks to the integration server of AB Download Manager running on a PC in the same network
 */
class PcRemote(
    private val appSettings: AppSettingsStorage,
    baseClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = baseClient.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isConfigured() = appSettings.pcAddress.value.isNotBlank()
    val isConfiguredFlow get() = appSettings.pcAddress.map { it.isNotBlank() }.distinctUntilChanged()

    private fun newRequest(path: String): Request.Builder {
        val address = appSettings.pcAddress.value.trim().trimEnd('/')
        val baseUrl = if ("://" in address) address else "http://$address"
        return Request.Builder()
            .url(baseUrl + path)
            .apply {
                appSettings.pcApiKey.value.trim().takeIf { it.isNotEmpty() }?.let {
                    header("X-Api-Key", it)
                }
            }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use {
            if (!it.isSuccessful) {
                throw IOException("PC responded with HTTP ${it.code}")
            }
            it.body.string()
        }
    }

    suspend fun listDownloads(): List<PcDownload> {
        return json.decodeFromString(execute(newRequest("/downloads").build()))
    }

    /**
     * the PC adds and starts these downloads without asking
     */
    suspend fun sendDownloads(items: List<IDownloadCredentials>) {
        val body = buildJsonObject {
            put("items", buildJsonArray {
                for (item in items) {
                    add(buildJsonObject {
                        put("type", if (item is HLSDownloadCredentials) "hls" else "http")
                        put("link", item.link)
                        item.downloadPage?.let { put("downloadPage", it) }
                        (item as? IHttpBasedDownloadCredentials)?.headers?.let { headers ->
                            putJsonObject("headers") {
                                headers.forEach { (k, v) -> put(k, v) }
                            }
                        }
                    })
                }
            })
            putJsonObject("options") {
                put("silentAdd", true)
                put("silentStart", true)
            }
        }
        execute(
            newRequest("/add")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        )
    }
}

/**
 * polls the PC and mirrors its active downloads as notifications on this device
 */
class PcDownloadsWatcher(
    private val context: Context,
    private val pcRemote: PcRemote,
    private val scope: CoroutineScope,
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider,
) {
    private val notificationManager by lazy { NotificationManagerCompat.from(context) }
    private val _downloads = MutableStateFlow<List<PcDownload>>(emptyList())
    val downloads = _downloads.asStateFlow()

    // null means the last poll was successful
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private var booted = false
    fun boot() {
        synchronized(this) {
            if (booted) return
            booted = true
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PC Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        scope.launch {
            pcRemote.isConfiguredFlow.collectLatest { configured ->
                if (!configured) {
                    onNewList(emptyList())
                    return@collectLatest
                }
                while (true) {
                    val result = runCatching { pcRemote.listDownloads() }
                    _lastError.value = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                    // an unreachable PC has nothing to show
                    val list = result.getOrDefault(emptyList())
                    onNewList(list)
                    delay(
                        when {
                            list.any { it.isDownloading } -> 2.seconds
                            result.isSuccess -> 15.seconds
                            else -> 60.seconds
                        }
                    )
                }
            }
        }
    }

    private var notifiedIds = emptySet<Long>()

    @SuppressLint("MissingPermission")
    private fun onNewList(list: List<PcDownload>) {
        _downloads.value = list
        val downloading = list.filter { it.isDownloading }
        val downloadingIds = downloading.map { it.id }.toSet()
        runCatching {
            for (download in downloading) {
                notificationManager.notify(TAG, download.id.toInt(), createNotification(download))
            }
            for (id in notifiedIds - downloadingIds) {
                val finished = list.find { it.id == id && it.isFinished }
                if (finished != null) {
                    notificationManager.notify(TAG, id.toInt(), createNotification(finished))
                } else {
                    notificationManager.cancel(TAG, id.toInt())
                }
            }
        }.onFailure { it.printStackTrace() }
        notifiedIds = downloadingIds
    }

    private fun createNotification(download: PcDownload): Notification {
        val openList = PendingIntent.getActivity(
            context,
            0,
            Intent(context, PcDownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(download.name)
            .setSmallIcon(R.drawable.ic_monochrome)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openList)
        if (download.isFinished) {
            return builder
                .setContentText("PC - Finished")
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .build()
        }
        val speed = convertPositiveSpeedToHumanReadable(download.speed, sizeAndSpeedUnitProvider.speedUnit.value)
        return builder
            .setContentText("PC - $speed")
            .setSubText(download.percent?.let { "$it%" })
            .setProgress(100, download.percent ?: 0, download.percent == null)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pcDownloads"
        private const val TAG = "pc"
    }
}
