package tech.devline.scropy_ui.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer

private const val TAG = "VideoDecoder"

/**
 * Reads the scrcpy video stream from an [AdbStream] and decodes it
 * with [MediaCodec], rendering output to a [Surface].
 *
 * The raw stream format (after the session handshake) is:
 *   [pts_flags: int64 BE] [size: int32 BE] [data: <size> bytes]   (repeated)
 *
 * Bit 63 of pts_flags = CONFIG packet, bit 62 = KEY_FRAME.
 */
class VideoDecoder(
    private val stream: AdbStream,
    private val codecId: Int,
    private val width: Int,
    private val height: Int,
) {
    @Volatile var isRunning = false
        private set

    @Volatile var framesDecoded = 0L
        private set

    private var codec: MediaCodec? = null
    private val codecLock = Object()
    private var mimeType: String? = null

    /** Cached config packet (SPS/PPS) to re-feed after codec restart. */
    private var lastConfigData: ByteArray? = null

    /**
     * Start decoding and rendering to [surface].
     * This suspends (blocks the IO dispatcher) until the stream closes or
     * [stop] is called.
     */
    suspend fun start(surface: Surface) = withContext(Dispatchers.IO) {
        mimeType = when (codecId) {
            ScrcpyProtocol.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            ScrcpyProtocol.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ScrcpyProtocol.CODEC_AV1  -> "video/av01"
            else -> {
                Log.e(TAG, "Unsupported video codec 0x${codecId.toString(16)}")
                return@withContext
            }
        }

        synchronized(codecLock) { configureCodec(surface) }

        isRunning = true
        val dis = DataInputStream(stream.inputStream)

        try {
            while (isRunning && !stream.isClosed()) {
                val ptsFlags = dis.readLong()
                val dataSize = dis.readInt()

                if (dataSize <= 0 || dataSize > 4 * 1024 * 1024) {
                    Log.w(TAG, "Invalid packet size $dataSize, skipping")
                    continue
                }

                val isConfig = (ptsFlags and ScrcpyProtocol.FLAG_CONFIG) != 0L
                val pts      = if (isConfig) 0L else (ptsFlags and ScrcpyProtocol.PTS_MASK)
                val data     = ByteArray(dataSize)
                dis.readFully(data)

                if (isConfig) lastConfigData = data.clone()

                synchronized(codecLock) {
                    val mc = codec ?: return@synchronized // no codec → discard
                    try {
                        feedToCodec(mc, data, pts, isConfig)
                        drainCodec(mc)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Codec error (surface destroyed?), releasing codec", e)
                        runCatching { mc.stop(); mc.release() }
                        codec = null
                    }
                }
            }
        } catch (_: IOException) {
            // Stream closed normally
        } catch (e: Exception) {
            Log.e(TAG, "Video decode error", e)
        } finally {
            isRunning = false
            synchronized(codecLock) {
                runCatching { codec?.stop(); codec?.release() }
                codec = null
            }
        }
    }

    /** Release the codec (call before the surface is destroyed). */
    fun releaseCodec() {
        synchronized(codecLock) {
            codec?.let { mc ->
                Log.d(TAG, "Releasing codec")
                runCatching { mc.stop(); mc.release() }
                codec = null
            }
        }
    }

    /** Reconfigure and start the codec with a new surface. */
    fun restartCodec(surface: Surface) {
        synchronized(codecLock) {
            codec?.let { mc ->
                runCatching { mc.stop(); mc.release() }
                codec = null
            }
            configureCodec(surface)
            // Re-feed cached config (SPS/PPS) so decoder is ready for next keyframe
            lastConfigData?.let { cfg ->
                val mc = codec ?: return@let
                feedToCodec(mc, cfg, 0L, isConfig = true)
                Log.d(TAG, "Re-fed config packet (${cfg.size} bytes)")
            }
            Log.d(TAG, "Codec restarted with new surface")
        }
    }

    fun stop() {
        isRunning = false
        runCatching { stream.close() }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun configureCodec(surface: Surface) {
        val mime = mimeType ?: return
        val format = MediaFormat.createVideoFormat(mime, width, height)
        codec = MediaCodec.createDecoderByType(mime).also { mc ->
            mc.configure(format, surface, null, 0)
            mc.start()
        }
    }

    private fun feedToCodec(mc: MediaCodec, data: ByteArray, pts: Long, isConfig: Boolean) {
        val timeout = 5_000L   // µs
        val idx     = mc.dequeueInputBuffer(timeout)
        if (idx < 0) { Log.w(TAG, "No input buffer available"); return }

        val buf = mc.getInputBuffer(idx) ?: return
        buf.clear()
        buf.put(data)

        val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        mc.queueInputBuffer(idx, 0, data.size, pts, flags)
    }

    private fun drainCodec(mc: MediaCodec) {
        val info    = MediaCodec.BufferInfo()
        val timeout = 0L   // non-blocking
        while (true) {
            val idx = mc.dequeueOutputBuffer(info, timeout)
            when {
                idx >= 0 -> {
                    val render = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    mc.releaseOutputBuffer(idx, render)
                    if (render) framesDecoded++
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Output format: ${mc.outputFormat}")
                }
                else -> break
            }
        }
    }
}
