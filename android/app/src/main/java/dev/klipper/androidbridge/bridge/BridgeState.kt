package dev.klipper.androidbridge.bridge

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SessionSnapshot(
    val deviceId: UUID,
    val alias: String,
    val connectedAtMillis: Long,
    val lastActivityMillis: Long,
    val usbToHostBytes: Long,
    val hostToUsbBytes: Long,
    val usbReads: Long,
    val usbWrites: Long,
    val maxSocketWriteMicros: Long,
    val maxUsbWriteMicros: Long,
    val errors: Long,
)

class SessionCounters(val deviceId: UUID, val alias: String) {
    val connectedAtMillis = System.currentTimeMillis()
    val lastActivityMillis = AtomicLong(connectedAtMillis)
    val usbToHostBytes = AtomicLong()
    val hostToUsbBytes = AtomicLong()
    val usbReads = AtomicLong()
    val usbWrites = AtomicLong()
    val maxSocketWriteMicros = AtomicLong()
    val maxUsbWriteMicros = AtomicLong()
    val errors = AtomicLong()

    fun recordMaxSocketWriteMicros(value: Long) = recordMax(maxSocketWriteMicros, value)
    fun recordMaxUsbWriteMicros(value: Long) = recordMax(maxUsbWriteMicros, value)

    private fun recordMax(counter: AtomicLong, value: Long) {
        var previous = counter.get()
        while (value > previous && !counter.compareAndSet(previous, value)) {
            previous = counter.get()
        }
    }

    fun snapshot() = SessionSnapshot(
        deviceId, alias, connectedAtMillis, lastActivityMillis.get(), usbToHostBytes.get(),
        hostToUsbBytes.get(), usbReads.get(), usbWrites.get(), maxSocketWriteMicros.get(),
        maxUsbWriteMicros.get(), errors.get(),
    )
}

object BridgeState {
    @Volatile var serviceRunning = false
    @Volatile var listenerError: String? = null
    @Volatile var lastUsbError: String? = null
    val sessions = ConcurrentHashMap<UUID, SessionCounters>()
    fun snapshots(): List<SessionSnapshot> = sessions.values.map(SessionCounters::snapshot)
}
