package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAddressTest {
    @Test fun `prefers site local wifi over cellular and public addresses`() {
        assertEquals(
            "192.168.1.42",
            NetworkAddress.choose(listOf(
                NetworkCandidate("rmnet0", "10.12.0.4", true),
                NetworkCandidate("wlan0", "192.168.1.42", true),
                NetworkCandidate("wlan0", "8.8.8.8", false),
            )),
        )
    }

    @Test fun `falls back to another active interface`() {
        assertEquals(
            "192.168.42.2",
            NetworkAddress.choose(listOf(NetworkCandidate("rndis0", "192.168.42.2", true))),
        )
    }

    @Test fun `returns null with no candidates`() {
        assertNull(NetworkAddress.choose(emptyList()))
    }
}
