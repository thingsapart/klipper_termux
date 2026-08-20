package dev.klipper.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareCommandOutputTest {
    @Test fun `parses firmware profiles`() {
        val result = FirmwareCommandOutput.profiles(
            "btt-octopus-f446-v1|BTT Octopus|V1.1|firmware.bin|flash-sdcard\n",
        )
        assertEquals(1, result.size)
        assertEquals("firmware.bin", result.single().filename)
    }

    @Test fun `parses destinations and rejects unsafe paths`() {
        val result = FirmwareCommandOutput.destinations(
            "web|Mainsail / web download||1\n" +
                "storage-1234-abcd|Storage card|/storage/1234-ABCD|0\n" +
                "bad|Bad|/data/data/com.termux|1\n",
        )
        assertEquals(2, result.size)
        assertTrue(result.first().writable)
        assertFalse(result.last().writable)
    }

    @Test fun `extracts only safe completed build ids`() {
        assertEquals("board-0123", FirmwareCommandOutput.buildId("Build complete: board-0123\n"))
        assertNull(FirmwareCommandOutput.buildId("Build complete: ../../bad\n"))
    }
}
