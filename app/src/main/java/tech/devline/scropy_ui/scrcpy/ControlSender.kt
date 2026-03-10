package tech.devline.scropy_ui.scrcpy

import android.util.Log
import android.view.MotionEvent
import tech.devline.scropy_ui.adb.AdbStream
import java.io.IOException

private const val TAG = "ControlSender"

/**
 * Serializes and sends scrcpy control messages to the device.
 *
 * All methods are thread-safe (writes are synchronised on [stream]).
 */
class ControlSender(private val stream: AdbStream) {

    // Device display dimensions — updated when session info is known
    @Volatile var deviceWidth:  Int = 0
    @Volatile var deviceHeight: Int = 0

    // ── Touch ───────────────────────────────────────────────────────────────

    /**
     * Forward an Android [MotionEvent] from our Surface to the remote device.
     * [surfaceWidth] / [surfaceHeight] are the current SurfaceView dimensions
     * so that we can scale coordinates to device resolution.
     */
    fun sendTouchEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        if (deviceWidth == 0 || deviceHeight == 0) return

        val action    = event.actionMasked
        val scrcpyAct = mapAction(action) ?: return

        val pointerIdx = event.actionIndex
        val pointerId  = normalizePointerId(event.getPointerId(pointerIdx))

        // Scale from surface coords to device coords
        val x = (event.getX(pointerIdx) / surfaceWidth  * deviceWidth).toInt()
        val y = (event.getY(pointerIdx) / surfaceHeight * deviceHeight).toInt()

        val pressure     = event.getPressure(pointerIdx)
        val actionButton = event.actionButton
        val buttons      = event.buttonState

        send(ScrcpyProtocol.buildTouchEvent(
            scrcpyAct, pointerId, x, y, deviceWidth, deviceHeight,
            pressure, actionButton, buttons,
        ))
    }

    fun sendScrollEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        val x = (event.x / surfaceWidth  * deviceWidth).toInt()
        val y = (event.y / surfaceHeight * deviceHeight).toInt()
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        send(ScrcpyProtocol.buildScrollEvent(x, y, deviceWidth, deviceHeight, hScroll, vScroll))
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    fun sendKeyEvent(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0) =
        send(ScrcpyProtocol.buildKeyEvent(action, keycode, repeat, metaState))

    fun sendText(text: String) = send(ScrcpyProtocol.buildTextEvent(text))

    // ── Simple actions ───────────────────────────────────────────────────────

    fun sendBackButton()              = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_BACK_OR_SCREEN_ON))
    fun sendCollapseNotifications()   = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_COLLAPSE_PANELS))
    fun sendExpandNotifications()     = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_EXPAND_NOTIFICATION))
    fun sendRotateDevice()            = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_ROTATE_DEVICE))
    fun sendResetVideo()              = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_RESET_VIDEO))

    // ─── Private ─────────────────────────────────────────────────────────────

    private fun send(data: ByteArray) {
        try {
            stream.write(data)
        } catch (e: IOException) {
            Log.w(TAG, "Control send failed: ${e.message}")
        }
    }

    /** Map Android MotionEvent action to scrcpy action byte (AMOTION_EVENT_ACTION_*). */
    private fun mapAction(action: Int): Int? = when (action) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN -> 0   // AMOTION_EVENT_ACTION_DOWN
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_POINTER_UP   -> 1   // AMOTION_EVENT_ACTION_UP
        MotionEvent.ACTION_MOVE         -> 2   // AMOTION_EVENT_ACTION_MOVE
        else -> null
    }

    /** Map Android pointer IDs to scrcpy IDs (use POINTER_MOUSE for single-touch). */
    private fun normalizePointerId(androidId: Int): Long =
        if (androidId == 0) ScrcpyProtocol.POINTER_MOUSE else androidId.toLong()
}
