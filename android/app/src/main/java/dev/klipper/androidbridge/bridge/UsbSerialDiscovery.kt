package dev.klipper.androidbridge.bridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

enum class UsbSerialDriverKind(val displayName: String) {
    CDC_ACM("CDC / ACM"),
    CH341("CH341"),
    CP21XX("CP210x"),
    FTDI("FTDI"),
    PROLIFIC("Prolific"),
}

/**
 * Discovers every Android USB device, then binds either an automatically
 * detected driver or a persisted manual override. Klipper's default USB ID is
 * force-bound to CDC/ACM because older firmware descriptors are not accepted
 * by the strict class-based probe on every Android USB stack.
 */
object UsbSerialDiscovery {
    const val KLIPPER_VENDOR_ID = 0x1d50
    const val KLIPPER_PRODUCT_ID = 0x614e

    fun findAllDrivers(manager: UsbManager, repository: DeviceRepository): List<UsbSerialDriver> {
        val prober = UsbSerialProber.getDefaultProber()
        return manager.deviceList.values
            .sortedBy { it.deviceId }
            .mapNotNull { device ->
                val forced = repository.driverOverride(device)
                    ?: defaultKind(device.vendorId, device.productId)
                if (forced != null) createDriver(device, forced) else prober.probeDevice(device)
            }
    }

    fun defaultKind(vendorId: Int, productId: Int): UsbSerialDriverKind? =
        if (vendorId == KLIPPER_VENDOR_ID && productId == KLIPPER_PRODUCT_ID) {
            UsbSerialDriverKind.CDC_ACM
        } else {
            null
        }

    fun createDriver(device: UsbDevice, kind: UsbSerialDriverKind): UsbSerialDriver = when (kind) {
        UsbSerialDriverKind.CDC_ACM -> CdcAcmSerialDriver(device)
        UsbSerialDriverKind.CH341 -> Ch34xSerialDriver(device)
        UsbSerialDriverKind.CP21XX -> Cp21xxSerialDriver(device)
        UsbSerialDriverKind.FTDI -> FtdiSerialDriver(device)
        UsbSerialDriverKind.PROLIFIC -> ProlificSerialDriver(device)
    }
}
