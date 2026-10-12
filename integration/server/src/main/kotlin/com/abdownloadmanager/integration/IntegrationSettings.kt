package com.abdownloadmanager.integration


data class IntegrationSettings(
    val port: Int,
    val apiKey: String?,
    // listen for other devices of the network too, it is ignored when there is no api key
    val allowLan: Boolean,
)
