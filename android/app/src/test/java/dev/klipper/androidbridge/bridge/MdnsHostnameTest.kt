package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class MdnsHostnameTest {
    @Test fun `normalizes labels and optional local suffix`() {
        assertEquals("klipper-android", MdnsHostname.normalize(" Klipper-Android.local "))
        assertEquals("klipper-android", MdnsHostname.normalize("klipper-android.local."))
        assertEquals("printer7", MdnsHostname.normalize("printer7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects labels beginning with hyphen`() { MdnsHostname.normalize("-printer") }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects dotted domain names`() { MdnsHostname.normalize("printer.example") }
}
