// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import app.grom.server.mesh.BleAdvertisement
import app.grom.server.mesh.MeshCandidate
import app.grom.server.mesh.MeshRadio
import app.grom.server.mesh.MeshTransport
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * The Android half of the server network (D99, D102).
 *
 * Everything here is a thin adapter: it publishes discovery sightings and
 * provides a byte carrier. Bounds, dedupe, expiry, trust and transport choice
 * live in the pure `mesh/` package, so they are JVM-tested even though the
 * radios are not.
 *
 * The one rule the adapters enforce themselves is that a sighting is a **hint**:
 * a BLE advertisement is unsigned, a Wi-Fi Direct device name is chosen by the
 * other side, and a bonded Classic link proves only that Android paired once.
 * Every candidate is handed to [app.grom.server.mesh.DiscoveryBook] with
 * `signedBeacon = false`; trust still comes only from the Grom pairing code.
 */
internal object AndroidMeshPermissions {

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    val BLE: List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        @Suppress("DEPRECATION")
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            // Android 12+ rejects a FINE request that does not name COARSE, and
            // the manifest declares both. A COARSE-only grant keeps FINE in the
            // missing list, so the screen says what discovery still needs rather
            // than pretending it is ready.
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val WIFI_DIRECT: List<String> = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val CLASSIC: List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        @Suppress("DEPRECATION")
        listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }
}

/**
 * BLE advertising and scanning (D99).
 *
 * The advertisement is [BleAdvertisement]'s eight bytes; the scan filter is the
 * one service UUID, so the radio never reports unrelated devices. Discovery is
 * data-free on purpose: a BLE link is a name tag, not a carrier.
 */
internal class BleMeshRadio(
    private val context: Context,
    private val nodeId: () -> String?,
    private val port: () -> Int,
    private val dataPlaneUp: () -> Boolean
) : MeshRadio {

    override val transport: MeshTransport = MeshTransport.BLUETOOTH_LE

    @Volatile
    override var lastError: String? = null
        private set

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var sink: ((MeshCandidate) -> Unit)? = null

    override fun isAvailable(): Boolean =
        adapter?.isEnabled == true && adapter?.isMultipleAdvertisementSupported == true

    override fun missingPermissions(): List<String> =
        AndroidMeshPermissions.missing(context, AndroidMeshPermissions.BLE)

    @SuppressLint("MissingPermission")
    override fun start(sink: (MeshCandidate) -> Unit): Boolean {
        this.sink = sink
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            lastError = "BLE_UNAVAILABLE"
            return false
        }
        if (missingPermissions().isNotEmpty()) {
            lastError = "MESH_PERMISSION_REQUIRED"
            return false
        }
        lastError = null
        startAdvertising(adapter)
        startScanning(adapter)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        val node = nodeId() ?: return
        val payload = BleAdvertisement.encode(node, port(), dataPlaneUp()) ?: return
        val radio = adapter.bluetoothLeAdvertiser ?: run {
            lastError = "BLE_ADVERTISE_UNAVAILABLE"
            return
        }
        advertiser = radio
        val uuid = ParcelUuid.fromString(BleAdvertisement.SERVICE_UUID)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(uuid)
            .addServiceData(uuid, payload)
            .setIncludeDeviceName(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                lastError = "BLE_ADVERTISE_FAILED"
            }
        }
        advertiseCallback = callback
        runCatching { radio.startAdvertising(settings, data, callback) }
            .onFailure { lastError = "BLE_ADVERTISE_FAILED" }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(adapter: BluetoothAdapter) {
        val radio = adapter.bluetoothLeScanner ?: run {
            lastError = "BLE_SCAN_UNAVAILABLE"
            return
        }
        scanner = radio
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val decoded = BleAdvertisement.decode(
                    result.scanRecord?.getServiceData(
                        ParcelUuid.fromString(BleAdvertisement.SERVICE_UUID)
                    )
                ) ?: return
                val now = System.currentTimeMillis()
                sink?.invoke(
                    MeshCandidate(
                        key = decoded.key(),
                        nodeId = null,
                        nodeIdPrefix = decoded.nodeIdPrefix,
                        publicKey = null,
                        label = null,
                        transport = MeshTransport.BLUETOOTH_LE,
                        address = "",
                        port = decoded.port,
                        signedBeacon = false,
                        firstSeenMs = now,
                        lastSeenMs = now
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                lastError = "BLE_SCAN_FAILED"
            }
        }
        scanCallback = callback
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(BleAdvertisement.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { radio.startScan(listOf(filter), settings, callback) }
            .onFailure { lastError = "BLE_SCAN_FAILED" }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        runCatching {
            val callback = scanCallback
            if (callback != null) scanner?.stopScan(callback)
        }
        runCatching {
            val callback = advertiseCallback
            if (callback != null) advertiser?.stopAdvertising(callback)
        }
        scanCallback = null
        advertiseCallback = null
        scanner = null
        advertiser = null
    }

    @SuppressLint("MissingPermission")
    override fun scanNow() {
        if (!isAvailable() || missingPermissions().isNotEmpty()) return
        val radio = scanner ?: adapter?.bluetoothLeScanner ?: return
        val callback = scanCallback ?: return
        runCatching { radio.stopScan(callback) }
        runCatching {
            radio.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString(BleAdvertisement.SERVICE_UUID))
                        .build()
                ),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
        }
    }
}

