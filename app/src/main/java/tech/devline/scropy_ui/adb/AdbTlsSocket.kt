package tech.devline.scropy_ui.adb

import java.io.IOException

/**
 * JNI wrapper for ADB TLS connections.
 *
 * The native code handles:
 *  - TCP connect
 *  - ADB CNXN exchange (plain, with "tls" feature)
 *  - STLS negotiation
 *  - TLS 1.3 handshake (client cert from RSA key)
 *
 * After [connect], use [read]/[write] for ADB protocol over TLS,
 * and [close] when done.
 */
object AdbTlsSocket {

    init {
        try {
            System.loadLibrary("adb_pairing")
        } catch (e: UnsatisfiedLinkError) {
            tech.devline.scropy_ui.App.writeDiag("AdbTlsSocket: loadLibrary FAILED: $e")
            throw e
        }
    }

    /**
     * Connect to ADB daemon, negotiate STLS, and complete TLS handshake.
     * Returns an opaque handle for subsequent [read]/[write]/[close] calls.
     * @throws IOException on any failure (DNS, TCP, STLS, TLS handshake)
     */
    @Throws(IOException::class)
    private external fun nativeConnect(host: String, port: Int, rsaPrivKeyDer: ByteArray): Long

    /** Read exactly [len] bytes over TLS. Returns null on error/EOF. */
    private external fun nativeRead(handle: Long, len: Int): ByteArray?

    /** Write all bytes over TLS. Returns false on error. */
    private external fun nativeWrite(handle: Long, data: ByteArray): Boolean

    /** Shutdown the socket to unblock reads, without freeing resources. */
    private external fun nativeShutdown(handle: Long)

    /** Close the TLS connection and free native resources. */
    private external fun nativeClose(handle: Long)

    /** Returns true if the connection used STLS upgrade (CNXN already exchanged). */
    private external fun nativeIsStlsPath(handle: Long): Boolean

    @Throws(IOException::class)
    fun connect(host: String, port: Int, rsaPrivKeyDer: ByteArray): Long =
        nativeConnect(host, port, rsaPrivKeyDer)

    fun read(handle: Long, len: Int): ByteArray? = nativeRead(handle, len)
    fun write(handle: Long, data: ByteArray): Boolean = nativeWrite(handle, data)
    fun shutdownSocket(handle: Long) = nativeShutdown(handle)
    fun close(handle: Long) = nativeClose(handle)
    fun isStlsPath(handle: Long): Boolean = nativeIsStlsPath(handle)
}
