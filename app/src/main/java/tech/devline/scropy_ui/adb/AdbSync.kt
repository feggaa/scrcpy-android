package tech.devline.scropy_ui.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB SYNC protocol client.
 *
 * Open a stream to "sync:", wrap it in this class, then call [pushFile].
 * The SYNC stream is stateful — create a new instance per sync session.
 */
class AdbSync(private val inp: InputStream, private val out: OutputStream) {

    companion object {
        private const val CHUNK = 64 * 1024   // max DATA chunk
    }

    /**
     * Push [data] bytes to [remotePath] on the device.
     * [mode] is the Unix file permission (e.g. 0o644).
     */
    fun pushFile(data: ByteArray, remotePath: String, mode: Int = 0x1A4 /* 0644 */) {
        val pathMode = "$remotePath,$mode"

        // SEND <len> <path,mode>
        writeId("SEND", pathMode.length)
        out.write(pathMode.toByteArray(Charsets.UTF_8))

        // DATA chunks
        var offset = 0
        while (offset < data.size) {
            val end  = minOf(offset + CHUNK, data.size)
            val size = end - offset
            writeId("DATA", size)
            out.write(data, offset, size)
            offset = end
        }

        // DONE <mtime>
        writeId("DONE", (System.currentTimeMillis() / 1000).toInt())
        out.flush()

        // Read response
        val resp = readResponse()
        if (resp.id != "OKAY")
            throw IOException("SYNC push failed: ${resp.message}")
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun writeId(id: String, value: Int) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(id.toByteArray(Charsets.UTF_8))
        buf.putInt(value)
        out.write(buf.array())
    }

    private data class SyncResp(val id: String, val message: String = "")

    private fun readResponse(): SyncResp {
        val hdr = readN(8)
        val id  = String(hdr, 0, 4, Charsets.UTF_8)
        val len = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return if (id == "FAIL" && len > 0) {
            SyncResp(id, String(readN(len), Charsets.UTF_8))
        } else {
            SyncResp(id)
        }
    }

    private fun readN(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("SYNC stream closed")
            off += r
        }
        return buf
    }
}
