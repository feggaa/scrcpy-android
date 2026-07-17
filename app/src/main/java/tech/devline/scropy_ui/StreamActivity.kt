package tech.devline.scropy_ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
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

    // ─── UI state ─────────────────────────────────────────────────────────────
    private var statusText  = mutableStateOf("Connecting…")
    private var errorText   = mutableStateOf<String?>(null)
    private var isConnected = mutableStateOf(false)
    // Whether the remote device's physical screen is currently powered on.
    private var screenOn    = mutableStateOf(true)

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
                        controlSender?.sendDisplayPower(newOn)
                    },
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
            FloatingControls(
                screenOn       = screenOn,
                onToggleScreen = onToggleScreen,
                onDisconnect   = onDisconnect,
            )
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

    // ── The menu: one compact panel just below the puck, anchored separately so
    //    only it moves — the puck stays exactly where you left it.
    if (expanded) {
        val menuY = offset.y + puckPx + gapPx
        val menuModifier = if (onLeft) {
            // Menu's left edge lines up with the puck's left edge, opening right
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset((screenW - puckPx + offset.x).roundToInt(), menuY.roundToInt()) }
        } else {
            // Menu's right edge lines up with the puck's right edge, opening left
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(offset.x.roundToInt(), menuY.roundToInt()) }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
            modifier = menuModifier,
        ) {
            Column {
                MenuItem("Disconnect", onDisconnect)
                MenuItem(if (screenOn) "Screen off" else "Screen on", onToggleScreen)
            }
        }
    }
}

/** One row inside the floating menu panel. */
@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
