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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.android.ui.page.PageTitle
import com.abdownloadmanager.android.util.activity.ABDMActivity
import com.abdownloadmanager.android.util.remote.PcDownload
import com.abdownloadmanager.android.util.remote.PcDownloadsWatcher
import com.abdownloadmanager.android.util.remote.PcRemote
import com.abdownloadmanager.shared.ui.widget.ActionButton
import com.abdownloadmanager.shared.ui.widget.Text
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.mySpacings
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import org.koin.core.component.inject

/**
 * tests the connection to the PC and shows a read only list of its downloads
 */
class PcDownloadsActivity : ABDMActivity() {
    private val watcher: PcDownloadsWatcher by inject()
    private val pcRemote: PcRemote by inject()
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setABDMContent {
            val downloads by watcher.downloads.collectAsState()
            var testNumber by remember { mutableIntStateOf(0) }
            var isTesting by remember { mutableStateOf(true) }
            // null when the PC is connected
            var connectionError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(testNumber) {
                isTesting = true
                connectionError = pcRemote.testConnection()
                isTesting = false
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(myColors.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                PageTitle("PC downloads")
                Column(Modifier.padding(horizontal = mySpacings.largeSpace)) {
                    val error = connectionError
                    Text(
                        text = when {
                            isTesting -> "Testing the connection..."
                            error == null -> "Connected to the PC"
                            else -> "Not connected to the PC"
                        },
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isTesting -> Color.Unspecified
                            error == null -> myColors.success
                            else -> myColors.error
                        },
                    )
                    if (!isTesting && error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(error, fontSize = myTextSizes.sm)
                    }
                    Spacer(Modifier.height(mySpacings.mediumSpace))
                    ActionButton(
                        text = "Test connection",
                        enabled = !isTesting,
                        onClick = { testNumber++ },
                    )
                    Spacer(Modifier.height(mySpacings.mediumSpace))
                    if (!isTesting && error == null && downloads.isEmpty()) {
                        Text("No downloads")
                    }
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
