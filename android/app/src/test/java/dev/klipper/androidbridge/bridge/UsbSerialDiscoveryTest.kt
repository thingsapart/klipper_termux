package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbSerialDiscoveryTest {
    @Test
    fun `Klipper default USB id force binds CDC ACM`() {
        assertEquals(
            UsbSerialDriverKind.CDC_ACM,
            UsbSerialDiscovery.defaultKind(0x1d50, 0x614e),
        )
    }

    @Test
    fun `unrelated USB id has no forced driver`() {
        assertNull(UsbSerialDiscovery.defaultKind(0x1234, 0x5678))
    }
}
