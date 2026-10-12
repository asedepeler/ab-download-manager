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
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.SerializationException
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

private class PcResponseException(
    // null if the response is successful, but it is not what we expected
    val code: Int?,
) : IOException("HTTP $code")

// default port of the integration server
private const val DEFAULT_PORT = 15151

/**
 * talks to the integration server of AB Download Manager running on a PC in the same network
 */
class PcRemote(
    private val appSettings: AppSettingsStorage,
    baseClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    // an unreachable PC in the local network should fail fast
    private val client = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun address() = appSettings.pcAddress.value.trim().trimEnd('/')
    fun isConfigured() = address().isNotEmpty()
    val isConfiguredFlow get() = appSettings.pcAddress.map { it.isNotBlank() }.distinctUntilChanged()

    private fun newRequest(path: String): Request.Builder {
        val address = address()
        require(address.isNotEmpty()) { "The address is empty" }
        val withScheme = if ("://" in address) address else "http://$address"
        val baseUrl = if (':' in withScheme.substringAfter("://")) {
            withScheme
        } else {
            "$withScheme:$DEFAULT_PORT"
        }
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
                throw PcResponseException(it.code)
            }
            it.body.string()
        }
    }

    /**
     * @return null if AB Download Manager on the PC answered and accepted the api key, otherwise the reason
     */
    suspend fun testConnection(): String? {
        return try {
            val answer = execute(newRequest("/ping").post("".toRequestBody()).build())
            if (answer.trim() == "pong") null else describeError(PcResponseException(null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            describeError(e)
        }
    }

    /**
     * explains to the user why a request to the PC failed and what to check
     */
    fun describeError(e: Throwable): String {
        val address = address()
        return when (e) {
            is PcResponseException -> when (e.code) {
                401, 403 -> "The PC refused the API key. Copy \"API Key\" from the settings of " +
                        "AB Download Manager on the PC and put it in \"PC API key\" here."

                else -> "Something answered at $address but it is not AB Download Manager with PC sync " +
                        "(${e.code?.let { "HTTP $it" } ?: "unexpected answer"}). Check the address and the port."
            }

            // it answered something that is not ours (this is an IllegalArgumentException too)
            is SerializationException -> describeError(PcResponseException(null))

            is IllegalArgumentException -> "\"$address\" is not a valid address. " +
                    "Use the IP of the PC, like 192.168.1.10:$DEFAULT_PORT."

            else -> listOf(
                "Can't reach the PC at $address (${e.message ?: e.javaClass.simpleName}). Check that:",
                "1. The phone and the PC are in the same network",
                "2. The address is the IP of the PC",
                "3. AB Download Manager is running on the PC, " +
                        "with \"Browser Integration\" and \"Use API Key\" enabled in its settings",
                "4. Windows Firewall on the PC allows incoming connections to port $DEFAULT_PORT",
            ).joinToString(System.lineSeparator())
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
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
