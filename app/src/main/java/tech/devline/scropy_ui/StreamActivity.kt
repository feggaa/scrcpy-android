package tech.devline.scropy_ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.scrcpy.AudioPlayer
import tech.devline.scropy_ui.scrcpy.ControlSender
import tech.devline.scropy_ui.scrcpy.ScrcpySession
import tech.devline.scropy_ui.scrcpy.VideoDecoder
import tech.devline.scropy_ui.ui.theme.ScropyTheme

/** Quick actions offered by the floating control panel. */
enum class RemoteAction(val icon: String, val label: String) {
    BACK("◀", "Back"), HOME("●", "Home"), RECENTS("▣", "Recents"), KEYBOARD("⌨", "Type"),
    POWER("⏻", "Power"), WAKE("☀", "Wake"), LOCK("🔒", "Lock"), UNLOCK("🔓", "Unlock"),
    VOLUME_DOWN("🔉", "Vol −"), VOLUME_UP("🔊", "Vol +"), MUTE("🔇", "Mute"), SCREENSHOT("📷", "Screenshot"),
    MEDIA_PREVIOUS("⏮", "Prev"), MEDIA_PLAY_PAUSE("⏯", "Play"), MEDIA_NEXT("⏭", "Next"), ROTATE("⟳", "Rotate"),
    NOTIFICATIONS("🔔", "Notifs"), QUICK_SETTINGS("⚙", "Quick set."), COLLAPSE_PANELS("⌃", "Close"),
    BRIGHTNESS_UP("🔆", "Bright +"),
}

class StreamActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USB_DEVICE = "usb_device"

        // Battery-friendly streaming defaults (tune freely).
        // 30 fps and ~4 Mbps roughly halve the encode/decode/Wi-Fi load versus
        // scrcpy's uncapped default without a visible hit for normal use.
        private const val DEFAULT_MAX_FPS = 30
        private const val DEFAULT_VIDEO_BIT_RATE = 4_000_000

        private const val PREFS = "stream_settings"
        private const val PREF_FORWARD_VOLUME_KEYS = "forward_volume_keys"
    }

    // ─── Session state ────────────────────────────────────────────────────────
    private var adbConn:     AdbConnection? = null
    private var session:     ScrcpySession? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer:  AudioPlayer?  = null
    private var controlSender: ControlSender? = null
    private var decodeJob: Job? = null
    private var audioJob:  Job? = null
    // Remembered connection params so we can offer Retry on failure
    private var pendingHost: String? = null
    private var pendingPort: Int = 5555
    private var pendingUsbDevice: UsbDevice? = null

    @Volatile private var surface: Surface? = null

    // Control messages go out on one background thread: AdbStream writes can
    // block on flow control, and key down/up must keep their order.
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ─── UI state ─────────────────────────────────────────────────────────────
    private var statusText  = mutableStateOf("Connecting…")
    private var errorText   = mutableStateOf<String?>(null)
    private var isConnected = mutableStateOf(false)
    // Whether the remote device's physical screen is currently powered on.
    private var screenOn    = mutableStateOf(true)
    // Route this phone's hardware volume keys to the remote device while streaming.
    private var forwardVolumeKeys = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Go fully immersive — hide status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Determine connection mode: USB or TCP
        val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_USB_DEVICE)
        }

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 5555)

        if (usbDevice == null && host == null) { finish(); return }

        pendingHost = host
        pendingPort = port
        pendingUsbDevice = usbDevice
        forwardVolumeKeys.value = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_FORWARD_VOLUME_KEYS, true)

        setContent {
            ScropyTheme {
                StreamScreen(
                    statusText  = statusText.value,
                    errorText   = errorText.value,
                    isConnected = isConnected.value,
                    screenOn    = screenOn.value,
                    onToggleScreen = {
                        val newOn = !screenOn.value
                        screenOn.value = newOn
                        runControl { sendDisplayPower(newOn) }
                    },
                    forwardVolumeKeys = forwardVolumeKeys.value,
                    onToggleVolumeKeys = {
                        val newValue = !forwardVolumeKeys.value
                        forwardVolumeKeys.value = newValue
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_FORWARD_VOLUME_KEYS, newValue).apply()
                    },
                    onAction   = ::onRemoteAction,
                    onSendText = { text -> runControl { sendText(text) } },
                    onSendKey  = { keycode -> runControl { pressKey(keycode) } },
                    onSurfaceReady      = { s ->
                        surface = s
                        if (isConnected.value) {
                            // Returning from background — restart codec with new surface
                            videoDecoder?.restartCodec(s)
                            // Request a new keyframe so the decoder can render immediately
                            controlSender?.sendResetVideo()
                        } else {
                            if (usbDevice != null) {
                                connectUsbIfReady(usbDevice)
                            } else {
                                connectIfReady(host!!, port)
                            }
                        }
                    },
                    onSurfaceDestroyed  = { surface = null },
                    onTouchEvent        = { ev, w, h -> controlSender?.sendTouchEvent(ev, w, h) },
                    onScrollEvent       = { ev, w, h -> controlSender?.sendScrollEvent(ev, w, h) },
                    onDisconnect        = { disconnectAll(); finish() },
                    onRetry             = { retryConnection() },
                )
            }
        }
    }

    // ─── Connection lifecycle ─────────────────────────────────────────────────

    private fun connectIfReady(host: String, port: Int) {
        if (surface == null) return
        lifecycleScope.launch {
            try {
                statusText.value  = "Connecting to $host:$port …"
                adbConn = AdbConnection.connectTcp(this@StreamActivity, host, port)

                statusText.value  = "Starting server…"
                session = ScrcpySession.start(
                    context    = this@StreamActivity,
                    adb        = adbConn!!,
                    enableAudio = true,
                    maxFps       = DEFAULT_MAX_FPS,
                    videoBitRate = DEFAULT_VIDEO_BIT_RATE,
                )

                val sess = session!!
                val info = sess.deviceInfo

                controlSender = ControlSender(sess.controlStream).also {
                    it.deviceWidth  = info.width
                    it.deviceHeight = info.height
                }

                videoDecoder = VideoDecoder(
                    stream  = sess.videoStream,
                    codecId = info.videoCodec,
                    width   = info.width,
                    height  = info.height,
                    onResize = { w, h ->
                        // Keep touch coordinate scaling correct across rotation/resize
                        controlSender?.let { it.deviceWidth = w; it.deviceHeight = h }
                    },
                )

                statusText.value  = "Streaming ${info.name}"
                isConnected.value = true

                decodeJob = launch(Dispatchers.IO) {
                    val s = surface ?: return@launch
                    videoDecoder!!.start(s)
                }

                if (sess.audioStream != null && info.audioCodec != 0) {
                    android.util.Log.i("StreamActivity", "Starting audio: codec=0x${info.audioCodec.toString(16)}")
                    audioPlayer = AudioPlayer(sess.audioStream, info.audioCodec)
                    audioJob = launch(Dispatchers.IO) { audioPlayer!!.start() }
                } else {
                    android.util.Log.w("StreamActivity", "Audio skipped: stream=${sess.audioStream != null} codec=0x${info.audioCodec.toString(16)}")
                }

            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "TCP connection failed", e)
                errorText.value   = e.message ?: "Unknown error"
                statusText.value  = "Failed"
                isConnected.value = false
            }
        }
    }

    private fun connectUsbIfReady(device: UsbDevice) {
        if (surface == null) return
        lifecycleScope.launch {
            try {
                statusText.value = "Connecting via USB…"
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                adbConn = AdbConnection.connectUsb(this@StreamActivity, device, usbManager)

                statusText.value = "Starting server…"
                session = ScrcpySession.start(
                    context     = this@StreamActivity,
                    adb         = adbConn!!,
                    enableAudio = true,
                    maxFps       = DEFAULT_MAX_FPS,
                    videoBitRate = DEFAULT_VIDEO_BIT_RATE,
                )

                val sess = session!!
                val info = sess.deviceInfo

                controlSender = ControlSender(sess.controlStream).also {
                    it.deviceWidth  = info.width
                    it.deviceHeight = info.height
                }

                videoDecoder = VideoDecoder(
                    stream  = sess.videoStream,
                    codecId = info.videoCodec,
                    width   = info.width,
                    height  = info.height,
                    onResize = { w, h ->
                        // Keep touch coordinate scaling correct across rotation/resize
                        controlSender?.let { it.deviceWidth = w; it.deviceHeight = h }
                    },
                )

                statusText.value  = "Streaming ${info.name} (USB)"
                isConnected.value = true

                decodeJob = launch(Dispatchers.IO) {
                    val s = surface ?: return@launch
                    videoDecoder!!.start(s)
                }

                if (sess.audioStream != null && info.audioCodec != 0) {
                    android.util.Log.i("StreamActivity", "Starting audio (USB): codec=0x${info.audioCodec.toString(16)}")
                    audioPlayer = AudioPlayer(sess.audioStream, info.audioCodec)
                    audioJob = launch(Dispatchers.IO) { audioPlayer!!.start() }
                } else {
                    android.util.Log.w("StreamActivity", "Audio skipped: stream=${sess.audioStream != null} codec=0x${info.audioCodec.toString(16)}")
                }

            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "USB connection failed", e)
                errorText.value   = e.message ?: "USB connection error"
                statusText.value  = "Failed"
                isConnected.value = false
            }
        }
    }

    // ─── Remote control ───────────────────────────────────────────────────────

    /** Run [block] against the current [ControlSender] on the control thread. */
    private fun runControl(block: ControlSender.() -> Unit) {
        val sender = controlSender ?: return
        if (controlExecutor.isShutdown) return
        controlExecutor.execute {
            runCatching { sender.block() }
                .onFailure { android.util.Log.w("StreamActivity", "Control action failed", it) }
        }
    }

    private fun onRemoteAction(action: RemoteAction) = when (action) {
        RemoteAction.BACK             -> runControl { sendBack() }
        RemoteAction.HOME             -> runControl { sendHome() }
        RemoteAction.RECENTS          -> runControl { sendRecents() }
        RemoteAction.KEYBOARD         -> Unit   // handled by the UI (opens the text dialog)
        RemoteAction.POWER            -> runControl { sendPower() }
        RemoteAction.WAKE             -> runControl { sendWakeUp() }
        RemoteAction.LOCK             -> runControl { sendLock() }
        RemoteAction.UNLOCK           -> runControl { sendWakeAndSwipeUp() }
        RemoteAction.VOLUME_DOWN      -> runControl { sendVolumeDown() }
        RemoteAction.VOLUME_UP        -> runControl { sendVolumeUp() }
        RemoteAction.MUTE             -> runControl { sendVolumeMute() }
        RemoteAction.SCREENSHOT       -> runControl { sendScreenshot() }
        RemoteAction.MEDIA_PREVIOUS   -> runControl { pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        RemoteAction.MEDIA_PLAY_PAUSE -> runControl { pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        RemoteAction.MEDIA_NEXT       -> runControl { pressKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        RemoteAction.ROTATE           -> runControl { sendRotateDevice() }
        RemoteAction.NOTIFICATIONS    -> runControl { sendExpandNotifications() }
        RemoteAction.QUICK_SETTINGS   -> runControl { sendExpandSettings() }
        RemoteAction.COLLAPSE_PANELS  -> runControl { sendCollapseNotifications() }
        RemoteAction.BRIGHTNESS_UP    -> runControl { pressKey(KeyEvent.KEYCODE_BRIGHTNESS_UP) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && forwardVolumeKeys.value && isConnected.value && controlSender != null) {
            val action = event.action; val code = event.keyCode; val repeat = event.repeatCount
            runControl { sendKeyEvent(action, code, repeat) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun disconnectAll() {
        decodeJob?.cancel()
        audioJob?.cancel()
        videoDecoder?.stop()
        audioPlayer?.stop()
        session?.close()
        adbConn?.close()
        adbConn    = null
        session    = null
        videoDecoder  = null
        audioPlayer   = null
        controlSender = null
    }

    /** Re-attempt the connection after a failure, reusing the original params. */
    private fun retryConnection() {
        if (surface == null) return
        errorText.value = null
        statusText.value = "Reconnecting…"
        isConnected.value = false
        val u = pendingUsbDevice
        if (u != null) connectUsbIfReady(u) else pendingHost?.let { connectIfReady(it, pendingPort) }
    }

    override fun onPause() {
        super.onPause()
        // Release the video codec before the surface is destroyed by the system
        videoDecoder?.releaseCodec()
        audioPlayer?.pauseAudio()
    }

    override fun onResume() {
        super.onResume()
        // Audio resumes here; video codec restarts in onSurfaceReady
        audioPlayer?.resumeAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAll()
        controlExecutor.shutdown()
    }
}

// ─── Composable UI ─────────────────────────────────────────────────────────────

@Composable
private fun StreamScreen(
    statusText: String,
    errorText: String?,
    isConnected: Boolean,
    screenOn: Boolean,
    onToggleScreen: () -> Unit,
    forwardVolumeKeys: Boolean,
    onToggleVolumeKeys: () -> Unit,
    onAction: (RemoteAction) -> Unit,
    onSendText: (String) -> Unit,
    onSendKey: (Int) -> Unit,
    onSurfaceReady:     (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouchEvent:  (MotionEvent, Int, Int) -> Unit,
    onScrollEvent: (MotionEvent, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        // ── SurfaceView for video output ────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) =
                            onSurfaceReady(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) =
                            onSurfaceDestroyed()
                    })

                    setOnTouchListener { v, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_SCROLL -> onScrollEvent(ev, v.width, v.height)
                            else                     -> onTouchEvent(ev,  v.width, v.height)
                        }
                        true
                    }
                }
            },
        )

        // ── Status / error overlay ──────────────────────────────────────────
        if (!isConnected) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color    = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier              = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement   = Arrangement.Center,
                    horizontalAlignment   = Alignment.CenterHorizontally,
                ) {
                    if (errorText != null) {
                        Text(
                            text  = "Connection failed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onRetry) { Text("Retry") }
                            OutlinedButton(onClick = onDisconnect) { Text("Go Back") }
                        }
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(text = statusText)
                    }
                }
            }
        }

        // ── Floating, draggable control puck when connected ─────────────────
        if (isConnected) {
            var showKeyboard by remember { mutableStateOf(false) }
            FloatingControls(
                screenOn           = screenOn,
                onToggleScreen     = onToggleScreen,
                forwardVolumeKeys  = forwardVolumeKeys,
                onToggleVolumeKeys = onToggleVolumeKeys,
                onAction           = { action ->
                    if (action == RemoteAction.KEYBOARD) showKeyboard = true else onAction(action)
                },
                onDisconnect       = onDisconnect,
            )
            if (showKeyboard) {
                TextInputDialog(
                    onSendText = onSendText,
                    onSendKey  = onSendKey,
                    onDismiss  = { showKeyboard = false },
                )
            }
        }
    }
}

