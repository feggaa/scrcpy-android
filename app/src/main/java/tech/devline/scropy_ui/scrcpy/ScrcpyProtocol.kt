package tech.devline.scropy_ui.scrcpy

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire-level constants and control-message serialization for the scrcpy protocol. */
object ScrcpyProtocol {

    // ── Codec IDs (4-byte ASCII big-endian) ─────────────────────────────────
    const val CODEC_H264 = 0x68323634
    const val CODEC_H265 = 0x68323635
    const val CODEC_AV1  = 0x00617631
    const val CODEC_OPUS = 0x6f707573
    const val CODEC_AAC  = 0x00616163
    const val CODEC_FLAC = 0x666c6163
    const val CODEC_RAW  = 0x00726177

    // ── Frame packet flags (in the 8-byte pts header) ───────────────────────
    const val FLAG_CONFIG    = 1L shl 63
    const val FLAG_KEY_FRAME = 1L shl 62
    const val PTS_MASK       = FLAG_KEY_FRAME - 1L

    // ── Packet header size (pts:8 + size:4) ─────────────────────────────────
    const val PACKET_HEADER = 12

    // ── Device meta ──────────────────────────────────────────────────────────
    const val DEVICE_NAME_LEN = 64

    // ── Control message types ─────────────────────────────────────────────────
    const val TYPE_INJECT_KEYCODE           = 0
    const val TYPE_INJECT_TEXT              = 1
    const val TYPE_INJECT_TOUCH_EVENT       = 2
    const val TYPE_INJECT_SCROLL_EVENT      = 3
    const val TYPE_BACK_OR_SCREEN_ON        = 4
    const val TYPE_EXPAND_NOTIFICATION      = 5
    const val TYPE_EXPAND_SETTINGS          = 6
    const val TYPE_COLLAPSE_PANELS          = 7
    const val TYPE_GET_CLIPBOARD            = 8
    const val TYPE_SET_CLIPBOARD            = 9
    const val TYPE_SET_DISPLAY_POWER        = 10
    const val TYPE_ROTATE_DEVICE            = 11
    const val TYPE_RESET_VIDEO              = 17

    // ── Pointer IDs ───────────────────────────────────────────────────────────
    const val POINTER_MOUSE: Long = -1L    // 0xFFFFFFFFFFFFFFFF

    // ─── Control message serialization ──────────────────────────────────────

    /** Inject a touch / pointer event (MotionEvent equivalent). */
    fun buildTouchEvent(
        action: Int,          // MotionEvent.ACTION_DOWN / MOVE / UP
        pointerId: Long,
        x: Int, y: Int,
        screenW: Int, screenH: Int,
        pressure: Float = 1f,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(x); buf.putInt(y)
        buf.putShort(screenW.toShort()); buf.putShort(screenH.toShort())
        buf.putShort(floatToU16Fixed(pressure))
        buf.putInt(actionButton)
        buf.putInt(buttons)
        return buf.array()
    }

    /** Inject a scroll event. */
    fun buildScrollEvent(
        x: Int, y: Int, screenW: Int, screenH: Int,
        hScroll: Float, vScroll: Float, buttons: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_SCROLL_EVENT.toByte())
        buf.putInt(x); buf.putInt(y)
        buf.putShort(screenW.toShort()); buf.putShort(screenH.toShort())
        buf.putShort(floatToI16Fixed(hScroll / 16f))
        buf.putShort(floatToI16Fixed(vScroll / 16f))
        buf.putInt(buttons)
        return buf.array()
    }

    /** Inject a key-code event. */
    fun buildKeyEvent(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(repeat)
        buf.putInt(metaState)
        return buf.array()
    }

    /** Inject a text string. */
    fun buildTextEvent(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(5 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_INJECT_TEXT.toByte())
        buf.putInt(textBytes.size)
        buf.put(textBytes)
        return buf.array()
    }

    /** Simple no-payload messages (back, expand/collapse panels, rotate, etc.). */
    fun buildEmpty(type: Int): ByteArray = byteArrayOf(type.toByte())

    // ─────────────────────────────────────────────────────────────────────────

    /** Float [0,1] → unsigned 16-bit fixed-point. */
    private fun floatToU16Fixed(v: Float): Short =
        (v.coerceIn(0f, 1f) * 0xFFFF).toInt().toShort()

    /** Float [-1,1] → signed 16-bit fixed-point. */
    private fun floatToI16Fixed(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 0x7FFF).toInt().toShort()
}
