package dev.klipper.androidbridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import dev.klipper.androidbridge.MainActivity
import dev.klipper.androidbridge.R
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsbBridgeService : Service() {
    private val stopped = AtomicBoolean()
    private val clients = Executors.newCachedThreadPool { task ->
        Thread(task, "bridge-client").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<UUID, UsbSession>()
    private lateinit var repository: DeviceRepository
    private lateinit var usbManager: UsbManager
    private var server: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = DeviceRepository(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("$packageName:moonrakerMdns")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        createNotificationChannel()
        enterForeground()
        startListener()
        BridgeState.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListener() {
        Thread({
            try {
                val listener = ServerSocket(repository.port(), 16, InetAddress.getByName("127.0.0.1"))
                server = listener
                while (!stopped.get()) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.sendBufferSize = 32 * 1024
                    socket.receiveBufferSize = 32 * 1024
                    clients.execute { handleClient(socket) }
                }
            } catch (error: Exception) {
                if (!stopped.get()) BridgeState.listenerError = error.message ?: error.javaClass.simpleName
            }
        }, "bridge-listener").apply { isDaemon = true }.start()
    }

    private fun handleClient(socket: Socket) {
        var handedOff = false
        var requestId = 0
        try {
            socket.soTimeout = 3000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val request = BridgeProtocol.readRequest(input)
            requestId = request.requestId
            if (!MessageDigest.isEqual(request.token, repository.token())) {
                BridgeProtocol.writeResponse(output, requestId, BridgeProtocol.Status.UNAUTHORIZED, "unauthorized")
                return
            }
            if (request.operation != BridgeProtocol.Operation.OPEN) {
                BridgeProtocol.writeResponse(output, requestId, BridgeProtocol.Status.BAD_REQUEST, "operation not implemented")
                return
            }
            if (request.baud !in 1200..4_000_000 || request.dataBits !in 5..8 ||
                request.stopBits !in 1..2 || request.parity !in 0..4 || request.flags and 0xfc != 0) {
                BridgeProtocol.writeResponse(output, requestId, BridgeProtocol.Status.BAD_REQUEST, "invalid serial parameters")
                return
            }
            val result = openUsbSession(request, socket, output)
            handedOff = result
        } catch (error: ProtocolException) {
            runCatching {
                BridgeProtocol.writeResponse(DataOutputStream(socket.getOutputStream()), requestId, error.status, error.message ?: "protocol error")
            }
        } catch (_: Exception) {
            runCatching {
                BridgeProtocol.writeResponse(DataOutputStream(socket.getOutputStream()), requestId, BridgeProtocol.Status.INTERNAL_ERROR, "request failed")
            }
        } finally {
            if (!handedOff) runCatching { socket.close() }
        }
    }

    @Synchronized
    private fun openUsbSession(
        request: BridgeProtocol.Request,
        socket: Socket,
        output: DataOutputStream,
    ): Boolean {
        val match = findPort(request.deviceId)
        if (match == null) {
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.DEVICE_NOT_FOUND, "no matching USB serial port attached")
            return false
        }
        val (profile, port) = match
        if (sessions.containsKey(profile.id)) {
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.DEVICE_BUSY, "device already open")
            return false
        }
        val device = port.driver.device
        if (!usbManager.hasPermission(device)) {
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.PERMISSION_REQUIRED, "grant USB permission in the app")
            return false
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.USB_ERROR, "Android could not open USB device")
            return false
        }
        return try {
            port.setReadQueue(2, 0)
            port.open(connection)
            port.setParameters(request.baud, request.dataBits, request.stopBits, request.parity)
            runCatching { port.dtr = request.flags and 1 != 0 }
            runCatching { port.rts = request.flags and 2 != 0 }
            socket.soTimeout = 0
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.OK)
            val counters = SessionCounters(profile.id, profile.alias)
            val session = UsbSession(socket, port, counters) { closed ->
                sessions.remove(profile.id, closed)
                BridgeState.sessions.remove(profile.id)
                updateWakeLock()
                updateNotification()
            }
            val previous = sessions.putIfAbsent(profile.id, session)
            if (previous != null) {
                session.close()
                false
            } else {
                BridgeState.sessions[profile.id] = counters
                BridgeState.lastUsbError = null
                updateWakeLock()
                updateNotification()
                session.start()
                true
            }
        } catch (error: Exception) {
            BridgeState.lastUsbError = "USB setup: ${error.message ?: error.javaClass.simpleName}"
            runCatching { port.close() }
            BridgeProtocol.writeResponse(output, request.requestId, BridgeProtocol.Status.USB_ERROR, error.message ?: "USB setup failed")
            false
        }
    }

    private fun findPort(id: UUID): Pair<DeviceProfile, UsbSerialPort>? {
        val automatic = id == BridgeProtocol.AUTO_DEVICE_ID
        var permissionCandidate: Pair<DeviceProfile, UsbSerialPort>? = null
        for (driver in UsbSerialDiscovery.findAllDrivers(usbManager, repository)) {
            for (port in driver.ports) {
                val profile = repository.profileFor(
                    driver.device,
                    port.portNumber,
                    create = automatic,
                ) ?: continue
                if (profile.id == id) return profile to port
                if (automatic) {
                    val candidate = profile to port
                    if (usbManager.hasPermission(driver.device)) return candidate
                    if (permissionCandidate == null) permissionCandidate = candidate
                }
            }
        }
        return permissionCandidate
    }

    private fun updateWakeLock() {
        if (sessions.isNotEmpty()) {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:usbBridge")
                    .also { it.acquire() }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.service_channel_description) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingIntentFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, UsbBridgeService::class.java).setAction(ACTION_STOP), pendingIntentFlags(),
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Klipper USB Bridge")
            .setContentText("${sessions.size} active USB connection(s)")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        stopped.set(true)
        runCatching { server?.close() }
        sessions.values.toList().forEach(UsbSession::close)
        sessions.clear()
        clients.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        BridgeState.sessions.clear()
        BridgeState.serviceRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.klipper.androidbridge.STOP"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1001
        fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE
    }
}
