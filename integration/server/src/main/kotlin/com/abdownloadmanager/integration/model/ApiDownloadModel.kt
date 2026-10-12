package com.abdownloadmanager.integration.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiDownloadModel(
    val id: Long,
    val name: String,
    // one of STATUS_*
    val status: String,
    // 0..100, null when the size is unknown
    val percent: Int?,
    // bytes per second
    val speed: Long,
    val contentLength: Long,
) {
    companion object {
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_FINISHED = "finished"
    }
}
