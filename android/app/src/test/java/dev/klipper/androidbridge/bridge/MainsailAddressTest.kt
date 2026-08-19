package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainsailAddressTest {
    @Test fun `normalizes loopback URLs`() {
        assertEquals("http://127.0.0.1:8080/", MainsailAddress.normalize(" http://127.0.0.1:8080 "))
        assertEquals("http://localhost/ui/", MainsailAddress.normalize("http://localhost/ui"))
        assertEquals("http://[::1]:8080/", MainsailAddress.normalize("http://[::1]:8080/"))
    }

    @Test fun `recognizes local navigation`() {
        assertTrue(MainsailAddress.isLoopbackUrl("http://localhost:8080/websocket"))
        assertFalse(MainsailAddress.isLoopbackUrl("https://example.com/"))
        assertFalse(MainsailAddress.isLoopbackUrl("file:///etc/passwd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects remote hosts`() { MainsailAddress.normalize("http://192.168.1.2:8080/") }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects credentials`() { MainsailAddress.normalize("http://user:pass@localhost/") }
}
