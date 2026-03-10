package tech.devline.scropy_ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.devline.scropy_ui.adb.AdbAuthHelper
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.AdbPairing
import tech.devline.scropy_ui.ui.theme.ScropyTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "tech.devline.scropy_ui.USB_PERMISSION"
        private const val TAG = "MainActivity"
    }

    // USB permission callback - set by the USB tab when requesting permission
    private var usbPermissionCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                usbPermissionCallback?.invoke(granted)
                usbPermissionCallback = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.writeDiag("MainActivity.onCreate: started")
        enableEdgeToEdge()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            ScropyTheme {
                MainScreen(
                    onConnect = { host, port, onResult ->
                        lifecycleScope.launch {
                            runCatching {
                                AdbConnection.connectTcp(this@MainActivity, host, port).also { it.close() }
                            }.fold(
                                onSuccess = {
                                    onResult(null)
                                    startActivity(
                                        Intent(this@MainActivity, StreamActivity::class.java).apply {
                                            putExtra(StreamActivity.EXTRA_HOST, host)
                                            putExtra(StreamActivity.EXTRA_PORT, port)
                                        }
                                    )
                                },
                                onFailure = { e ->
                                    android.util.Log.e(TAG, "TCP connect failed", e)
                                    onResult(e.message ?: "ADB connect failed")
                                },
                            )
                        }
                    },
                    onPair = { host, pairPort, code, adbPort, onResult ->
                        lifecycleScope.launch {
                            val (priv, pub) = AdbAuthHelper.getOrCreateKeys(this@MainActivity)
                            runCatching {
                                AdbPairing.pair(host, pairPort, code, priv, pub)
                            }.fold(
                                onSuccess = {
                                    // After pairing, connect to ADB port
                                    onResult(null, "Paired! Connecting to ADB…")
                                    // Device needs time to register the new key
                                    kotlinx.coroutines.delay(2000)
                                    runCatching {
                                        val conn = AdbConnection.connectTcp(
                                            this@MainActivity, host, adbPort
                                        )
                                        conn.close()
                                    }.fold(
                                        onSuccess = {
                                            onResult(null, "Paired & connected! Go to Connect tab.")
                                        },
                                        onFailure = {
                                            onResult(null,
                                                "Paired OK, but ADB connect failed: ${it.message}\n" +
                                                "Try the Connect tab manually.")
                                        }
                                    )
                                },
                                onFailure = { e ->
                                    android.util.Log.e(TAG, "Pairing failed", e)
                                    onResult(e.message ?: "Pairing failed", null)
                                },
                            )
                        }
                    },
                    onUsbConnect = { device, onResult ->
                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
                        if (!usbManager.hasPermission(device)) {
                            onResult("No USB permission", null)
                            return@MainScreen
                        }
                        // Don't test-connect over USB — unlike TCP, USB has a single
                        // physical link and adbd can't handle rapid connect/close/reconnect.
                        // Just verify we can open the device, then let StreamActivity handle it.
                        val usbConnection = usbManager.openDevice(device)
                        if (usbConnection == null) {
                            onResult("Cannot open USB device", null)
                            return@MainScreen
                        }
                        usbConnection.close()
                        onResult(null, null)
                        startActivity(
                            Intent(this@MainActivity, StreamActivity::class.java).apply {
                                putExtra(StreamActivity.EXTRA_USB_DEVICE, device)
                            }
                        )
                    },
                    onRequestUsbPermission = { device, callback ->
                        usbPermissionCallback = callback
                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
                        val pi = PendingIntent.getBroadcast(
                            this, 0,
                            Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        usbManager.requestPermission(device, pi)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
    }
}

// ─── Top-level tabbed screen ──────────────────────────────────────────────────

@Composable
fun MainScreen(
    onConnect: (host: String, port: Int, result: (String?) -> Unit) -> Unit,
    onPair: (host: String, pairPort: Int, code: String, adbPort: Int, result: (error: String?, info: String?) -> Unit) -> Unit,
    onUsbConnect: (device: UsbDevice, result: (error: String?, info: String?) -> Unit) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, callback: (Boolean) -> Unit) -> Unit,
) {
    val tabs = listOf("Pair", "Connect", "USB", "Shell")
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("scropy_prefs", Context.MODE_PRIVATE) }
    val savedHost = remember { mutableStateOf(prefs.getString("last_host", "") ?: "") }
    val savedPort = remember { mutableStateOf(prefs.getString("last_adb_port", "") ?: "") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(title) })
                }
            }
            when (selectedTab) {
                0 -> PairScreen(onPair, savedHost, savedPort, prefs)
                1 -> ConnectScreen(onConnect, savedHost, savedPort, prefs)
                2 -> UsbScreen(onUsbConnect, onRequestUsbPermission)
                3 -> ShellScreen(savedHost, savedPort, prefs, onRequestUsbPermission)
            }
        }
    }
}

