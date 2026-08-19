package dev.klipper.androidbridge.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BridgeProtocolTest {
    @Test fun parsesCCompatibleOpenRequest() {
        val token = ByteArray(32) { it.toByte() }
        val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val bytes = ByteBuffer.allocate(BridgeProtocol.REQUEST_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(BridgeProtocol.MAGIC)
            .putShort(1)
            .putShort(BridgeProtocol.Operation.OPEN.toShort())
            .putInt(0x12345678)
            .put(token)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .putInt(250000)
            .put(8)
            .put(1)
            .put(0)
            .put(2)
            .array()

        val request = BridgeProtocol.readRequest(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(BridgeProtocol.Operation.OPEN, request.operation)
        assertEquals(0x12345678, request.requestId)
        assertArrayEquals(token, request.token)
        assertEquals(id, request.deviceId)
        assertEquals(250000, request.baud)
        assertEquals(8, request.dataBits)
        assertEquals(2, request.flags)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsBadMagic() {
        BridgeProtocol.readRequest(DataInputStream(ByteArrayInputStream(ByteArray(72))))
    }
}
