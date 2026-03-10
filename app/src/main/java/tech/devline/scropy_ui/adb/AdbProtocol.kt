package tech.devline.scropy_ui.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Low-level ADB message format helpers. */
internal object AdbProtocol {

    // ADB command identifiers (little-endian ASCII in uint32)
    const val A_CNXN = 0x4E584E43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4E45504F
    const val A_OKAY = 0x59414B4F
    const val A_CLSE = 0x45534C43
    const val A_WRTE = 0x45545257

    // AUTH arg0 subtypes
    const val ADB_AUTH_TOKEN        = 1
    const val ADB_AUTH_SIGNATURE    = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    const val A_VERSION  = 0x01000000
    const val ADB_MAX_DATA = 1 shl 20   // 1 MB

    private const val HEADER = 24

    data class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0))

    /** Write an ADB message synchronised on [out]. */
    fun write(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).also {
            it.putInt(cmd); it.putInt(arg0); it.putInt(arg1)
            it.putInt(data.size); it.putInt(sum); it.putInt(cmd xor -1)
        }.array()
        synchronized(out) {
            out.write(hdr)
            if (data.isNotEmpty()) out.write(data)
            out.flush()
        }
    }

    /** Blocking read of one ADB message from [inp]. */
    fun read(inp: InputStream): Msg {
        val hdr = inp.readN(HEADER)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int; val a0 = buf.int; val a1 = buf.int
        val len = buf.int; buf.int; buf.int   // skip checksum + magic
        val data = if (len > 0) inp.readN(len) else ByteArray(0)
        return Msg(cmd, a0, a1, data)
    }

    private fun InputStream.readN(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw IOException("ADB connection closed")
            off += r
        }
        return buf
    }
}