/**
 * A small floating, draggable control puck overlaid on the stream — like the
 * toolbar in an RDP session. Drag it anywhere (it stays where you drop it, and
 * can't be lost off-screen), tap it to expand quick actions, tap ✕ to collapse.
 */
@Composable
private fun BoxScope.FloatingControls(
    screenOn: Boolean,
    onToggleScreen: () -> Unit,
    forwardVolumeKeys: Boolean,
    onToggleVolumeKeys: () -> Unit,
    onAction: (RemoteAction) -> Unit,
    onDisconnect: () -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenW = with(density) { config.screenWidthDp.dp.toPx() }
    val screenH = with(density) { config.screenHeightDp.dp.toPx() }
    val puckPx = with(density) { 46.dp.toPx() }
    val marginPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }

    // Offset from the top-right corner, starting just inside it.
    var offset by remember { mutableStateOf(Offset(-marginPx, marginPx)) }
    var expanded by remember { mutableStateOf(false) }

    fun clampOffset(o: Offset) = Offset(
        o.x.coerceIn(-(screenW - puckPx), 0f),
        o.y.coerceIn(0f, screenH - puckPx),
    )

    // Which half is the puck in? The menu unfolds toward the screen centre.
    val puckCentreX = screenW - puckPx / 2f + offset.x
    val onLeft = puckCentreX < screenW / 2f

    // ── The puck. Its container holds nothing but the puck, so its position
    //    depends only on `offset` — opening the menu can never shift it.
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset = clampOffset(offset + drag)
                }
            },
    ) {
        Surface(
            onClick = { expanded = !expanded },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (expanded) "✕" else "☰", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    // ── The menu: one compact panel next to the puck, anchored separately so
    //    only it moves — the puck stays exactly where you left it. It opens
    //    downward from a puck in the top half and upward from one in the bottom.
    if (expanded) {
        val onTop = offset.y + puckPx / 2f < screenH / 2f
        val menuX = if (onLeft) (screenW - puckPx + offset.x) else offset.x
        val menuY = if (onTop) offset.y + puckPx + gapPx else offset.y - gapPx - screenH
        val maxMenuHeight = with(density) {
            (if (onTop) screenH - (offset.y + puckPx + gapPx) - marginPx else offset.y - gapPx - marginPx)
                .coerceAtLeast(puckPx * 3).toDp()
        }
        val alignment = when {
            onTop && onLeft  -> Alignment.TopStart
            onTop            -> Alignment.TopEnd
            onLeft           -> Alignment.BottomStart
            else             -> Alignment.BottomEnd
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.78f),
            contentColor = Color.White,
            modifier = Modifier
                .align(alignment)
                .offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                .width(264.dp)
                .heightIn(max = maxMenuHeight),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
            ) {
                RemoteAction.entries.chunked(4).forEach { row ->
                    Row {
                        row.forEach { action ->
                            ActionButton(action, Modifier.weight(1f)) { onAction(action) }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.2f),
                )
                MenuItem(if (screenOn) "Screen off (keep streaming)" else "Screen on", onToggleScreen)
                MenuItem(
                    "Volume keys → device: " + if (forwardVolumeKeys) "On" else "Off",
                    onToggleVolumeKeys,
                )
                MenuItem("Disconnect", onDisconnect, color = Color(0xFFFF8A80))
            }
        }
    }
}

/** One square icon + label button in the control grid. */
@Composable
private fun ActionButton(action: RemoteAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        contentColor = Color.White,
        modifier = modifier.padding(2.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Text(action.icon, fontSize = 20.sp)
            Text(
                action.label,
                fontSize = 10.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * Type text into whatever field is focused on the remote device, plus
 * Backspace / Enter keys. Text is injected through scrcpy, which works best
 * for characters the device's keyboard map knows (Latin letters, digits).
 */
@Composable
private fun TextInputDialog(
    onSendText: (String) -> Unit,
    onSendKey: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on device") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text to send") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSendKey(KeyEvent.KEYCODE_DEL) }) { Text("⌫ Delete") }
                    OutlinedButton(onClick = { onSendKey(KeyEvent.KEYCODE_ENTER) }) { Text("↵ Enter") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotEmpty()) { onSendText(text); text = "" } },
                enabled = text.isNotEmpty(),
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One row inside the floating menu panel. */
@Composable
private fun MenuItem(label: String, onClick: () -> Unit, color: Color = Color.White) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
