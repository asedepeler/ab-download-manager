package com.abdownloadmanager.android.pages.remote

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.android.ui.page.PageTitle
import com.abdownloadmanager.android.util.activity.ABDMActivity
import com.abdownloadmanager.android.util.remote.PcDownload
import com.abdownloadmanager.android.util.remote.PcDownloadsWatcher
import com.abdownloadmanager.shared.ui.widget.Text
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.mySpacings
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import org.koin.core.component.inject

/**
 * read only list of the downloads of the PC
 */
class PcDownloadsActivity : ABDMActivity() {
    private val watcher: PcDownloadsWatcher by inject()
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setABDMContent {
            val downloads by watcher.downloads.collectAsState()
            val error by watcher.lastError.collectAsState()
            Column(
                Modifier
                    .fillMaxSize()
                    .background(myColors.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                PageTitle("PC downloads")
                val message = error ?: "No downloads".takeIf { downloads.isEmpty() }
                message?.let {
                    Text(it, Modifier.padding(mySpacings.largeSpace))
                }
                LazyColumn {
                    items(downloads, key = { it.id }) {
                        RenderPcDownload(it)
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderPcDownload(download: PcDownload) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = mySpacings.largeSpace, vertical = mySpacings.mediumSpace)
        ) {
            Text(download.name, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            val speedUnit by sizeAndSpeedUnitProvider.speedUnit.collectAsState()
            Text(
                listOfNotNull(
                    download.status,
                    download.percent?.let { "$it%" },
                    convertPositiveSpeedToHumanReadable(download.speed, speedUnit)
                        .takeIf { download.isDownloading },
                ).joinToString(" - "),
                fontSize = myTextSizes.sm,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(myColors.onBackground / 10)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((download.percent ?: 0) / 100f)
                        .height(3.dp)
                        .background(myColors.primary)
                )
            }
        }
    }
}
