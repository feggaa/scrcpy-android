package tech.devline.scropy_ui.adb

/**
 * Abstract transport for ADB protocol I/O.
 * Implementations: [TlsAdbTransport] (WiFi/TLS) and [UsbAdbTransport] (USB OTG).
 */
interface AdbTransport {
    /** Read exactly [len] bytes. Returns null on error/EOF. */
    fun read(len: Int): ByteArray?

    /** Write all bytes. Returns false on error. */
    fun write(data: ByteArray): Boolean

    /** Unblock any blocking read (e.g. shutdown socket). */
    fun shutdown()

    /** Release all resources. */
    fun close()
}
