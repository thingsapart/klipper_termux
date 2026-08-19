package dev.klipper.androidbridge.bridge

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BridgeProtocol {
    const val VERSION = 1
    const val REQUEST_SIZE = 72
    const val RESPONSE_SIZE = 20
    const val DEFAULT_PORT = 27831
    const val MAX_MESSAGE = 1024
    val MAGIC = byteArrayOf(0x4b, 0x4c, 0x49, 0x50, 0x55, 0x53, 0x42, 0)

    object Operation {
        const val OPEN = 1
        const val LIST = 2
        const val STATUS = 3
    }

    object Status {
        const val OK = 0
        const val BAD_MAGIC = 1
        const val UNSUPPORTED_VERSION = 2
        const val UNAUTHORIZED = 3
        const val BAD_REQUEST = 4
        const val DEVICE_NOT_FOUND = 5
        const val PERMISSION_REQUIRED = 6
        const val DEVICE_BUSY = 7
        const val USB_ERROR = 8
        const val INTERNAL_ERROR = 9
    }

    data class Request(
        val version: Int,
        val operation: Int,
        val requestId: Int,
        val token: ByteArray,
        val deviceId: UUID,
        val baud: Int,
        val dataBits: Int,
        val stopBits: Int,
        val parity: Int,
        val flags: Int,
    )

    fun readRequest(input: DataInputStream): Request {
        val bytes = ByteArray(REQUEST_SIZE)
        input.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw ProtocolException(Status.BAD_MAGIC, "bad magic")
        val version = buffer.short.toInt() and 0xffff
        if (version != VERSION) {
            throw ProtocolException(Status.UNSUPPORTED_VERSION, "unsupported version $version")
        }
        val operation = buffer.short.toInt() and 0xffff
        val requestId = buffer.int
        val token = ByteArray(32).also(buffer::get)
        val deviceId = UUID(buffer.long, buffer.long)
        val baud = buffer.int
        return Request(
            version, operation, requestId, token, deviceId, baud,
            buffer.get().toInt() and 0xff,
            buffer.get().toInt() and 0xff,
            buffer.get().toInt() and 0xff,
            buffer.get().toInt() and 0xff,
        )
    }

    fun writeResponse(output: DataOutputStream, requestId: Int, status: Int, message: String = "") {
        val messageBytes = message.toByteArray(Charsets.UTF_8).let {
            if (it.size <= MAX_MESSAGE) it else it.copyOf(MAX_MESSAGE)
        }
        val buffer = ByteBuffer.allocate(RESPONSE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(status.toShort())
        buffer.putInt(requestId)
        buffer.putShort(messageBytes.size.toShort())
        buffer.putShort(0)
        output.write(buffer.array())
        output.write(messageBytes)
        output.flush()
    }
}

class ProtocolException(val status: Int, message: String) : Exception(message)

