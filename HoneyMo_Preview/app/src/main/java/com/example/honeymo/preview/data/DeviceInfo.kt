package com.example.honeymo.preview.data

data class DeviceInfo(
    val id: String,
    val name: String,
    val width: Int = 720,
    val height: Int = 1280,
    val fps: Int = 30,
    val bitrate: Int = 2000000,
    val connectedAt: String = "",
    val isIconVisible: Boolean = true
)
