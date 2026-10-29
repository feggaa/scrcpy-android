package tech.devline.scropy_ui.scrcpy

import android.util.Log
import android.view.KeyEvent
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

    /** Press and release [keycode] (a [KeyEvent] KEYCODE_*), like tapping a hardware key. */
    fun pressKey(keycode: Int) {
        sendKeyEvent(KeyEvent.ACTION_DOWN, keycode)
        sendKeyEvent(KeyEvent.ACTION_UP, keycode)
    }

    fun sendPower()      = pressKey(KeyEvent.KEYCODE_POWER)
    fun sendWakeUp()     = pressKey(KeyEvent.KEYCODE_WAKEUP)
    /** Turn the screen off and lock the device. */
    fun sendLock()       = pressKey(KeyEvent.KEYCODE_SLEEP)
    fun sendVolumeUp()   = pressKey(KeyEvent.KEYCODE_VOLUME_UP)
    fun sendVolumeDown() = pressKey(KeyEvent.KEYCODE_VOLUME_DOWN)
    fun sendVolumeMute() = pressKey(KeyEvent.KEYCODE_VOLUME_MUTE)
    fun sendHome()       = pressKey(KeyEvent.KEYCODE_HOME)
    fun sendBack()       = pressKey(KeyEvent.KEYCODE_BACK)
    fun sendRecents()    = pressKey(KeyEvent.KEYCODE_APP_SWITCH)
    fun sendScreenshot() = pressKey(KeyEvent.KEYCODE_SYSRQ)

    /**
     * Inject a straight finger swipe in device coordinates. Blocks for
     * [durationMs], so call it off the main thread.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 250, steps: Int = 12) {
        val w = deviceWidth; val h = deviceHeight
        if (w == 0 || h == 0) return
        val id = ScrcpyProtocol.POINTER_FINGER
        send(ScrcpyProtocol.buildTouchEvent(0, id, x1, y1, w, h))
        for (i in 1..steps) {
            Thread.sleep(durationMs / steps)
            val x = x1 + (x2 - x1) * i / steps
            val y = y1 + (y2 - y1) * i / steps
            send(ScrcpyProtocol.buildTouchEvent(2, id, x, y, w, h))
        }
        send(ScrcpyProtocol.buildTouchEvent(1, id, x2, y2, w, h, pressure = 0f))
    }

    /**
     * Wake the device and swipe up to dismiss the lock screen. Unlocks devices
     * with no PIN/pattern; with one, it brings up the PIN pad. Blocks ~1 s.
     */
    fun sendWakeAndSwipeUp() {
        sendWakeUp()
        Thread.sleep(400)
        val w = deviceWidth; val h = deviceHeight
        swipe(w / 2, h * 85 / 100, w / 2, h * 30 / 100)
    }

    // ── Simple actions ───────────────────────────────────────────────────────

    fun sendBackButton()              = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_BACK_OR_SCREEN_ON))
    fun sendCollapseNotifications()   = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_COLLAPSE_PANELS))
    fun sendExpandNotifications()     = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_EXPAND_NOTIFICATION))
    fun sendExpandSettings()          = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_EXPAND_SETTINGS))
    fun sendRotateDevice()            = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_ROTATE_DEVICE))
    fun sendResetVideo()              = send(ScrcpyProtocol.buildEmpty(ScrcpyProtocol.TYPE_RESET_VIDEO))

    /** Turn the remote device's screen on/off while keeping the session live. */
    fun sendDisplayPower(on: Boolean) = send(ScrcpyProtocol.buildDisplayPower(on))

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
