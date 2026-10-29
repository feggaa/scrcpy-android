package tech.devline.scropy_ui.adb

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket

/**
 * ADB transport over a plain TCP socket — the classic `adb tcpip 5555` mode.
 *
 * This is what Android 10 and older use for network debugging (there is no
 * Wireless debugging / pairing before Android 11), and what any newer device
 * uses after `adb tcpip <port>`. No TLS: just the ADB protocol with RSA AUTH,
 * exactly like USB.
 */
class PlainTcpAdbTransport private constructor(private val socket: Socket) : AdbTransport {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Open a TCP socket to [host]:[port]. Throws an IOException with a readable message. */
        fun open(host: String, port: Int): PlainTcpAdbTransport {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: NoRouteToHostException) {
                runCatching { socket.close() }
                throw IOException(
                    "TCP connect failed: No route to host — this phone can't reach $host. " +
                    "Check both phones are on the same Wi-Fi (not a guest network / AP isolation), " +
                    "VPN is off, and the IP is still current.", e)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw IOException("TCP connect failed: ${e.message}", e)
            }
            return PlainTcpAdbTransport(socket)
        }
    }

    private val input  = DataInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    override fun read(len: Int): ByteArray? = try {
        ByteArray(len).also { input.readFully(it) }
    } catch (_: IOException) {
        null
    }

    override fun write(data: ByteArray): Boolean = try {
        output.write(data)
        output.flush()
        true
    } catch (_: IOException) {
        false
    }

    override fun shutdown() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
