package dev.klipper.androidbridge.bridge

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class UsbSession(
    private val socket: Socket,
    private val port: UsbSerialPort,
    private val counters: SessionCounters,
    private val onClosed: (UsbSession) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val usbToHost = Thread({ usbToHostLoop() }, "usb-to-termux-${counters.alias}")
    private val hostToUsb = Thread({ hostToUsbLoop() }, "termux-to-usb-${counters.alias}")

    fun start() {
        usbToHost.start()
        hostToUsb.start()
    }

    private fun usbToHostLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val output = socket.getOutputStream()
            while (!closed.get()) {
                val count = port.read(buffer, buffer.size, 0)
                if (count <= 0) continue
                output.write(buffer, 0, count)
                counters.usbToHostBytes.addAndGet(count.toLong())
                counters.usbReads.incrementAndGet()
                counters.lastActivityMillis.set(System.currentTimeMillis())
            }
        } catch (error: Exception) {
            if (!closed.get()) recordError("USB read", error)
        } finally {
            close()
        }
    }

    private fun hostToUsbLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                port.write(buffer, count, WRITE_TIMEOUT_MS)
                counters.hostToUsbBytes.addAndGet(count.toLong())
                counters.usbWrites.incrementAndGet()
                counters.lastActivityMillis.set(System.currentTimeMillis())
            }
        } catch (error: Exception) {
            if (!closed.get()) recordError("USB write", error)
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { socket.close() } catch (_: Exception) {}
        try { port.close() } catch (_: Exception) {}
        onClosed(this)
    }

    private fun recordError(operation: String, error: Exception) {
        counters.errors.incrementAndGet()
        val detail = "$operation: ${error.message ?: error.javaClass.simpleName}"
        BridgeState.lastUsbError = detail
        Log.e(TAG, detail, error)
    }

    companion object {
        private const val TAG = "KlipperUsbSession"
        private const val BUFFER_SIZE = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 1000
    }
}
