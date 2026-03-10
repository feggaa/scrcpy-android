package tech.devline.scropy_ui.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioPlayer"

/**
 * Reads the scrcpy audio stream, decodes it with [MediaCodec]
 * and plays it via [AudioTrack].
 *
 * Format: same packet structure as video — [pts_flags: int64 BE][size: int32 BE][data].
 *
 * For OPUS, the first config packet contains the raw OpusHead after the server's
 * fixOpusConfigPacket() strips the Android CSD wrapper. We must set CSD-0/1/2
 * in the MediaFormat before configuring the decoder.
 */
class AudioPlayer(private val stream: AdbStream, private val codecId: Int) {

    @Volatile var isRunning = false
        private set

    /** When paused, decoded audio is discarded instead of written to AudioTrack. */
    @Volatile var paused = false

    private var audioTrackRef: AudioTrack? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (codecId == 0) {
            Log.i(TAG, "Audio disabled by server (requires Android 11+)")
            return@withContext
        }
        if (codecId == 1) {
            Log.w(TAG, "Audio error on server (configuration error)")
            return@withContext
        }

        val mimeType = when (codecId) {
            ScrcpyProtocol.CODEC_OPUS -> "audio/opus"
            ScrcpyProtocol.CODEC_AAC  -> MediaFormat.MIMETYPE_AUDIO_AAC
            ScrcpyProtocol.CODEC_FLAC -> "audio/flac"
            ScrcpyProtocol.CODEC_RAW  -> null
            else -> {
                Log.e(TAG, "Unsupported audio codec 0x${codecId.toString(16)}")
                return@withContext
            }
        }
        Log.i(TAG, "Audio codec: $mimeType (0x${codecId.toString(16)})")

        if (mimeType == null) {
            startRaw(); return@withContext
        }

        val dis = DataInputStream(stream.inputStream)

        // ── Read the first config packet to set up the decoder properly ──
        val format = MediaFormat.createAudioFormat(mimeType, 48000, 2)
        val firstPacket = readFirstConfigPacket(dis)
        if (firstPacket != null) {
            Log.d(TAG, "Config packet: ${firstPacket.size} bytes")
            if (codecId == ScrcpyProtocol.CODEC_OPUS) {
                setupOpusCsd(format, firstPacket)
            } else {
                // AAC / FLAC: set raw config as csd-0
                format.setByteBuffer("csd-0", ByteBuffer.wrap(firstPacket))
            }
        } else {
            Log.w(TAG, "No config packet received, decoder may fail")
        }

        val codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(format, null, null, 0)
        codec.start()
        Log.i(TAG, "Audio MediaCodec started")

        val audioTrack = buildAudioTrack(48000, 2)
        audioTrackRef = audioTrack
        audioTrack.play()
        Log.i(TAG, "AudioTrack playing, state=${audioTrack.state}")

