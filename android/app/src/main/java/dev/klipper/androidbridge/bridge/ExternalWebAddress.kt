package dev.klipper.androidbridge.bridge

import java.net.URI

object ExternalWebAddress {
    fun normalize(raw: String): String {
        val parsed = runCatching { URI(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("Enter a valid URL") }
        require(parsed.scheme?.lowercase() == "https") { "The address must use https://" }
        require(!parsed.host.isNullOrBlank()) { "The address must include a host" }
        require(parsed.userInfo == null) { "Credentials are not allowed in the URL" }
        return parsed.normalize().toASCIIString()
    }
}
