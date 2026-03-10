package tech.devline.scropy_ui.scrcpy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.AdbStream
import tech.devline.scropy_ui.adb.AdbSync
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TAG = "ScrcpySession"

/** Describes the remote device after a successful connection. */
data class DeviceInfo(
    val name: String,
    val videoCodec: Int,
    val audioCodec: Int,    // 0 if audio disabled
    val width: Int,
    val height: Int,
)

/**
 * Manages the full scrcpy session lifecycle:
 *  1. Push the embedded scrcpy-server binary to the device via ADB SYNC
 *  2. Launch the server via ADB shell
 *  3. Open three ADB streams (video / audio / control) to the server's local socket
 *  4. Return open streams for [VideoDecoder], [AudioPlayer] and [ControlSender]
 */
class ScrcpySession private constructor(
    val deviceInfo: DeviceInfo,
    val videoStream:   AdbStream,
    val audioStream:   AdbStream?,
    val controlStream: AdbStream,
) {

    companion object {

        private const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SERVER_ASSET       = "scrcpy-server"
        private const val SCRCPY_VERSION     = "3.3.4"

        /**
         * Connect to a running [adb] session and start a scrcpy session.
         *
         * @param enableAudio  Request audio forwarding (Android 11+).
         * @param maxSize      0 = no limit; otherwise long-edge pixel cap.
         */
        suspend fun start(
            context: Context,
            adb: AdbConnection,
            enableAudio: Boolean = true,
            maxSize: Int = 0,
        ): ScrcpySession = withContext(Dispatchers.IO) {

            // ── 1. Push server JAR (skip if already up-to-date) ────────────
            val serverBytes = loadServerAsset(context)
            if (needsPush(adb, serverBytes)) {
                Log.d(TAG, "Pushing server (${serverBytes.size} bytes)…")
                val syncStream = adb.open("sync:")
                AdbSync(syncStream.inputStream, syncStream.outputStream)
                    .pushFile(serverBytes, DEVICE_SERVER_PATH)
                syncStream.close()
                Log.d(TAG, "Server pushed.")
            } else {
                Log.d(TAG, "Server already up-to-date, skipping push.")
            }

            // ── 2. Generate a random session ID (31-bit, server uses Integer.parseInt) ─
            val scid = (Math.random() * 0x7FFFFFFFL).toLong().and(0x7FFFFFFFL)
            val scidHex = "%08x".format(scid)
            val socketName = "scrcpy_$scidHex"

            // ── 3. Build shell command ───────────────────────────────────────
            val cmdParts = mutableListOf(
                "CLASSPATH=$DEVICE_SERVER_PATH",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                SCRCPY_VERSION,
                "scid=$scidHex",
                "log_level=info",
                "video=true",
                "control=true",
                "tunnel_forward=true",
            )
            if (!enableAudio) cmdParts += "audio=false"
            if (maxSize > 0) cmdParts += "max_size=$maxSize"

            val shellCmd = "shell:" + cmdParts.joinToString(" ")
            Log.d(TAG, "Starting server: $shellCmd")
            val shellStream = adb.open(shellCmd)

            // Read server stdout/stderr in background for diagnostics
            Thread({
                try {
                    val buf = ByteArray(1024)
                    while (!shellStream.isClosed()) {
                        val r = shellStream.inputStream.read(buf)
                        if (r <= 0) break
                        Log.i("ScrcpyServer", String(buf, 0, r).trimEnd())
                    }
                } catch (_: Exception) {}
            }, "scrcpy-server-log").apply { isDaemon = true; start() }

            // ── 4. Connect stream sockets (with retry) ───────────────────────
            // The server needs time to start and create its LocalServerSocket.
            // Retry up to ~5 s (50 × 100 ms).
            fun openWithRetry(dest: String): AdbStream {
                var lastErr: Exception? = null
                repeat(50) {
                    try {
                        return adb.open(dest)
                    } catch (e: IOException) {
                        lastErr = e
                        Thread.sleep(100)
                    }
                }
                throw lastErr ?: IOException("Failed to connect to $dest")
            }

            val socketDest = "localabstract:$socketName"
            val videoStream   = openWithRetry(socketDest)
            Log.d(TAG, "Video socket connected")
            val audioStream   = if (enableAudio) openWithRetry(socketDest) else null
            if (enableAudio) Log.d(TAG, "Audio socket connected")
            val controlStream = openWithRetry(socketDest)
            Log.d(TAG, "Control socket connected")

            // ── 5. Read handshake from video socket ──────────────────────────
            val dis = DataInputStream(videoStream.inputStream)

            // Discard the 1-byte dummy (send_dummy_byte=true by default)
            dis.read()

            // 64-byte device name (null-padded UTF-8)
            val nameBytes = ByteArray(ScrcpyProtocol.DEVICE_NAME_LEN)
            dis.readFully(nameBytes)
            val deviceName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')

            // Video codec meta: codec_id(4) + width(4) + height(4) big-endian
            val codecId = dis.readInt()
            val width   = dis.readInt()
            val height  = dis.readInt()
            Log.i(TAG, "Device: $deviceName  codec=0x${codecId.toString(16)}  ${width}x${height}")

            // Read audio codec ID (first 4 bytes of audio socket)
            // 0x0 = disabled (device does not support audio forwarding, requires Android 11+)
            // 0x1 = configuration error
            val audioCodecId = if (audioStream != null) {
                try {
                    val id = DataInputStream(audioStream.inputStream).readInt()
                    when (id) {
                        0 -> Log.w(TAG, "Audio disabled by server (requires Android 11+)")
                        1 -> Log.w(TAG, "Audio error on server (configuration error)")
                        else -> Log.i(TAG, "Audio codec id: 0x${id.toString(16)}")
                    }
                    id
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read audio codec id", e)
                    0
                }
            } else 0

            val info = DeviceInfo(deviceName, codecId, audioCodecId, width, height)
            ScrcpySession(info, videoStream, audioStream, controlStream)
        }

        private fun loadServerAsset(context: Context): ByteArray {
            return runCatching {
                context.assets.open(SERVER_ASSET).use { it.readBytes() }
            }.getOrElse {
                throw IOException(
                    "scrcpy-server not found in app assets.\n" +
                    "Build it first:\n" +
                    "  cd /path/to/scrcpy && ./gradlew :server:assembleRelease\n" +
                    "Then rebuild this app."
                )
            }
        }

        /**
         * Check if the server JAR on the device is already up-to-date.
         * Compares md5 checksum to avoid redundant pushes.
         */
        private fun needsPush(adb: AdbConnection, localBytes: ByteArray): Boolean {
            return try {
                val localMd5 = md5Hex(localBytes)
                val stream = adb.open("shell:md5sum $DEVICE_SERVER_PATH 2>/dev/null")
                val buf = StringBuilder()
                val inp = stream.inputStream
                val tmp = ByteArray(512)
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val avail = inp.available()
                    if (avail > 0) {
                        val r = inp.read(tmp, 0, minOf(avail, tmp.size))
                        if (r > 0) buf.append(String(tmp, 0, r))
                    } else if (stream.isClosed()) {
                        break
                    } else {
                        Thread.sleep(50)
                    }
                }
                stream.close()
                val remoteMd5 = buf.toString().trim().split("\\s+".toRegex()).firstOrNull() ?: ""
                Log.d(TAG, "Server md5 local=$localMd5 remote=$remoteMd5")
                localMd5 != remoteMd5
            } catch (e: Exception) {
                Log.w(TAG, "Could not check remote server, will push", e)
                true
            }
        }

        private fun md5Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    fun close() {
        runCatching { videoStream.close() }
        runCatching { audioStream?.close() }
        runCatching { controlStream.close() }
    }
}
