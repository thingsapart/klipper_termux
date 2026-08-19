package dev.klipper.androidbridge.bridge

import java.net.URI

object MainsailAddress {
    const val DEFAULT = "http://127.0.0.1:8080/"

    fun normalize(raw: String): String {
        val parsed = runCatching { URI(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("Enter a valid URL") }
        require(parsed.scheme == "http" || parsed.scheme == "https") {
            "Only http:// or https:// URLs are supported"
        }
        require(parsed.userInfo == null) { "Credentials are not allowed in the URL" }
        require(isLoopbackHost(parsed.host)) { "Mainsail must use localhost or a loopback address" }
        require(parsed.port == -1 || parsed.port in 1..65535) { "Enter a valid port" }
        require(parsed.rawQuery == null && parsed.rawFragment == null) {
            "Query strings and fragments are not supported"
        }
        val path = (parsed.rawPath?.takeIf(String::isNotEmpty) ?: "/").let {
            if (it.endsWith('/')) it else "$it/"
        }
        return URI(parsed.scheme, null, parsed.host, parsed.port, path, null, null).toASCIIString()
    }

    fun isLoopbackUrl(raw: String): Boolean = runCatching {
        val parsed = URI(raw)
        (parsed.scheme == "http" || parsed.scheme == "https") && isLoopbackHost(parsed.host)
    }.getOrDefault(false)

    private fun isLoopbackHost(host: String?): Boolean = when (
        host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    ) {
        "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
        else -> false
    }
}
