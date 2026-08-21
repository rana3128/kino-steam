package com.vpsin.kino.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the server's GET /api/info response. */
@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    val protocol: Int = 1,
    val shares: Int = 0,
    val addresses: List<String> = emptyList(),
)

/** A server the TV can connect to, from UDP discovery or typed in by hand. */
data class ServerRef(
    val name: String,
    val host: String,
    val port: Int,
) {
    val baseUrl: String get() = "http://$host:$port"
    val label: String get() = "$host:$port"
}

/** UDP announcement payload broadcast by the laptop. */
@Serializable
data class Announcement(
    val magic: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val protocol: Int = 1,
)

@Serializable
data class Subtitle(
    val id: String,
    val label: String,
    val lang: String,
    val ext: String,
)

@Serializable
data class Crumb(
    val id: String,
    val name: String,
)

@Serializable
data class Entry(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("fileName") val fileName: String? = null,
    val ext: String? = null,
    val size: Long = 0,
    val subtitles: List<Subtitle> = emptyList(),
) {
    val isFolder: Boolean get() = type == "folder"
}

@Serializable
data class BrowseResult(
    val path: List<Crumb> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

/** Human-readable file size for the browse cards. */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit >= 3) String.format("%.1f %s", value, units[unit])
    else String.format("%.0f %s", value, units[unit])
}

/** mm:ss or h:mm:ss, for the player's time readouts. */
fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