/**
 * Wi-Fi Direct discovery (D99).
 *
 * Discovery uses the platform's DNS-SD service records rather than the device
 * name, because the name is chosen by the other side and cannot carry a port
 * safely. The service publishes two TXT fields — `node` (the first eight hex
 * digits of the node id) and `port` (the data-plane port) — and the same fields
 * are parsed back from every Grom service the radio sees.
 *
 * The group owner's IP is only known after a connection; the client side then
 * combines it with the port from the service record. A group with more than one
 * Grom server needs a real discovery protocol, which is why the LAN carrier is
 * preferred whenever both phones share a network.
 */
@SuppressLint("MissingPermission")
internal class WifiDirectMeshRadio(
    private val context: Context,
    private val nodeId: () -> String?,
    private val port: () -> Int
) : MeshRadio {

    override val transport: MeshTransport = MeshTransport.WIFI_DIRECT

    @Volatile
    override var lastError: String? = null
        private set

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var sink: ((MeshCandidate) -> Unit)? = null
    private var request: WifiP2pDnsSdServiceRequest? = null

    /** The most recent service record seen; on the client side this is the owner. */
    @Volatile
    private var lastRecord: Pair<String, Int>? = null

    override fun isAvailable(): Boolean = context.packageManager.hasSystemFeature(
        PackageManager.FEATURE_WIFI_DIRECT
    )

    override fun missingPermissions(): List<String> =
        AndroidMeshPermissions.missing(context, AndroidMeshPermissions.WIFI_DIRECT)

    override fun start(sink: (MeshCandidate) -> Unit): Boolean {
        this.sink = sink
        if (!isAvailable()) {
            lastError = "WIFI_DIRECT_UNAVAILABLE"
            return false
        }
        if (missingPermissions().isNotEmpty()) {
            lastError = "MESH_PERMISSION_REQUIRED"
            return false
        }
        val created = context.getSystemService(WifiP2pManager::class.java) ?: run {
            lastError = "WIFI_DIRECT_UNAVAILABLE"
            return false
        }
        manager = created
        val channel = created.initialize(context, Looper.getMainLooper(), null)
        this.channel = channel
        lastRecord = null
        register()
        advertiseService(created, channel)
        discoverServices(created, channel)
        discover(created, channel)
        return true
    }

    private fun advertiseService(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        val prefix = nodeId()?.takeIf { it.length >= 8 }?.substring(0, 8) ?: return
        val dataPort = port()
        if (dataPort !in 1..65535) return
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            INSTANCE,
            SERVICE_TYPE,
            mapOf("node" to prefix, "port" to dataPort.toString())
        )
        manager.addLocalService(channel, info, null)
    }

    private fun discoverServices(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.setDnsSdResponseListeners(
            channel,
            { _: String, _: String, _: WifiP2pDevice ->
                // The service record was parsed by the TXT listener below; the
                // response proves it exists, so a hint is emitted now and the
                // endpoint follows when the group forms.
                val record = lastRecord ?: return@setDnsSdResponseListeners
                emit(record, address = "")
            },
            { _: String, txt: Map<String, String>, _: WifiP2pDevice ->
                parseTxt(txt)?.let { lastRecord = it }
            }
        )
        val created = WifiP2pDnsSdServiceRequest.newInstance()
        request = created
        manager.addServiceRequest(channel, created, null)
        manager.discoverServices(channel, null)
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            lastError = "WIFI_DIRECT_DISABLED"
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                }
            }
        }
        receiver = created
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(created, filter)
        }
    }

    private fun discover(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                lastError = "WIFI_DIRECT_DISCOVERY_FAILED"
            }
        })
    }

    private fun requestPeers() {
        val manager = manager ?: return
        val channel = channel ?: return
        manager.requestPeers(channel) { _ ->
            // A group candidate needs the group owner IP, which only the
            // connection callback has; peers alone produce no endpoint.
            lastRecord?.let { emit(it, address = "") }
            requestConnection()
        }
    }

    private fun requestConnection() {
        val manager = manager ?: return
        val channel = channel ?: return
        manager.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed || info.isGroupOwner) return@requestConnectionInfo
            val owner = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
            if (owner.contains(':')) return@requestConnectionInfo
            val record = lastRecord ?: return@requestConnectionInfo
            if (record.second !in 1..65535) return@requestConnectionInfo
            emit(record, address = "http://$owner:${record.second}")
        }
    }

    private fun emit(record: Pair<String, Int>, address: String) {
        val now = System.currentTimeMillis()
        sink?.invoke(
            MeshCandidate(
                key = if (address.isEmpty()) "prefix:wifi_direct:${record.first}" else "addr:$address",
                nodeId = null,
                nodeIdPrefix = record.first,
                publicKey = null,
                label = null,
                transport = MeshTransport.WIFI_DIRECT,
                address = address,
                port = record.second,
                signedBeacon = false,
                firstSeenMs = now,
                lastSeenMs = now
            )
        )
    }

    override fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        val managerRef = manager
        val channelRef = this.channel
        if (managerRef != null && channelRef != null) {
            val pending = request
            if (pending != null) {
                runCatching { managerRef.removeServiceRequest(channelRef, pending, null) }
            }
            runCatching { managerRef.clearLocalServices(channelRef, null) }
            runCatching { managerRef.stopPeerDiscovery(channelRef, null) }
            runCatching { managerRef.removeGroup(channelRef, null) }
        }
        request = null
        manager = null
        channel = null
    }

    override fun scanNow() {
        val manager = manager ?: return
        val channel = channel ?: return
        discover(manager, channel)
        requestPeers()
    }

    private fun parseTxt(txt: Map<String, String>): Pair<String, Int>? {
        val prefix = txt["node"] ?: return null
        if (!PREFIX.matches(prefix)) return null
        val port = txt["port"]?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return prefix to port
    }

    companion object {
        const val INSTANCE: String = "grom"
        const val SERVICE_TYPE: String = "_grom._tcp"

        private val PREFIX = Regex("^[0-9a-f]{8}$")
    }
}

