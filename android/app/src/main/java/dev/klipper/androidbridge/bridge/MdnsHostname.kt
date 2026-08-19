package dev.klipper.androidbridge.bridge

import java.util.Locale

object MdnsHostname {
    const val DEFAULT = "klipper-android"
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun normalize(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.US).trimEnd('.').removeSuffix(".local")
        require(label.matches(normalized)) {
            "Use 1–63 letters, digits, or hyphens; the name cannot begin or end with a hyphen"
        }
        return normalized
    }
}
