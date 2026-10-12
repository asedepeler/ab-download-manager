package com.abdownloadmanager.android.pages.settings

import android.content.Context
import android.content.Intent
import com.abdownloadmanager.android.pages.onboarding.permissions.ABDMPermissions
import com.abdownloadmanager.android.pages.remote.PcDownloadsActivity
import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.android.ui.configurable.android.item.PermissionConfigurable
import com.abdownloadmanager.android.util.pagemanager.PermissionsPageManager
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.ui.configurable.item.BooleanConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.NavigatableConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.StringConfigurable
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.flow.MutableStateFlow


object AndroidSettings {
    fun permissionSettings(
        permissionsPageManager: PermissionsPageManager
    ): NavigatableConfigurable {
        return NavigatableConfigurable(
            title = Res.string.permissions.asStringSource(),
            description = "".asStringSource(),
            onRequestNavigate = {
                permissionsPageManager.openPermissionsPage(false)
            },
        )
    }

    fun ignoreBatteryOptimizations(): PermissionConfigurable {
        val permission = ABDMPermissions.BatteryOptimizationPermission
        return PermissionConfigurable(
            title = permission.title,
            description = permission.description,
            backedBy = MutableStateFlow(permission),
        )
    }

    fun pcAddress(
        appSettingsStorage: AppSettingsStorage
    ): StringConfigurable {
        return StringConfigurable(
            title = "PC address".asStringSource(),
            description = "Address of AB Download Manager on your PC in the same network, like 192.168.1.10:15151 (see the IPv4 address in the network settings of the PC). Downloads of that PC are shown in notifications. Leave it empty to disable.".asStringSource(),
            backedBy = appSettingsStorage.pcAddress,
            describe = {
                it.ifBlank { Res.string.disabled.asStringSource().getString() }.asStringSource()
            },
        )
    }

    fun pcApiKey(
        appSettingsStorage: AppSettingsStorage
    ): StringConfigurable {
        return StringConfigurable(
            title = "PC API key".asStringSource(),
            description = "On the PC enable \"Use API Key\" in the settings of AB Download Manager, then copy its \"API Key\" here.".asStringSource(),
            backedBy = appSettingsStorage.pcApiKey,
            describe = { "".asStringSource() },
        )
    }

    fun pcDownloads(context: Context): NavigatableConfigurable {
        return NavigatableConfigurable(
            title = "Test PC connection".asStringSource(),
            description = "Check if this phone is connected to your PC and show the downloads of the PC".asStringSource(),
            onRequestNavigate = {
                context.startActivity(
                    Intent(context, PcDownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }

    fun browserIconInLauncher(
        appSettingsStorage: AppSettingsStorage
    ): BooleanConfigurable {
        return BooleanConfigurable(
            title = Res.string.settings_browser_in_launcher.asStringSource(),
            description = Res.string.settings_browser_in_launcher_description.asStringSource(),
            backedBy = appSettingsStorage.browserIconInLauncher,
            describe = {
                if (it) {
                    Res.string.enabled
                } else {
                    Res.string.disabled
                }.asStringSource()
            }
        )
    }
}