// ─── Pair tab ─────────────────────────────────────────────────────────────────

@Composable
fun PairScreen(
    onPair: (host: String, pairPort: Int, code: String, adbPort: Int, result: (error: String?, info: String?) -> Unit) -> Unit,
    savedHost: MutableState<String>,
    savedPort: MutableState<String>,
    prefs: android.content.SharedPreferences,
) {
    var host      by remember { mutableStateOf(savedHost.value) }
    var portStr   by remember { mutableStateOf("") }
    var adbPortStr by remember { mutableStateOf(savedPort.value) }
    var code      by remember { mutableStateOf("") }
    var status    by remember { mutableStateOf<String?>(null) }
    var pairing   by remember { mutableStateOf(false) }
    val focus     = LocalFocusManager.current
    val ctx       = LocalContext.current

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Wireless Debugging Pairing", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "On the device: Settings → Developer options → Wireless debugging\n" +
                   "→ \"Pair device with pairing code\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Device IP address") },
            placeholder = { Text("e.g. 192.168.1.65") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = portStr, onValueChange = { portStr = it.filter(Char::isDigit) },
            label = { Text("Pairing port (shown on pairing dialog)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = adbPortStr, onValueChange = { adbPortStr = it.filter(Char::isDigit) },
            label = { Text("ADB port (shown under Wireless debugging)") },
            placeholder = { Text("e.g. 38765") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6-digit pairing code") },
            placeholder = { Text("e.g. 123456") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                focus.clearFocus()
                val pairPort = portStr.toIntOrNull() ?: return@Button
                val adbPort = adbPortStr.toIntOrNull() ?: return@Button
                if (host.isBlank() || code.length != 6) return@Button
                pairing = true
                status  = "Pairing…"
                prefs.edit().putString("last_host", host.trim())
                    .putString("last_adb_port", adbPortStr).apply()
                savedHost.value = host.trim()
                savedPort.value = adbPortStr
                onPair(host.trim(), pairPort, code, adbPort) { err, info ->
                    if (err != null) {
                        pairing = false
                        status = "✗ $err"
                    } else if (info != null) {
                        status = "✓ $info"
                        if (info.contains("Go to Connect")) {
                            pairing = false
                            Toast.makeText(ctx, "Paired & connected!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = !pairing && host.isNotBlank() && portStr.isNotBlank()
                       && adbPortStr.isNotBlank() && code.length == 6,
        ) {
            if (pairing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (pairing) "Pairing…" else "Pair device")
        }

        status?.let { msg ->
            Spacer(Modifier.height(16.dp))
            val color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─── Connect tab ──────────────────────────────────────────────────────────────

@Composable
fun ConnectScreen(
    onConnect: (host: String, port: Int, result: (String?) -> Unit) -> Unit,
    savedHost: MutableState<String>,
    savedPort: MutableState<String>,
    prefs: android.content.SharedPreferences,
) {
    var host    by remember { mutableStateOf(savedHost.value) }
    var portStr by remember { mutableStateOf(savedPort.value) }
    var connecting by remember { mutableStateOf(false) }
    var status  by remember { mutableStateOf<String?>(null) }
    val focus   = LocalFocusManager.current

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize(),
        verticalArrangement   = Arrangement.Top,
        horizontalAlignment   = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Connect to paired device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Use the IP address & port shown in\nSettings → Wireless debugging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value         = host,
            onValueChange = { host = it },
            label         = { Text("Device IP Address") },
            placeholder   = { Text("e.g. 192.168.1.65") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction    = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value         = portStr,
            onValueChange = { portStr = it.filter(Char::isDigit) },
            label         = { Text("ADB port (shown under Wireless debugging)") },
            placeholder   = { Text("e.g. 38765") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction    = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick  = {
                focus.clearFocus()
                val port = portStr.toIntOrNull() ?: return@Button
                if (host.isNotBlank()) {
                    prefs.edit().putString("last_host", host.trim())
                        .putString("last_adb_port", portStr).apply()
                    savedHost.value = host.trim()
                    savedPort.value = portStr
                    connecting = true
                    status = "Connecting to ADB…"
                    onConnect(host.trim(), port) { err ->
                        connecting = false
                        status = if (err != null) "✗ $err" else null
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = !connecting && host.isNotBlank() && portStr.isNotBlank(),
        ) {
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (connecting) "Connecting…" else "Connect")
        }

        status?.let { msg ->
            Spacer(Modifier.height(16.dp))
            val color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Tip", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "• Pair once (first tab) — the device remembers your key.\n" +
                   "• After pairing, just enter the IP & ADB port here.\n" +
                   "• The ADB port on the device changes each session;\n" +
                   "  check Wireless debugging each time you reconnect.\n" +
                   "• If connection fails, re-pair on the first tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── USB tab ──────────────────────────────────────────────────────────────────

@Composable
fun UsbScreen(
    onUsbConnect: (device: UsbDevice, result: (error: String?, info: String?) -> Unit) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, callback: (Boolean) -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val usbManager = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }

    var devices by remember { mutableStateOf(findAdbDevices(usbManager)) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("USB ADB Connection", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Connect an Android device via USB OTG cable.\n" +
                   "Enable USB debugging on the target device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = { devices = findAdbDevices(usbManager); status = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan USB Devices")
        }

        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty()) {
            Text(
                text = "No ADB-capable USB devices found.\n\n" +
                       "Make sure:\n" +
                       "• USB debugging is enabled on the device\n" +
                       "• An OTG cable/adapter is connected\n" +
                       "• The device is connected and recognized",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            devices.forEach { device ->
                val hasPermission = usbManager.hasPermission(device)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = device.productName ?: "Unknown Device",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "VID: 0x${device.vendorId.toString(16).uppercase()}  " +
                                   "PID: 0x${device.productId.toString(16).uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        device.manufacturerName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        if (!hasPermission) {
                            Button(
                                onClick = {
                                    onRequestUsbPermission(device) { granted ->
                                        if (granted) {
                                            devices = findAdbDevices(usbManager)
                                            status = "Permission granted!"
                                        } else {
                                            status = "USB permission denied"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Grant Permission")
                            }
                        } else {
                            Button(
                                onClick = {
                                    connecting = true
                                    status = "Connecting via USB…"
                                    onUsbConnect(device) { err, _ ->
                                        connecting = false
                                        status = if (err != null) "✗ $err" else null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !connecting,
                            ) {
                                if (connecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (connecting) "Connecting…" else "Connect via USB")
                            }
                        }
                    }
                }
            }
        }

        status?.let { msg ->
            Spacer(Modifier.height(16.dp))
            val color = if (msg.startsWith("✗")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
            Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Tip", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "• No pairing needed for USB — just enable USB debugging.\n" +
                   "• When connecting for the first time, tap \"Allow\" on\n" +
                   "  the target device's USB debugging prompt.\n" +
                   "• USB provides lower latency than WiFi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun findAdbDevices(usbManager: UsbManager): List<UsbDevice> {
    return usbManager.deviceList.values.filter { device ->
        (0 until device.interfaceCount).any { i ->
            val iface = device.getInterface(i)
            iface.interfaceClass == 0xFF &&
            iface.interfaceSubclass == 0x42 &&
            iface.interfaceProtocol == 0x01
        }
    }
}

// ─── Shell tab ────────────────────────────────────────────────────────────────

@Composable
fun ShellScreen(
    savedHost: MutableState<String>,
    savedPort: MutableState<String>,
    prefs: android.content.SharedPreferences,
    onRequestUsbPermission: (UsbDevice, (Boolean) -> Unit) -> Unit,
) {
    var host      by remember { mutableStateOf(savedHost.value) }
    var portStr   by remember { mutableStateOf(savedPort.value) }
    var command   by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status    by remember { mutableStateOf<String?>(null) }
    val outputLines = remember { mutableStateListOf<String>() }
    var running   by remember { mutableStateOf(false) }
    var useUsb    by remember { mutableStateOf(false) }
    val focus     = LocalFocusManager.current
    val ctx       = LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val usbManager = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }
    var usbDevices by remember { mutableStateOf(findAdbDevices(usbManager)) }
    var selectedUsbDevice by remember { mutableStateOf<UsbDevice?>(null) }

    // Keep a reference to the ADB connection
    var adbConn by remember { mutableStateOf<AdbConnection?>(null) }

    // Auto-scroll when new output arrives
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    // Cleanup on leave
    DisposableEffect(Unit) {
        onDispose {
            adbConn?.close()
            adbConn = null
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
    ) {
        if (!connected) {
            // ── Connection form ──────────────────────────────────────────
            Text("Connect to device for shell access", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // Mode toggle: WiFi vs USB
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilterChip(
                    selected = !useUsb,
                    onClick = { useUsb = false },
                    label = { Text("WiFi") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = useUsb,
                    onClick = {
                        useUsb = true
                        usbDevices = findAdbDevices(usbManager)
                    },
                    label = { Text("USB") },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!useUsb) {
                // ── WiFi connection fields ───────────────────────────────
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Device IP address") },
                    placeholder = { Text("e.g. 192.168.1.65") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = portStr, onValueChange = { portStr = it.filter(Char::isDigit) },
                    label = { Text("ADB port") },
                    placeholder = { Text("e.g. 39843") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        focus.clearFocus()
                        val port = portStr.toIntOrNull() ?: return@Button
                        if (host.isBlank()) return@Button
                        connecting = true
                        status = "Connecting…"
                        prefs.edit().putString("last_host", host.trim())
                            .putString("last_adb_port", portStr).apply()
                        savedHost.value = host.trim()
                        savedPort.value = portStr
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AdbConnection.connectTcp(ctx as ComponentActivity, host.trim(), port)
                                }
                            }.fold(
                                onSuccess = { conn ->
                                    adbConn = conn
                                    connected = true
                                    connecting = false
                                    status = null
                                    outputLines.add("--- Connected to ${host.trim()}:$port ---")
                                },
                                onFailure = {
                                    connecting = false
                                    status = "✗ ${it.message}"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting && host.isNotBlank() && portStr.isNotBlank(),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (connecting) "Connecting…" else "Connect")
                }
            } else {
                // ── USB connection ───────────────────────────────────────
                OutlinedButton(
                    onClick = { usbDevices = findAdbDevices(usbManager) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Scan USB Devices")
                }
                Spacer(Modifier.height(8.dp))

                if (usbDevices.isEmpty()) {
                    Text(
                        text = "No ADB USB devices found.\nConnect a device via OTG cable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    usbDevices.forEach { device ->
                        val hasPermission = usbManager.hasPermission(device)
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.productName ?: "Unknown Device",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "VID:0x${device.vendorId.toString(16).uppercase()} PID:0x${device.productId.toString(16).uppercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!hasPermission) {
                                    Button(onClick = {
                                        onRequestUsbPermission(device) { granted ->
                                            if (granted) usbDevices = findAdbDevices(usbManager)
                                            else status = "Permission denied"
                                        }
                                    }) { Text("Allow") }
                                } else {
                                    Button(
                                        onClick = {
                                            selectedUsbDevice = device
                                            connecting = true
                                            status = "Connecting via USB…"
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        val usbConn = usbManager.openDevice(device)
                                                            ?: throw java.io.IOException("Cannot open USB device")
                                                        AdbConnection.connectUsb(ctx as ComponentActivity, device, usbConn)
                                                    }
                                                }.fold(
                                                    onSuccess = { conn ->
                                                        adbConn = conn
                                                        connected = true
                                                        connecting = false
                                                        status = null
                                                        outputLines.add("--- Connected via USB (${device.productName}) ---")
                                                    },
                                                    onFailure = {
                                                        connecting = false
                                                        status = "✗ ${it.message}"
                                                    }
                                                )
                                            }
                                        },
                                        enabled = !connecting,
                                    ) { Text("Connect") }
                                }
                            }
                        }
                    }
                }
            }

            status?.let { msg ->
                Spacer(Modifier.height(12.dp))
                val color = if (msg.startsWith("✗")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            // ── Terminal view ────────────────────────────────────────────

            // Output area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp),
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (line.startsWith("\$ ")) Color(0xFF4EC9B0) else Color(0xFFD4D4D4),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Command input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("e.g. ls /sdcard") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (command.isNotBlank() && !running) {
                            val cmd = command.trim()
                            command = ""
                            running = true
                            outputLines.add("\$ $cmd")
                            scope.launch {
                                executeShell(adbConn, cmd, outputLines)
                                running = false
                            }
                        }
                    }),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (command.isNotBlank() && !running) {
                            val cmd = command.trim()
                            command = ""
                            running = true
                            outputLines.add("\$ $cmd")
                            scope.launch {
                                executeShell(adbConn, cmd, outputLines)
                                running = false
                            }
                        }
                    },
                    enabled = command.isNotBlank() && !running,
                ) {
                    Text("Run")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Disconnect + clear buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = {
                    outputLines.clear()
                }) {
                    Text("Clear")
                }
                OutlinedButton(onClick = {
                    adbConn?.close()
                    adbConn = null
                    connected = false
                    outputLines.clear()
                    status = null
                }) {
                    Text("Disconnect")
                }
            }
        }
    }
}

private suspend fun executeShell(
    conn: AdbConnection?,
    cmd: String,
    output: MutableList<String>,
) {
    if (conn == null) {
        output.add("[error] Not connected")
        return
    }
    withContext(Dispatchers.IO) {
        try {
            val stream = conn.open("shell:$cmd")
            val inp = stream.inputStream
            val buf = ByteArray(4096)
            val lineBuffer = StringBuilder()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                val text = String(buf, 0, n, Charsets.UTF_8)
                lineBuffer.append(text)
                // Emit complete lines
                while (true) {
                    val idx = lineBuffer.indexOf('\n')
                    if (idx < 0) break
                    val line = lineBuffer.substring(0, idx).trimEnd('\r')
                    withContext(Dispatchers.Main) { output.add(line) }
                    lineBuffer.delete(0, idx + 1)
                }
            }
            // Emit remaining partial line
            if (lineBuffer.isNotEmpty()) {
                val remaining = lineBuffer.toString().trimEnd('\r', '\n')
                if (remaining.isNotEmpty()) {
                    withContext(Dispatchers.Main) { output.add(remaining) }
                }
            }
            stream.close()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { output.add("[error] ${e.message}") }
        }
    }
}
