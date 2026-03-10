package tech.devline.scropy_ui.adb

/**
 * ADB transport over TLS (wireless debugging).
 * Wraps the existing [AdbTlsSocket] JNI layer.
 */
class TlsAdbTransport(private val handle: Long) : AdbTransport {

    /** True if the connection used the STLS upgrade path (CNXN already exchanged). */
    val isStlsPath: Boolean get() = AdbTlsSocket.isStlsPath(handle)

    override fun read(len: Int): ByteArray? = AdbTlsSocket.read(handle, len)

    override fun write(data: ByteArray): Boolean = AdbTlsSocket.write(handle, data)

    override fun shutdown() = AdbTlsSocket.shutdownSocket(handle)

    override fun close() = AdbTlsSocket.close(handle)
}
