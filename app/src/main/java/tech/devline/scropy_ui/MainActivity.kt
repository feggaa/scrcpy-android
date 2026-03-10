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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tech.devline.scropy_ui.adb.AdbAuthHelper
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.adb.AdbPairing
import tech.devline.scropy_ui.ui.theme.ScropyTheme
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// --- Navigation model ---------------------------------------------------------

enum class AppAction { STREAM, SHELL }

data class SavedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val model: String? = null,
    val androidVersion: String? = null,
    val screenshotPath: String? = null,
)

sealed class Screen {
    object DeviceList : Screen()
    object ChooseTransport : Screen()
    object WifiConnect : Screen()
    object UsbConnect : Screen()
    data class ShellTerminal(val conn: AdbConnection, val label: String) : Screen()
}

// --- Saved-device persistence -------------------------------------------------

private const val PREFS_NAME = "scropy_prefs"
private const val KEY_SAVED = "saved_devices_v2"

private fun loadDevices(prefs: android.content.SharedPreferences): List<SavedDevice> {
    val raw = prefs.getString(KEY_SAVED, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).run {
                SavedDevice(
                    getString("id"), getString("name"), getString("host"), getInt("port"),
                    optString("model").takeIf { it.isNotEmpty() },
                    optString("androidVersion").takeIf { it.isNotEmpty() },
                    optString("screenshotPath").takeIf { it.isNotEmpty() },
                )
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun persistDevices(prefs: android.content.SharedPreferences, list: List<SavedDevice>) {
    val arr = JSONArray()
    list.forEach { d ->
        arr.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("host", d.host); put("port", d.port)
            d.model?.let { put("model", it) }
            d.androidVersion?.let { put("androidVersion", it) }
            d.screenshotPath?.let { put("screenshotPath", it) }
        })
    }
    prefs.edit().putString(KEY_SAVED, arr.toString()).apply()
}

private fun upsertDevice(prefs: android.content.SharedPreferences, device: SavedDevice) {
    val list = loadDevices(prefs).toMutableList()
    val idx = list.indexOfFirst { it.host == device.host && it.port == device.port }
    if (idx >= 0) list[idx] = device.copy(id = list[idx].id) else list.add(0, device)
    persistDevices(prefs, list)
}

private fun deleteDevice(prefs: android.content.SharedPreferences, id: String) =
    persistDevices(prefs, loadDevices(prefs).filter { it.id != id })

// --- Network scan ------------------------------------------------------------

data class ScanResult(val host: String, val port: Int?, val label: String?)

private fun getLocalSubnetPrefix(): String? = getLocalIpInfo()?.substringBeforeLast('.')?.plus('.')

private fun getLocalIpInfo(): String? = try {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
        ?.flatMap { it.interfaceAddresses.toList() }
        ?.firstOrNull { addr ->
            addr.address is java.net.Inet4Address && !addr.address.isLoopbackAddress
        }
        ?.address?.hostAddress
} catch (_: Exception) { null }

