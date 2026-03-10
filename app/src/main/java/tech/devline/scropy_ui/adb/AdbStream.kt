package tech.devline.scropy_ui.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Represents one logical ADB stream (one OPEN/WRTE/OKAY/CLSE conversation).
 * Data received from the device is readable via [inputStream].
 * Use [write] to send data to the device.
 */
class AdbStream internal constructor(val localId: Int) {

    @Volatile internal var remoteId: Int = 0

    private val pipeOut  = PipedOutputStream()
    /** Read device→app data from this stream. */
    val inputStream: InputStream = PipedInputStream(pipeOut, 256 * 1024)

    companion object {
        // ADB max payload per WRTE message (safe for all devices).
        internal const val MAX_PAYLOAD = 4096
    }

    /** Writable side — uses flow-controlled [write] internally. */
    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@AdbStream.write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            val end = off + len
            while (pos < end) {
                val chunk = minOf(end - pos, MAX_PAYLOAD)
                this@AdbStream.write(b.copyOfRange(pos, pos + chunk))
                pos += chunk
            }
        }
        override fun write(b: ByteArray) = write(b, 0, b.size)
    }

    // Flow-control: ADB requires waiting for OKAY before sending next WRTE
    // Starts at 0 — first permit is released when device sends OKAY for OPEN
    private val writeLock = Semaphore(0)
    private val readyLatch = CountDownLatch(1)

    @Volatile private var closed = false

    // Callbacks supplied by AdbConnection
    internal lateinit var writeToDevice: (ByteArray) -> Unit
    internal lateinit var sendOkay: () -> Unit
    internal lateinit var onCloseCallback: () -> Unit

    // ─── Called by AdbConnection dispatcher ──────────────────────────────

    internal fun onData(data: ByteArray) {
        try {
            pipeOut.write(data)
            pipeOut.flush()
            sendOkay()
        } catch (_: IOException) { close() }
    }

    internal fun onOkay() {
        readyLatch.countDown()
        writeLock.release()
    }

    internal fun onClose() {
        readyLatch.countDown()  // unblock waitReady if stream was rejected
        closed = true
        runCatching { pipeOut.close() }
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /** Block until the device acknowledges OPEN (sets remoteId). */
    fun waitReady(timeoutMs: Long = 5000) {
        if (!readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw IOException("ADB stream open timed out")
        if (closed) throw IOException("ADB stream was closed")
    }

    fun write(data: ByteArray) {
        if (closed) throw IOException("Stream closed")
        // Chunk to MAX_PAYLOAD — each chunk is a separate WRTE/OKAY cycle
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_PAYLOAD, data.size)
            val chunk = if (offset == 0 && end == data.size) data else data.copyOfRange(offset, end)
            if (!writeLock.tryAcquire(5, TimeUnit.SECONDS))
                throw IOException("ADB WRTE timed out waiting for OKAY")
            writeToDevice(chunk)
            offset = end
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            onCloseCallback()
            runCatching { pipeOut.close() }
        }
    }

    fun isClosed() = closed
}
