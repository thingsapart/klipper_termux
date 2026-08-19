package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalWebAddressTest {
    @Test fun `accepts secure download URLs`() {
        assertEquals(
            "https://github.com/termux/termux-app/releases",
            ExternalWebAddress.normalize(" https://github.com/termux/termux-app/releases "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects cleartext URLs`() { ExternalWebAddress.normalize("http://example.com/app.apk") }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects URLs with credentials`() {
        ExternalWebAddress.normalize("https://user:password@example.com/app.apk")
    }
}
