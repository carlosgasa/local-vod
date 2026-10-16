package com.local.vod.domain.model

/** Lo que el servidor cuenta de sí mismo: para vigilarlo desde el sofá. */
data class ServerStatus(
    val hostName: String,
    val system: String,
    val uptimeSeconds: Long?,
    val cpuTemperature: Double?,
    val ip: String,
    val port: Int,
    val totalBytes: Long,
    val freeBytes: Long,
    val libraryBytes: Long,
    val titles: Int,
    val videos: Int,
    val librarySeconds: Long,
    val pendingDurations: Int,
    val battery: Battery?,
) {
    val usedFraction: Float get() = if (totalBytes > 0) 1f - freeBytes.toFloat() / totalBytes else 0f
}

data class Battery(
    val percent: Int?,
    val charging: Boolean,
    val temperature: Double?,
)
