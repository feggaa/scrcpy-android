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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
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
    }

    // ─── Session state ────────────────────────────────────────────────────────
    private var adbConn:     AdbConnection? = null
    private var session:     ScrcpySession? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer:  AudioPlayer?  = null
    private var controlSender: ControlSender? = null
    private var decodeJob: Job? = null
    private var audioJob:  Job? = null

    @Volatile private var surface: Surface? = null

    // ─── UI state ─────────────────────────────────────────────────────────────
    private var statusText  = mutableStateOf("Connecting…")
    private var errorText   = mutableStateOf<String?>(null)
    private var isConnected = mutableStateOf(false)

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

        setContent {
            ScropyTheme {
                StreamScreen(
                    statusText  = statusText.value,
                    errorText   = errorText.value,
                    isConnected = isConnected.value,
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
                val usbConnection = usbManager.openDevice(device)
                    ?: throw java.io.IOException("Cannot open USB device")
                adbConn = AdbConnection.connectUsb(this@StreamActivity, device, usbConnection)

                statusText.value = "Starting server…"
                session = ScrcpySession.start(
                    context     = this@StreamActivity,
                    adb         = adbConn!!,
                    enableAudio = true,
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
    onSurfaceReady:     (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouchEvent:  (MotionEvent, Int, Int) -> Unit,
    onScrollEvent: (MotionEvent, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
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
                        Button(onClick = onDisconnect) { Text("Go Back") }
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(text = statusText)
                    }
                }
            }
        }

        // ── Top control bar when connected ──────────────────────────────────
        if (isConnected) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onDisconnect,
                    colors  = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor   = Color.White,
                    ),
                ) {
                    Text("✕  Disconnect")
                }
            }
        }
    }
}