private fun resolveHostname(ip: String): String? = runCatching {
    java.net.InetAddress.getByName(ip).hostName
        .takeIf { h -> h != ip && !h.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
}.getOrNull()

suspend fun scanForDevices(): List<ScanResult> = withContext(Dispatchers.IO) {
    val prefix = getLocalSubnetPrefix() ?: return@withContext emptyList()
    val myIp = getLocalIpInfo()
    val found = Collections.synchronizedList(mutableListOf<ScanResult>())
    coroutineScope {
        (1..254).forEach { i ->
            launch {
                val ip = "$prefix$i"
                if (ip == myIp) return@launch
                val adbOpen = runCatching {
                    Socket().use { s -> s.connect(InetSocketAddress(ip, 5555), 400) }
                    true
                }.getOrElse { false }
                if (adbOpen) {
                    val label = resolveHostname(ip)
                    found.add(ScanResult(ip, 5555, label))
                } else {
                    val reachable = runCatching {
                        java.net.InetAddress.getByName(ip).isReachable(400)
                    }.getOrElse { false }
                    if (reachable) {
                        val label = resolveHostname(ip)
                        found.add(ScanResult(ip, null, label))
                    }
                }
            }
        }
    }
    found.sortedWith(compareBy(
        { it.port == null },
        { it.host.substringAfterLast('.').toIntOrNull() ?: 0 },
    ))
}

// Scan all 5-digit ports on a specific host for open ADB connections
suspend fun scanAdbPorts(ip: String): List<Int> = withContext(Dispatchers.IO) {
    val open = Collections.synchronizedList(mutableListOf<Int>())
    val sem = Semaphore(300)
    coroutineScope {
        (10000..65535).forEach { port ->
            launch {
                sem.withPermit {
                    val ok = runCatching {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, port), 300) }
                        true
                    }.getOrElse { false }
                    if (ok) open.add(port)
                }
            }
        }
    }
    open.sorted()
}

// --- mDNS device discovery ---------------------------------------------------

data class DiscoveredDevice(val name: String, val host: String, val port: Int)

fun discoverAdbViaBonjour(
    context: android.content.Context,
    onFound: (DiscoveredDevice) -> Unit,
): android.net.nsd.NsdManager.DiscoveryListener {
    val nsd = context.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val pending = LinkedBlockingQueue<android.net.nsd.NsdServiceInfo>()
    val resolving = AtomicBoolean(false)
    val myIp = getLocalIpInfo()

    fun resolveNext() {
        val svc = pending.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(svc, object : android.net.nsd.NsdManager.ResolveListener {
            override fun onResolveFailed(si: android.net.nsd.NsdServiceInfo, e: Int) = resolveNext()
            override fun onServiceResolved(si: android.net.nsd.NsdServiceInfo) {
                si.host?.hostAddress?.let { ip ->
                    if (ip != myIp) handler.post { onFound(DiscoveredDevice(si.serviceName, ip, si.port)) }
                }
                resolveNext()
            }
        })
    }

    return object : android.net.nsd.NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onServiceFound(svc: android.net.nsd.NsdServiceInfo) {
            pending.add(svc)
            if (resolving.compareAndSet(false, true)) resolveNext()
        }
        override fun onServiceLost(svc: android.net.nsd.NsdServiceInfo) {}
    }.also { listener ->
        nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
    }
}

// --- Device metadata ---------------------------------------------------------

suspend fun fetchDeviceMetadata(
    conn: AdbConnection,
    context: android.content.Context,
    deviceId: String,
): Triple<String?, String?, String?> = withContext(Dispatchers.IO) {
    fun readProp(prop: String): String? = runCatching {
        val s = conn.open("shell:getprop $prop")
        val buf = ByteArray(256); val sb = StringBuilder()
        while (true) { val n = s.inputStream.read(buf); if (n < 0) break; sb.append(String(buf, 0, n, Charsets.UTF_8)) }
        s.close(); sb.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
    val model = readProp("ro.product.model")
    val androidVersion = readProp("ro.build.version.release")
    val screenshotPath = runCatching {
        val s = conn.open("exec:screencap -p")
        val baos = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (true) { val n = s.inputStream.read(buf); if (n < 0) break; baos.write(buf, 0, n) }
        s.close()
        val bytes = baos.toByteArray()
        if (bytes.size < 8) return@runCatching null
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        val file = java.io.File(context.filesDir, "screenshot_$deviceId.jpg")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }
        file.absolutePath
    }.getOrNull()
    Triple(model, androidVersion, screenshotPath)
}

// --- USB helper ---------------------------------------------------------------

private fun findAdbDevices(usbManager: UsbManager): List<UsbDevice> =
    usbManager.deviceList.values.filter { device ->
        (0 until device.interfaceCount).any { i ->
            device.getInterface(i).run {
                interfaceClass == 0xFF && interfaceSubclass == 0x42 && interfaceProtocol == 0x01
            }
        }
    }

