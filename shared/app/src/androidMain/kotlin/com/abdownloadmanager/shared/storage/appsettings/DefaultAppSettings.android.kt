package com.abdownloadmanager.shared.storage.appsettings

actual object PlatformDefaultSettings : DefaultAppSettings() {
    override val useSparseFileAllocation: Boolean get() = false

    val browserIconInLauncher: Boolean get() = false
    val pcAddress: String get() = ""
    val pcApiKey: String get() = ""
}