package dev.klipper.androidbridge.bridge

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkCandidate(
    val interfaceName: String,
    val address: String,
    val siteLocal: Boolean,
)

object NetworkAddress {
    fun currentIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { network ->
            if (!runCatching { network.isUp }.getOrDefault(false) || network.isLoopback) {
                emptyList()
            } else {
                Collections.list(network.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { NetworkCandidate(network.name, it.hostAddress ?: return@map null, it.isSiteLocalAddress) }
                    .filterNotNull()
            }
        }.let(::choose)
    }.getOrNull()

    fun choose(candidates: List<NetworkCandidate>): String? = candidates.minWithOrNull(
        compareBy<NetworkCandidate>({ interfacePriority(it.interfaceName, it.siteLocal) }, { it.address }),
    )?.address

    private fun interfacePriority(name: String, siteLocal: Boolean): Int {
        val normalized = name.lowercase()
        val base = when {
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 0
            normalized.startsWith("eth") -> 1
            normalized.startsWith("rndis") || normalized.startsWith("usb") -> 2
            normalized.startsWith("ap") -> 3
            normalized.startsWith("rmnet") || normalized.startsWith("ccmni") -> 20
            else -> 10
        }
        return base + if (siteLocal) 0 else 50
    }
}
