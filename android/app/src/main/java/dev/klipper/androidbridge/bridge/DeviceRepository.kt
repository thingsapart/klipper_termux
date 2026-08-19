package dev.klipper.androidbridge.bridge

import android.content.Context
import android.hardware.usb.UsbDevice
import java.security.SecureRandom
import java.util.UUID

data class DeviceProfile(val id: UUID, val key: String, val alias: String)

class DeviceRepository(context: Context) {
    private val preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun token(): ByteArray {
        val current = preferences.getString("token", null)
        if (current != null) return current.hexToBytes()
        val token = ByteArray(32).also(SecureRandom()::nextBytes)
        preferences.edit().putString("token", token.toHex()).apply()
        return token
    }

    fun regenerateToken(): ByteArray {
        val token = ByteArray(32).also(SecureRandom()::nextBytes)
        preferences.edit().putString("token", token.toHex()).apply()
        return token
    }

    fun port(): Int = preferences.getInt("port", BridgeProtocol.DEFAULT_PORT)

    fun mainsailUrl(): String = preferences.getString("mainsail_url", MainsailAddress.DEFAULT)
        ?: MainsailAddress.DEFAULT

    fun setMainsailUrl(raw: String): String {
        val normalized = MainsailAddress.normalize(raw)
        preferences.edit().putString("mainsail_url", normalized).apply()
        return normalized
    }

    fun termuxDownloadUrl(default: String): String =
        preferences.getString("termux_download_url", default) ?: default

    fun termuxGithubReleasesUrl(default: String): String =
        preferences.getString("termux_github_releases_url", default) ?: default

    fun setTermuxLinks(downloadUrl: String, githubReleasesUrl: String): Pair<String, String> {
        val download = ExternalWebAddress.normalize(downloadUrl)
        val releases = ExternalWebAddress.normalize(githubReleasesUrl)
        preferences.edit()
            .putString("termux_download_url", download)
            .putString("termux_github_releases_url", releases)
            .apply()
        return download to releases
    }

    fun installerAttempted(): Boolean = preferences.getBoolean("installer_attempted", false)

    fun markInstallerAttempted() {
        preferences.edit().putBoolean("installer_attempted", true).apply()
    }

    fun mainsailSeen(): Boolean = preferences.getBoolean("mainsail_seen", false)

    fun markMainsailSeen() {
        preferences.edit().putBoolean("mainsail_seen", true).apply()
    }

    fun pairingSent(): Boolean = preferences.getBoolean("pairing_sent", false)

    fun markPairingSent() {
        preferences.edit().putBoolean("pairing_sent", true).apply()
    }

    fun profileFor(device: UsbDevice, portNumber: Int, create: Boolean): DeviceProfile? {
        val key = stableKey(device, portNumber)
        val encoded = preferences.getString("device.$key", null)
        if (encoded != null) {
            val separator = encoded.indexOf('|')
            if (separator > 0) {
                return DeviceProfile(
                    UUID.fromString(encoded.substring(0, separator)), key,
                    encoded.substring(separator + 1),
                )
            }
        }
        if (!create) return null
        val profile = DeviceProfile(UUID.randomUUID(), key, "mcu-${device.deviceId}-$portNumber")
        preferences.edit().putString("device.$key", "${profile.id}|${profile.alias}").apply()
        return profile
    }

    fun rename(profile: DeviceProfile, alias: String): DeviceProfile {
        val clean = alias.trim().take(48)
        require(clean.isNotEmpty())
        val renamed = profile.copy(alias = clean)
        preferences.edit().putString("device.${profile.key}", "${profile.id}|$clean").apply()
        return renamed
    }

    private fun stableKey(device: UsbDevice, portNumber: Int): String {
        val serial = try { device.serialNumber } catch (_: SecurityException) { null }
        val identity = serial?.takeIf(String::isNotBlank) ?: "path:${device.deviceName}"
        return "%04x:%04x:%s:%d".format(
            device.vendorId, device.productId, identity.replace('|', '_'), portNumber,
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