/**
 * The secure RFCOMM carrier (D102).
 *
 * `createRfcommSocketToServiceRecord` is the authenticated, encrypted variant:
 * it only works with a **bonded** device, and that is deliberate. Android's
 * pairing is the carrier's own authentication; Grom's Ed25519 pairing still
 * runs above it, so neither side is trusted because of Bluetooth alone.
 *
 * There is no insecure variant and no automatic reconnection: a dead link is a
 * failed call, and reconnecting is something the operator asks for.
 */
internal class AndroidRfcommLink(private val context: Context) : app.grom.server.mesh.net.DuplexLink {

    @SuppressLint("MissingPermission")
    override fun open(remote: String): app.grom.server.mesh.net.DuplexStream? {
        if (AndroidMeshPermissions.missing(context, AndroidMeshPermissions.CLASSIC).isNotEmpty()) {
            return null
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        val device = runCatching { adapter.getRemoteDevice(remote) }.getOrNull() ?: return null
        if (device.bondState != BluetoothDevice.BOND_BONDED) return null
        val socket = runCatching {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID).apply { connect() }
        }.getOrNull() ?: return null
        return object : app.grom.server.mesh.net.DuplexStream {
            override val input: InputStream = socket.inputStream
            override val output: OutputStream = socket.outputStream

            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        /** A fixed UUID: the RFCOMM channel is discovered over SDP. */
        val SERVICE_UUID: UUID = UUID.fromString("67726f6d-6d65-7368-7631-000000000002")
    }
}

/**
 * Accepts RFCOMM links and pipes them to the loopback data plane.
 *
 * The data plane is bound to `127.0.0.1` by default, so the bridge connects to
 * `127.0.0.1:<port>` and never exposes anything itself: the only listener is
 * the RFCOMM one Android owns, and only a bonded device can use it.
 */
internal class AndroidRfcommServer(
    private val context: Context,
    /** The data plane endpoint this bridge forwards to; null when unreachable. */
    private val target: () -> Pair<String, Int>?
) : MeshRadio {

    override val transport: MeshTransport = MeshTransport.BLUETOOTH_CLASSIC

    @Volatile
    override var lastError: String? = null
        private set

    @Volatile
    private var running: Boolean = false

    private var server: android.bluetooth.BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    override fun isAvailable(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        return manager.adapter?.isEnabled == true
    }

    override fun missingPermissions(): List<String> =
        AndroidMeshPermissions.missing(context, AndroidMeshPermissions.CLASSIC)

    @SuppressLint("MissingPermission")
    override fun start(sink: (MeshCandidate) -> Unit): Boolean {
        if (running) return true
        if (!isAvailable()) {
            lastError = "BLUETOOTH_UNAVAILABLE"
            return false
        }
        if (missingPermissions().isNotEmpty()) {
            lastError = "MESH_PERMISSION_REQUIRED"
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        val created = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(
                "grom-mesh",
                AndroidRfcommLink.SERVICE_UUID
            )
        }.getOrNull() ?: run {
            lastError = "BLUETOOTH_LISTEN_FAILED"
            return false
        }
        server = created
        running = true
        acceptThread = Thread({ acceptLoop(created) }, "grom-mesh-bt").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun acceptLoop(server: android.bluetooth.BluetoothServerSocket) {
        while (running) {
            val remote = runCatching { server.accept() }.getOrNull()
            if (remote == null) {
                // `stop()` closes the socket under the loop, which is the
                // normal exit. Any other failure (the adapter was turned off,
                // the stack dropped the record) must be *reported*, not
                // retried in a tight loop: `continue` here would spin a core
                // at 100% while the screen still said discovery was on.
                if (running) lastError = "BLUETOOTH_ACCEPT_FAILED"
                return
            }
            if (!running) {
                runCatching { remote.close() }
                return
            }
            Thread({ pipe(remote) }, "grom-mesh-bt-link").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun pipe(remote: android.bluetooth.BluetoothSocket) {
        val endpoint = target()
        val host = endpoint?.first
        val port = endpoint?.second ?: 0
        if (host.isNullOrEmpty() || port !in 1..65535) {
            runCatching { remote.close() }
            return
        }
        val local = runCatching { Socket() }.getOrNull()
        val connected = local != null && runCatching {
            local.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }.getOrDefault(false)
        if (!connected) {
            runCatching { local?.close() }
            runCatching { remote.close() }
            return
        }
        val localSocket = local ?: return
        val up = Thread(
            { copy(localSocket.inputStream, remote.outputStream) },
            "grom-mesh-bt-up"
        ).apply {
            isDaemon = true
            start()
        }
        copy(remote.inputStream, localSocket.outputStream)
        runCatching { up.join(1_000) }
        runCatching { remote.close() }
        runCatching { localSocket.close() }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // The link closed; both sides are closed by the caller.
        }
    }

    override fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    override fun scanNow() = Unit

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 4_000
    }
}

/** The bundle the runtime installs, built from whatever this phone supports. */
data class AndroidMeshRadios(
    val radios: List<MeshRadio>,
    val classic: app.grom.server.mesh.net.DuplexLink
) {

    companion object {

        /**
         * Builds the adapters. A phone without a radio simply gets fewer
         * entries; [MeshService] reports every one that is unavailable.
         */
        fun create(
            context: Context,
            nodeId: () -> String?,
            port: () -> Int,
            dataPlaneUp: () -> Boolean,
            dataPlaneTarget: () -> Pair<String, Int>?
        ): AndroidMeshRadios = AndroidMeshRadios(
            radios = listOf(
                BleMeshRadio(context, nodeId, port, dataPlaneUp),
                WifiDirectMeshRadio(context, nodeId, port),
                AndroidRfcommServer(context, dataPlaneTarget)
            ),
            classic = AndroidRfcommLink(context)
        )
    }
}