// --- MainActivity -------------------------------------------------------------

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "tech.devline.scropy_ui.USB_PERMISSION"
    }

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
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            ScropyTheme {
                AppNavHost(
                    onLaunchStream = { host, port ->
                        startActivity(Intent(this, StreamActivity::class.java).apply {
                            putExtra(StreamActivity.EXTRA_HOST, host)
                            putExtra(StreamActivity.EXTRA_PORT, port)
                        })
                    },
                    onLaunchUsbStream = { device ->
                        startActivity(Intent(this, StreamActivity::class.java).apply {
                            putExtra(StreamActivity.EXTRA_USB_DEVICE, device)
                        })
                    },
                    onRequestUsbPermission = { device, cb ->
                        usbPermissionCallback = cb
                        val mgr = getSystemService(USB_SERVICE) as UsbManager
                        mgr.requestPermission(
                            device,
                            PendingIntent.getBroadcast(
                                this, 0, Intent(ACTION_USB_PERMISSION),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                            )
                        )
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

// --- Navigation host ----------------------------------------------------------

@Composable
fun AppNavHost(
    onLaunchStream: (host: String, port: Int) -> Unit,
    onLaunchUsbStream: (device: UsbDevice) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, cb: (Boolean) -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.DeviceList)) }
    val current = stack.last()

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun popToRoot() { stack = listOf(Screen.DeviceList) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = current) {
        Screen.DeviceList -> DeviceListScreen(
            prefs = prefs,
            onNewConnection = { push(Screen.ChooseTransport) },
            onLaunchStream = onLaunchStream,
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
        )
        Screen.ChooseTransport -> ChooseTransportScreen(
            onBack = ::pop,
            onWifi = { push(Screen.WifiConnect) },
            onUsb = { push(Screen.UsbConnect) },
        )
        Screen.WifiConnect -> WifiConnectScreen(
            prefs = prefs,
            onBack = ::pop,
            onLaunchStream = { host, port -> onLaunchStream(host, port); popToRoot() },
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
        )
        Screen.UsbConnect -> UsbConnectScreen(
            onBack = ::pop,
            onLaunchUsbStream = { device -> onLaunchUsbStream(device); popToRoot() },
            onOpenShell = { conn, label -> push(Screen.ShellTerminal(conn, label)) },
            onRequestUsbPermission = onRequestUsbPermission,
        )
        is Screen.ShellTerminal -> ShellTerminalScreen(
            conn = s.conn,
            label = s.label,
            onDisconnect = { runCatching { s.conn.close() }; pop() },
        )
    }
}