        isRunning = true
        try {
            val info = MediaCodec.BufferInfo()
            var packetCount = 0L
            while (isRunning && !stream.isClosed()) {
                val ptsFlags = dis.readLong()
                val dataSize = dis.readInt()
                if (dataSize <= 0) continue

                val data = ByteArray(dataSize); dis.readFully(data)
                val isConfig = (ptsFlags and ScrcpyProtocol.FLAG_CONFIG) != 0L
                if (isConfig) continue   // already consumed the config packet above

                packetCount++
                if (packetCount <= 3) Log.d(TAG, "Audio pkt #$packetCount size=$dataSize")

                // Feed
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) { Log.w(TAG, "No audio input buffer"); continue }
                val buf = codec.getInputBuffer(idx)!!
                buf.clear(); buf.put(data)
                val pts = ptsFlags and ScrcpyProtocol.PTS_MASK
                codec.queueInputBuffer(idx, 0, data.size, pts, 0)

                // Drain
                drainCodec(codec, info, audioTrack)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Audio stream ended", e)
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode error", e)
        } finally {
            isRunning = false
            runCatching { codec.stop(); codec.release() }
            runCatching { audioTrack.stop(); audioTrack.release() }
        }
    }

    fun stop() {
        isRunning = false
        runCatching { stream.close() }
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /** Read packets until we find the first config packet. */
    private fun readFirstConfigPacket(dis: DataInputStream): ByteArray? {
        // Try up to 10 packets looking for a config packet
        repeat(10) {
            val ptsFlags = dis.readLong()
            val dataSize = dis.readInt()
            if (dataSize <= 0) return@repeat
            val data = ByteArray(dataSize); dis.readFully(data)
            val isConfig = (ptsFlags and ScrcpyProtocol.FLAG_CONFIG) != 0L
            if (isConfig) return data
            // Non-config packet before config — unusual, skip
            Log.w(TAG, "Got non-config packet before config (size=$dataSize)")
        }
        return null
    }

    /**
     * Set OPUS CSD buffers in the MediaFormat.
     *
     * The scrcpy server sends the raw OpusHead (after fixOpusConfigPacket strips
     * the Android CSD wrapper). Android MediaCodec requires:
     *   csd-0: OpusHead bytes
     *   csd-1: pre-skip in nanoseconds (8 bytes, native byte order)
     *   csd-2: seek pre-roll in nanoseconds (8 bytes, native byte order)
     */
    private fun setupOpusCsd(format: MediaFormat, opusHead: ByteArray) {
        // csd-0: raw OpusHead
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))

        // Extract pre-skip from OpusHead (bytes 10-11, little-endian)
        val preSkipSamples = if (opusHead.size >= 12) {
            (opusHead[10].toInt() and 0xFF) or ((opusHead[11].toInt() and 0xFF) shl 8)
        } else 312  // default 6.5 ms at 48 kHz

        // csd-1: pre-skip in nanoseconds
        val preSkipNs = preSkipSamples.toLong() * 1_000_000_000L / 48000L
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd1.putLong(preSkipNs); csd1.flip()
        format.setByteBuffer("csd-1", csd1)

        // csd-2: seek pre-roll in nanoseconds (80 ms is standard for OPUS)
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        csd2.putLong(80_000_000L); csd2.flip()
        format.setByteBuffer("csd-2", csd2)

        Log.d(TAG, "OPUS CSD set: head=${opusHead.size}B preSkip=$preSkipSamples samples")
    }

    fun pauseAudio() {
        paused = true
        audioTrackRef?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                t.pause()
            }
        }
    }

    fun resumeAudio() {
        paused = false
        audioTrackRef?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                t.play()
            }
        }
    }

    private fun drainCodec(mc: MediaCodec, info: MediaCodec.BufferInfo, audioTrack: AudioTrack) {
        while (true) {
            val oi = mc.dequeueOutputBuffer(info, 0)
            when {
                oi >= 0 -> {
                    if (!paused && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val pcm = mc.getOutputBuffer(oi)!!
                        val pcmBytes = ByteArray(info.size)
                        pcm.position(info.offset); pcm.get(pcmBytes)
                        audioTrack.write(pcmBytes, 0, pcmBytes.size)
                    }
                    mc.releaseOutputBuffer(oi, false)
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Audio output format: ${mc.outputFormat}")
                }
                else -> break
            }
        }
    }

    private fun startRaw() {
        val audioTrack = buildAudioTrack(48000, 2)
        audioTrack.play()
        isRunning = true
        val dis = DataInputStream(stream.inputStream)
        try {
            while (isRunning && !stream.isClosed()) {
                dis.readLong()
                val size = dis.readInt()
                if (size <= 0) continue
                val data = ByteArray(size); dis.readFully(data)
                audioTrack.write(data, 0, data.size)
            }
        } catch (_: IOException) {
        } finally {
            isRunning = false
            runCatching { audioTrack.stop(); audioTrack.release() }
        }
    }

    private fun buildAudioTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                          else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
