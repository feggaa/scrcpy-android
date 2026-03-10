package tech.devline.scropy_ui.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * ADB transport over USB OTG using Android USB Host API.
 *
 * ADB USB interface: class=0xFF, subclass=0x42, protocol=0x01
 * Uses two bulk endpoints (IN + OUT) for ADB message framing.
 * No TLS — plain ADB protocol with RSA AUTH.
 */
class UsbAdbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbTransport {

    companion object {
        private const val TAG = "UsbAdbTransport"

        // ADB USB interface identifiers (from AOSP adb.h)
        private const val ADB_CLASS    = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        private const val TIMEOUT_MS = 5000
        private const val USB_WRITE_CHUNK = 16384  // 16 KB max per bulkTransfer

        // USB standard request: CLEAR_FEATURE(ENDPOINT_HALT)
        private const val USB_REQ_CLEAR_FEATURE = 0x01
        private const val USB_FEATURE_HALT = 0

        private fun clearHalt(conn: UsbDeviceConnection, ep: UsbEndpoint) {
            val dir = if (ep.direction == UsbConstants.USB_DIR_IN)
                UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
            val rc = conn.controlTransfer(
                dir or UsbConstants.USB_TYPE_STANDARD or 0x02, // RECIP_ENDPOINT
                USB_REQ_CLEAR_FEATURE,
                USB_FEATURE_HALT,
                ep.address,
                null, 0, TIMEOUT_MS
            )
            Log.d(TAG, "clearHalt ep=0x${ep.address.toString(16)} rc=$rc")
        }

        /**
         * Find the ADB interface on [device], claim it, and return a transport.
         * @throws IOException if not an ADB device or cannot claim interface.
         */
        fun open(device: UsbDevice, connection: UsbDeviceConnection): UsbAdbTransport {
            // Find ADB interface
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (isAdbInterface(iface)) {
                    return openInterface(connection, iface)
                }
            }
            throw IOException("No ADB interface found on ${device.deviceName}")
        }

        private fun isAdbInterface(iface: UsbInterface): Boolean {
            return iface.interfaceClass == ADB_CLASS &&
                   iface.interfaceSubclass == ADB_SUBCLASS &&
                   iface.interfaceProtocol == ADB_PROTOCOL
        }

        private fun openInterface(
            connection: UsbDeviceConnection,
            iface: UsbInterface,
        ): UsbAdbTransport {
            if (!connection.claimInterface(iface, true)) {
                throw IOException("Failed to claim ADB interface")
            }

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN  -> if (epIn == null) epIn = ep
                    UsbConstants.USB_DIR_OUT -> if (epOut == null) epOut = ep
                }
            }

            if (epIn == null || epOut == null) {
                connection.releaseInterface(iface)
                throw IOException("ADB interface missing bulk endpoints")
            }

            // Clear any stale halt/stall on both endpoints
            clearHalt(connection, epIn)
            clearHalt(connection, epOut)

            Log.i(TAG, "ADB USB interface claimed: " +
                       "IN=0x${epIn.address.toString(16)} OUT=0x${epOut.address.toString(16)} " +
                       "maxPacket=${epIn.maxPacketSize}")
            return UsbAdbTransport(connection, iface, epIn, epOut)
        }
    }

    @Volatile private var closed = false

    override fun read(len: Int): ByteArray? {
        if (closed) return null
        val result = ByteArray(len)
        var offset = 0
        while (offset < len) {
            if (closed) return null
            val remaining = len - offset
            val buf = ByteBuffer.allocate(remaining)
            val req = UsbRequest()
            try {
                if (!req.initialize(connection, epIn)) {
                    Log.e(TAG, "UsbRequest.initialize failed for IN ep")
                    return null
                }
                if (!req.queue(buf)) {
                    Log.e(TAG, "UsbRequest.queue failed")
                    return null
                }
                // Loop with short timeouts so we can check `closed` flag
                // while waiting for data (e.g. user tapping "Allow USB debugging")
                while (true) {
                    if (closed) {
                        req.cancel()
                        return null
                    }
                    try {
                        val completed = connection.requestWait(TIMEOUT_MS.toLong())
                        if (completed == null) {
                            if (closed) return null
                            Log.e(TAG, "requestWait returned null")
                            return null
                        }
                        break // success
                    } catch (_: TimeoutException) {
                        // Timeout waiting for data — keep waiting unless closed
                        if (closed) {
                            req.cancel()
                            return null
                        }
                        // Continue waiting
                    }
                }
                buf.flip()
                val bytesRead = buf.remaining()
                if (bytesRead == 0) {
                    Log.e(TAG, "USB read returned 0 bytes")
                    return null
                }
                buf.get(result, offset, bytesRead)
                offset += bytesRead
            } catch (e: Exception) {
                if (!closed) Log.e(TAG, "USB read error", e)
                return null
            } finally {
                req.close()
            }
        }
        return result
    }

    override fun write(data: ByteArray): Boolean {
        if (closed) return false
        var offset = 0
        while (offset < data.size) {
            // Chunk USB bulk transfers to 16 KB to avoid failures on some devices
            val remaining = minOf(data.size - offset, USB_WRITE_CHUNK)
            val n = connection.bulkTransfer(epOut, data, offset, remaining, TIMEOUT_MS)
            if (n < 0) {
                Log.e(TAG, "USB bulk write failed at offset=$offset, size=${data.size}")
                return false
            }
            offset += n
        }
        Log.d(TAG, "USB write OK: ${data.size} bytes")
        // Send ZLP if payload is exact multiple of max packet size
        if (data.isNotEmpty() && data.size % epOut.maxPacketSize == 0) {
            connection.bulkTransfer(epOut, ByteArray(0), 0, 0, TIMEOUT_MS)
        }
        return true
    }

    override fun shutdown() {
        closed = true
    }

    override fun close() {
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }
}
