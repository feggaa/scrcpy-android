package tech.devline.scropy_ui.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A full ADB connection over any transport (plain TCP, TLS or USB).
 *
 * This class handles CNXN, AUTH and stream management over the given [AdbTransport].
 */
class AdbConnection private constructor(
    private val transport: AdbTransport,
    private val privKey: java.security.PrivateKey,
    private val pubKey:  ByteArray,
) {
    companion object {
        private const val TAG = "AdbConnection"

        /**
         * Connect to the ADB daemon on [host]:[port].
         *
         * Tries plain TCP first (Android 10 and older, or any device after
         * `adb tcpip 5555`) — no pairing needed, the device just shows the
         * "Allow USB debugging?" prompt the first time. If the daemon answers
         * with STLS (Android 11+ Wireless debugging), reconnects over TLS,
         * which requires the device to have been paired first.
         */
        suspend fun connectTcp(
            context: Context,
            host: String,
            port: Int = 5555,
        ): AdbConnection = withContext(Dispatchers.IO) {
            val (priv, pub) = AdbAuthHelper.getOrCreateKeys(context)

            val plain = AdbConnection(PlainTcpAdbTransport.open(host, port), priv, pub)
            plain.sendCnxn()
            plain.startReader()
            try {
                plain.waitConnected()
            } catch (e: ClosedBeforeHandshakeException) {
                plain.close()
                if (plain.authSeen) {
                    // A plain daemon that hung up mid-AUTH: the prompt was denied/dismissed
                    throw IOException(
                        "Device closed the connection during authorization — " +
                        "tap \"Allow\" on the target device and try again", e)
                }
                // Some daemons drop a plain CNXN and expect TLS straight away
                Log.i(TAG, "Peer closed plain connection — trying TLS")
                return@withContext connectTls(host, port, priv, pub)
            } catch (e: IOException) {
                plain.close()
                throw IOException(
                    "ADB auth timed out — tap \"Allow\" on the target device " +
                    "(tick \"Always allow\") and try again", e)
            }
            if (!plain.tlsRequested) {
                Log.i(TAG, "Connected over plain TCP (legacy adb tcpip)")
                return@withContext plain
            }

            Log.i(TAG, "Device requires TLS (Wireless debugging) — reconnecting over TLS")
            plain.close()
            connectTls(host, port, priv, pub)
        }

        private fun connectTls(
            host: String,
            port: Int,
            priv: java.security.PrivateKey,
            pub: ByteArray,
        ): AdbConnection {
            val handle = AdbTlsSocket.connect(host, port, priv.encoded)
            val tlsTransport = TlsAdbTransport(handle)
            return AdbConnection(tlsTransport, priv, pub).also {
                if (!tlsTransport.isStlsPath) {
                    // TLS-first: need to send CNXN over TLS
                    it.sendCnxn()
                }
                // Start reader AFTER CNXN so the device has data to respond with
                it.startReader()
                it.waitConnected()
            }
        }

        /**
         * Connect to the ADB daemon over USB OTG.
         * No TLS — plain ADB protocol with RSA AUTH.
         */
        suspend fun connectUsb(
            context: Context,
            device: UsbDevice,
            usbManager: UsbManager,
        ): AdbConnection = withContext(Dispatchers.IO) {
            val (priv, pub) = AdbAuthHelper.getOrCreateKeys(context)
            val usbTransport = UsbAdbTransport.open(device, usbManager)
            AdbConnection(usbTransport, priv, pub).also {
                // USB always requires sending CNXN first (no STLS)
                it.sendCnxn()
                // Start reader AFTER CNXN so the device has data to respond with
                it.startReader()
                it.waitConnected()
            }
        }
    }

    private val localIdGen = AtomicInteger(1)
    private val streams    = ConcurrentHashMap<Int, AdbStream>()
    private val alive      = AtomicBoolean(true)
    private val writeLock  = Any()  // guards send() so header+data are atomic

    private val connectedLatch = CountDownLatch(1)
    /** Set when the daemon answered our plain CNXN with STLS (TLS required). */
    @Volatile private var tlsRequested = false
    /** Set once the daemon answered with CNXN or STLS. */
    @Volatile private var handshakeDone = false
    /** Set once the daemon sent any AUTH message (i.e. it speaks plain ADB). */
    @Volatile private var authSeen = false
    private val signatureSent = AtomicBoolean(false)
    private val pubKeySent    = AtomicBoolean(false)
    private lateinit var readerThread: Thread

    private fun startReader() {
        readerThread = Thread(::readLoop, "adb-reader").also { it.isDaemon = true; it.start() }
    }

    // ─── CNXN + Connection wait ─────────────────────────────────────────────

    private fun sendCnxn() {
        val banner = "host::features=stat_v2,ls_v2,apex,abb,fixed_push_symlink_timestamp"
        send(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.ADB_MAX_DATA,
             banner.toByteArray())
    }

    private fun waitConnected() {
        if (!connectedLatch.await(30, TimeUnit.SECONDS))
            throw IOException("ADB auth timed out (re-pair needed?)")
        if (!handshakeDone) throw ClosedBeforeHandshakeException()
    }

    /** The daemon closed the connection before answering CNXN with CNXN or STLS. */
    private class ClosedBeforeHandshakeException :
        IOException("Device closed the connection during the ADB handshake")

    // ─── Reader loop ────────────────────────────────────────────────────────

    private fun readLoop() {
        try {
            while (alive.get()) {
                val msg = readMsg() ?: break
                dispatch(msg)
            }
        } catch (e: IOException) {
            if (alive.get()) Log.e(TAG, "ADB read error", e)
        } finally {
            alive.set(false)
            connectedLatch.countDown()   // don't leave waitConnected() hanging
            streams.values.forEach { it.onClose() }
        }
    }

    private fun readMsg(): AdbProtocol.Msg? {
        val hdr = transport.read(24) ?: return null
        if (hdr.size < 24) return null
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int; val a0 = buf.int; val a1 = buf.int
        val len = buf.int; buf.int; buf.int   // skip checksum + magic
        val data = if (len > 0) {
            transport.read(len) ?: return null
        } else ByteArray(0)
        return AdbProtocol.Msg(cmd, a0, a1, data)
    }

    private fun dispatch(msg: AdbProtocol.Msg) {
        if (msg.cmd == AdbProtocol.A_AUTH) authSeen = true
        when (msg.cmd) {
            // ── Connection / auth ────────────────────────────────────────
            AdbProtocol.A_CNXN -> {
                Log.i(TAG, "ADB connected: ${String(msg.data)}")
                handshakeDone = true
                connectedLatch.countDown()
            }
            AdbProtocol.A_STLS -> {
                tlsRequested = true
                handshakeDone = true
                connectedLatch.countDown()
            }
            AdbProtocol.A_AUTH -> when (msg.arg0) {
                AdbProtocol.ADB_AUTH_TOKEN -> {
                    if (!signatureSent.getAndSet(true)) {
                        // First AUTH_TOKEN — try signing with our key
                        val sig = AdbAuthHelper.sign(privKey, msg.data)
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, sig)
                    } else if (!pubKeySent.getAndSet(true)) {
                        // Signature rejected — send our public key once
                        Log.i(TAG, "Sending ADB public key — please tap Allow on the device")
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, pubKey)
                    } else {
                        // Public key already sent, waiting for user to Accept
                        Log.d(TAG, "Ignoring AUTH_TOKEN — waiting for user to Allow")
                    }
                }
                else -> {
                    if (!pubKeySent.getAndSet(true)) {
                        Log.i(TAG, "Sending ADB public key — please tap Allow on the device")
                        send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, pubKey)
                    } else {
                        Log.d(TAG, "Ignoring AUTH — waiting for user to Allow")
                    }
                }
            }

            // ── Stream events ────────────────────────────────────────────
            AdbProtocol.A_OKAY -> {
                val stream = streams[msg.arg1] ?: return
                stream.remoteId = msg.arg0
                stream.onOkay()
            }
            AdbProtocol.A_WRTE -> {
                val stream = streams[msg.arg1] ?: return
                stream.onData(msg.data)
            }
            AdbProtocol.A_CLSE -> {
                streams.remove(msg.arg1)?.onClose()
            }
            else -> Log.w(TAG, "Unknown ADB cmd 0x${msg.cmd.toString(16)}")
        }
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    fun open(service: String): AdbStream {
        val localId = localIdGen.getAndIncrement()
        val stream  = AdbStream(localId)

        stream.writeToDevice   = { data -> send(AdbProtocol.A_WRTE, localId, stream.remoteId, data) }
        stream.sendOkay        = {        send(AdbProtocol.A_OKAY, localId, stream.remoteId)        }
        stream.onCloseCallback = {
            streams.remove(localId)
            send(AdbProtocol.A_CLSE, localId, stream.remoteId)
        }

        streams[localId] = stream
        send(AdbProtocol.A_OPEN, localId, 0, (service + "\u0000").toByteArray())

        stream.waitReady()
        return stream
    }

    fun close() {
        alive.set(false)
        transport.shutdown()   // unblock read
        try { readerThread.join(5000) } catch (_: InterruptedException) {}
        transport.close()     // release resources
    }

    // ─── Internal helpers ──────────────────────────────────────────────────

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        try {
            val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).also {
                it.putInt(cmd); it.putInt(arg0); it.putInt(arg1)
                it.putInt(data.size); it.putInt(sum); it.putInt(cmd xor -1)
            }.array()
            // Serialize all writes so header+data pairs never interleave
            // between threads (reader sending OKAY vs. writer sending WRTE).
            synchronized(writeLock) {
                if (!transport.write(hdr)) {
                    throw IOException("Transport write failed (header)")
                }
                if (data.isNotEmpty()) {
                    if (!transport.write(data)) {
                        throw IOException("Transport write failed (data)")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "ADB write error", e)
            alive.set(false)
        }
    }
}