// --- Screen: Device List ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    prefs: android.content.SharedPreferences,
    onNewConnection: () -> Unit,
    onLaunchStream: (host: String, port: Int) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(loadDevices(prefs)) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = { TextButton(onClick = onNewConnection) { Text("+ New") } },
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No saved devices yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNewConnection) { Text("+ New Connection") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                        ) {
                            // Screenshot thumbnail — no padding, flush left edge
                            val screenshotBitmap by produceState(
                                initialValue = null as android.graphics.Bitmap?,
                                device.screenshotPath,
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    device.screenshotPath?.let { path ->
                                        runCatching {
                                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                            android.graphics.BitmapFactory.decodeFile(path, opts)
                                        }.getOrNull()
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (screenshotBitmap != null) {
                                    Image(
                                        bitmap = screenshotBitmap!!.asImageBitmap(),
                                        contentDescription = "Last screenshot",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        filterQuality = FilterQuality.None,
                                    )
                                } else {
                                    Text("📱", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(device.model ?: device.name, style = MaterialTheme.typography.titleSmall)
                                        if (device.androidVersion != null) {
                                            Text(
                                                "Android ${device.androidVersion}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            "${device.host}:${device.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { deleteDevice(prefs, device.id); devices = loadDevices(prefs) },
                                        enabled = connectingId == null,
                                    ) { Text("X") }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (connectingId == device.id) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                if (connectingId != null) return@OutlinedButton
                                                connectingId = device.id; errorMsg = null
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            AdbConnection.connectTcp(ctx as ComponentActivity, device.host, device.port)
                                                        }
                                                    }.fold(
                                                        onSuccess = { conn ->
                                                            // Refresh screenshot in background
                                                            scope.launch {
                                                                val (_, _, screenshot) = fetchDeviceMetadata(conn, ctx, device.id)
                                                                if (screenshot != null) {
                                                                    upsertDevice(prefs, device.copy(screenshotPath = screenshot))
                                                                    devices = loadDevices(prefs)
                                                                }
                                                                conn.close()
                                                            }
                                                            connectingId = null; onLaunchStream(device.host, device.port)
                                                        },
                                                        onFailure = { connectingId = null; errorMsg = it.message ?: "Connection failed" },
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                                        ) { Text("Stream") }
                                        OutlinedButton(
                                            onClick = {
                                                if (connectingId != null) return@OutlinedButton
                                                connectingId = device.id; errorMsg = null
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            AdbConnection.connectTcp(ctx as ComponentActivity, device.host, device.port)
                                                        }
                                                    }.fold(
                                                        onSuccess = { conn ->
                                                            // Refresh screenshot then open shell
                                                            scope.launch {
                                                                val (_, _, screenshot) = fetchDeviceMetadata(conn, ctx, device.id)
                                                                if (screenshot != null) {
                                                                    upsertDevice(prefs, device.copy(screenshotPath = screenshot))
                                                                    devices = loadDevices(prefs)
                                                                }
                                                            }
                                                            connectingId = null; onOpenShell(conn, device.model ?: device.name)
                                                        },
                                                        onFailure = { connectingId = null; errorMsg = it.message ?: "Connection failed" },
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                                        ) { Text("Shell") }
                                    }
                                }
                            }
                        }
                    }
                }
                errorMsg?.let { msg ->
                    item {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// --- Screen: Choose Transport -------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseTransportScreen(onBack: () -> Unit, onWifi: () -> Unit, onUsb: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Connection") },
                navigationIcon = { TextButton(onClick = onBack) { Text("< Back") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("How do you want to connect?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(32.dp))
            ElevatedCard(onClick = onWifi, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("ADB over WiFi", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect wirelessly via IP address.\nRequires Wireless Debugging enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(onClick = onUsb, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("ADB over USB", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect via USB OTG cable.\nRequires USB Debugging enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- Screen: WiFi Connect -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConnectScreen(
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit,
    onLaunchStream: (host: String, port: Int) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val scrollState = rememberScrollState()

    var host by remember { mutableStateOf(prefs.getString("last_host", "") ?: "") }
    var adbPortStr by remember { mutableStateOf(prefs.getString("last_adb_port", "") ?: "") }
    var action by remember { mutableStateOf(AppAction.STREAM) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showPair by remember { mutableStateOf(true) }
    var pairPortStr by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var portScanHost by remember { mutableStateOf<String?>(null) }
    var portScanning by remember { mutableStateOf(false) }
    var portScanResults by remember { mutableStateOf<List<Int>>(emptyList()) }
    var portScanDone by remember { mutableStateOf(false) }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var discovering by remember { mutableStateOf(false) }
    val nsdManager = remember(ctx) { ctx.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager }
    val nsdListenerRef = remember { mutableStateOf<android.net.nsd.NsdManager.DiscoveryListener?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            nsdListenerRef.value?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
            runCatching { pendingConn?.close() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Connection") },
                navigationIcon = { TextButton(onClick = onBack) { Text("< Back") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("What do you want to do?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = action == AppAction.STREAM,
                    onClick = { action = AppAction.STREAM },
                    label = { Text("Stream") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = action == AppAction.SHELL,
                    onClick = { action = AppAction.SHELL },
                    label = { Text("Shell") },
                )
            }
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
                value = adbPortStr, onValueChange = { adbPortStr = it.filter(Char::isDigit) },
                label = { Text("ADB port (shown under Wireless debugging)") },
                placeholder = { Text("e.g. 38765") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
            Spacer(Modifier.height(12.dp))

            // ── Network scan ────────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    focus.clearFocus()
                    scanning = true
                    scanResults = emptyList()
                    discoveredDevices.clear()
                    discovering = true
                    // Stop any previous NSD session
                    nsdListenerRef.value?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
                    // Start mDNS discovery — finds named Android devices via _adb-tls-connect._tcp
                    val listener = discoverAdbViaBonjour(ctx) { device -> discoveredDevices.add(device) }
                    nsdListenerRef.value = listener
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        runCatching { nsdManager.stopServiceDiscovery(listener) }
                        discovering = false
                    }
                    // Subnet scan runs in parallel
                    scope.launch {
                        scanResults = scanForDevices()
                        scanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning && !discovering && !connecting && !pairing,
            ) {
                if (scanning || discovering) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (scanning || discovering) "Scanning..." else "Discover & scan network")
            }

            if ((scanning || discovering) && discoveredDevices.isEmpty() && scanResults.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Discovering devices on your network\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Named devices (mDNS / Wireless Debugging) ────────────────
            if (discoveredDevices.isNotEmpty() || discovering) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Android Devices",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (discovering) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                discoveredDevices.forEach { device ->
                    ElevatedCard(
                        onClick = {
                            host = device.host
                            adbPortStr = device.port.toString()
                            portScanHost = null; portScanResults = emptyList(); portScanDone = false
                            scanResults = emptyList(); discoveredDevices.clear()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${device.host}  \u00b7  port ${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Port scan for selected host ──────────────────────────────
            if (portScanHost != null && portScanHost == host.trim() && adbPortStr.isBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        portScanning = true
                        portScanResults = emptyList()
                        portScanDone = false
                        val targetHost = portScanHost!!
                        scope.launch {
                            portScanResults = scanAdbPorts(targetHost)
                            portScanning = false
                            portScanDone = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !portScanning && !connecting && !pairing,
                ) {
                    if (portScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (portScanning) "Scanning ports on $portScanHost..."
                        else "Scan all ADB ports on $portScanHost",
                    )
                }
                if (portScanning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Probing all 5-digit ports (10000–65535) in parallel…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (portScanResults.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open ADB ports — tap to use:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    portScanResults.forEach { p ->
                        OutlinedButton(
                            onClick = {
                                adbPortStr = p.toString()
                                portScanHost = null
                                portScanResults = emptyList()
                                portScanDone = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("${portScanHost}:$p", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (portScanDone && portScanResults.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No open 5-digit ports found on $portScanHost.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!scanning && scanResults.isEmpty() && host.isBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tip: tap \"Scan\" to auto-discover ADB devices on your WiFi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    focus.clearFocus()
                    val port = adbPortStr.toIntOrNull() ?: return@Button
                    if (host.isBlank()) return@Button
                    runCatching { pendingConn?.close() }; pendingConn = null
                    connecting = true; status = "Connecting..."; showPair = true
                    prefs.edit().putString("last_host", host.trim()).putString("last_adb_port", adbPortStr).apply()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                AdbConnection.connectTcp(ctx as ComponentActivity, host.trim(), port)
                            }
                        }.fold(
                            onSuccess = { conn ->
                                pendingConn = conn
                                connecting = false; status = "Getting device info..."
                                val deviceId = UUID.randomUUID().toString()
                                val baseDevice = SavedDevice(deviceId, host.trim(), host.trim(), port)
                                upsertDevice(prefs, baseDevice)
                                val (model, ver, screenshot) = fetchDeviceMetadata(conn, ctx, deviceId)
                                upsertDevice(prefs, baseDevice.copy(
                                    name = model ?: host.trim(),
                                    model = model,
                                    androidVersion = ver,
                                    screenshotPath = screenshot,
                                ))
                                status = null
                                pendingConn = null
                                when (action) {
                                    AppAction.STREAM -> { conn.close(); onLaunchStream(host.trim(), port) }
                                    AppAction.SHELL  -> onOpenShell(conn, model ?: host.trim())
                                }
                            },
                            onFailure = { e -> connecting = false; status = "X ${e.message}"; showPair = true },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting && !pairing && host.isNotBlank() && adbPortStr.isNotBlank(),
            ) {
                if (connecting) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (connecting) "Connecting..." else "Connect")
            }
            status?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = if (msg.startsWith("X")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showPair) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Device not paired yet", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "On device: Settings > Developer options > Wireless debugging\n> \"Pair device with pairing code\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pairPortStr, onValueChange = { pairPortStr = it.filter(Char::isDigit) },
                    label = { Text("Pairing port (shown on pairing dialog)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pairCode, onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("6-digit pairing code") },
                    placeholder = { Text("e.g. 123456") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        val pairPort = pairPortStr.toIntOrNull() ?: return@Button
                        val adbPort  = adbPortStr.toIntOrNull()  ?: return@Button
                        if (host.isBlank() || pairCode.length != 6) return@Button
                        runCatching { pendingConn?.close() }; pendingConn = null
                        pairing = true; status = "Pairing..."
                        scope.launch {
                            val (priv, pub) = AdbAuthHelper.getOrCreateKeys(ctx as ComponentActivity)
                            runCatching { AdbPairing.pair(host.trim(), pairPort, pairCode, priv, pub) }.fold(
                                onSuccess = {
                                    status = "Paired! Connecting..."
                                    kotlinx.coroutines.delay(2000)
                                    runCatching {
                                        withContext(Dispatchers.IO) { AdbConnection.connectTcp(ctx, host.trim(), adbPort) }
                                    }.fold(
                                        onSuccess = { conn ->
                                            pendingConn = conn
                                            pairing = false; status = "Getting device info..."
                                            val deviceId = UUID.randomUUID().toString()
                                            val baseDevice = SavedDevice(deviceId, host.trim(), host.trim(), adbPort)
                                            upsertDevice(prefs, baseDevice)
                                            val (model, ver, screenshot) = fetchDeviceMetadata(conn, ctx, deviceId)
                                            upsertDevice(prefs, baseDevice.copy(
                                                name = model ?: host.trim(),
                                                model = model,
                                                androidVersion = ver,
                                                screenshotPath = screenshot,
                                            ))
                                            status = null; showPair = true
                                            pendingConn = null
                                            when (action) {
                                                AppAction.STREAM -> { conn.close(); onLaunchStream(host.trim(), adbPort) }
                                                AppAction.SHELL  -> onOpenShell(conn, model ?: host.trim())
                                            }
                                        },
                                        onFailure = { e -> pairing = false; status = "Paired OK, but connect failed: ${e.message}" },
                                    )
                                },
                                onFailure = { e -> pairing = false; status = "X Pairing failed: ${e.message}" },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pairing && !connecting && pairPortStr.isNotBlank() && pairCode.length == 6,
                ) {
                    if (pairing) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (pairing) "Pairing..." else "Pair & Connect")
                }
            }
        }
    }
}

// --- Screen: USB Connect ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbConnectScreen(
    onBack: () -> Unit,
    onLaunchUsbStream: (device: UsbDevice) -> Unit,
    onOpenShell: (conn: AdbConnection, label: String) -> Unit,
    onRequestUsbPermission: (device: UsbDevice, cb: (Boolean) -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }

    var action by remember { mutableStateOf(AppAction.STREAM) }
    var devices by remember { mutableStateOf(findAdbDevices(usbManager)) }
    var connectingDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingConn by remember { mutableStateOf<AdbConnection?>(null) }

    LaunchedEffect(Unit) { devices = findAdbDevices(usbManager) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB Connection") },
                navigationIcon = { TextButton(onClick = onBack) { Text("< Back") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 24.dp).fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            Text("What do you want to do?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = action == AppAction.STREAM, onClick = { action = AppAction.STREAM },
                    label = { Text("Stream") }, modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = action == AppAction.SHELL, onClick = { action = AppAction.SHELL },
                    label = { Text("Shell") },
                )
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { devices = findAdbDevices(usbManager); status = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan USB Devices") }
            Spacer(Modifier.height(16.dp))
            if (devices.isEmpty()) {
                Text(
                    "No ADB-capable USB devices found.\n\nMake sure:\n- USB debugging is enabled\n- Connected via OTG cable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    val hasPermission = usbManager.hasPermission(device)
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.productName ?: "Unknown Device", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "VID: 0x${device.vendorId.toString(16).uppercase()}  PID: 0x${device.productId.toString(16).uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            when {
                                !hasPermission -> Button(
                                    onClick = {
                                        onRequestUsbPermission(device) { granted ->
                                            if (granted) { devices = findAdbDevices(usbManager); status = null }
                                            else status = "Permission denied"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Grant Permission") }
                                connectingDevice == device -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                                }
                                else -> Button(
                                    onClick = {
                                        if (connectingDevice != null) return@Button
                                        runCatching { pendingConn?.close() }; pendingConn = null
                                        connectingDevice = device; status = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    AdbConnection.connectUsb(ctx as ComponentActivity, device, usbManager)
                                                }
                                            }.fold(
                                                onSuccess = { conn ->
                                                    connectingDevice = null
                                                    when (action) {
                                                        AppAction.STREAM -> { conn.close(); onLaunchUsbStream(device) }
                                                        AppAction.SHELL  -> onOpenShell(conn, device.productName ?: "USB Device")
                                                    }
                                                },
                                                onFailure = { e -> connectingDevice = null; status = "X ${e.message}" },
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = connectingDevice == null,
                                ) { Text("Connect") }
                            }
                        }
                    }
                }
            }
            status?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = if (msg.startsWith("X")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// --- Screen: Shell Terminal ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellTerminalScreen(
    conn: AdbConnection,
    label: String,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val outputLines = remember { mutableStateListOf("--- Connected to $label ---") }
    val focus = LocalFocusManager.current

    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) listState.animateScrollToItem(outputLines.size - 1)
    }

    fun runCmd() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        command = ""; focus.clearFocus(); running = true
        outputLines.add("\$ $cmd")
        scope.launch { executeShell(conn, cmd, outputLines); running = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
                actions = { TextButton(onClick = { outputLines.clear() }) { Text("Clear") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("Command") }, placeholder = { Text("e.g. ls /sdcard") },
                    singleLine = true, modifier = Modifier.weight(1f), enabled = !running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { runCmd() }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::runCmd, enabled = command.isNotBlank() && !running) { Text("Run") }
            }
        }
    }
}

// --- Shell execution ----------------------------------------------------------

private suspend fun executeShell(
    conn: AdbConnection,
    cmd: String,
    output: MutableList<String>,
) = withContext(Dispatchers.IO) {
    try {
        val stream = conn.open("shell:$cmd")
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        while (true) {
            val n = stream.inputStream.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
            while (true) {
                val idx = sb.indexOf('\n')
                if (idx < 0) break
                val line = sb.substring(0, idx).trimEnd('\r')
                withContext(Dispatchers.Main) { output.add(line) }
                sb.delete(0, idx + 1)
            }
        }
        if (sb.isNotEmpty()) {
            val remaining = sb.toString().trimEnd('\r', '\n')
            if (remaining.isNotEmpty()) withContext(Dispatchers.Main) { output.add(remaining) }
        }
        stream.close()
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { output.add("[error] ${e.message}") }
    }
}
