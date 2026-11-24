package tools.wp2.sideloader

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tools.wp2.sideloader.wire.Link
import tools.wp2.sideloader.wire.Uuid
import tools.wp2.sideloader.wire.sha256Hex

class BleError(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { OFF, NO_WATCH, MISSING_GATT, DROPPED, OTHER }

    companion object {
        val off get() = BleError("Bluetooth is off", Kind.OFF)
        val noWatch get() = BleError("CMF Watch not found", Kind.NO_WATCH)
        val missing get() = BleError("CMF Watch GATT services not found", Kind.MISSING_GATT)
        val dropped get() = BleError("Connection to watch lost", Kind.DROPPED)
    }
}

data class DiscoveredWatch(
    val name: String,
    val address: String,
    val source: Source,
    val rssi: Int? = null,
) {
    enum class Source { CONNECTED, BONDED, SCANNED }
    val isCmfWatch: Boolean get() = name.startsWith("CMF Watch", ignoreCase = true)
}

@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : Link {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private val chars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val ops = Mutex()

    private class Step(val op: Op, val done: CompletableDeferred<Unit> = CompletableDeferred())
    private enum class Op { CONNECT, MTU, SERVICES, DESCRIPTOR, WRITE }

    @Volatile private var step: Step? = null
    @Volatile private var dropped = false
    @Volatile private var mtu = 23

    var watchId: String? = null
        private set

    var watchName: String? = null
        private set

    var watchAddress: String? = null
        private set

    suspend fun connect(targetAddress: String? = null, scan: Boolean = true) {
        val adapter = manager?.adapter ?: throw BleError.off
        if (!adapter.isEnabled) throw BleError.off

        fun isWatch(d: BluetoothDevice) = d.name?.startsWith(WATCH_NAME, ignoreCase = true) == true

        val device = if (targetAddress != null) {
            manager.getConnectedDevices(BluetoothProfile.GATT).firstOrNull { it.address == targetAddress }
                ?: adapter.bondedDevices.firstOrNull { it.address == targetAddress }
                ?: runCatching { adapter.getRemoteDevice(targetAddress) }.getOrNull()
                ?: throw BleError.noWatch
        } else {
            manager.getConnectedDevices(BluetoothProfile.GATT).firstOrNull(::isWatch)
                ?: adapter.bondedDevices.firstOrNull {
                    isWatch(it) && it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC
                }
                ?: (if (scan) scanForWatch() else null)
                ?: throw BleError.noWatch
        }

        watchId = sha256Hex(device.address).take(16)
        watchName = device.name ?: "CMF Watch (${device.address.takeLast(5)})"
        watchAddress = device.address
        log("Connecting to $watchName [${device.address}]...")

        dropped = false
        gattStep(Op.CONNECT) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        val g = gatt ?: throw BleError.noWatch

        gattStep(Op.MTU) { g.requestMtu(247) }
        log("MTU = $mtu")

        gattStep(Op.SERVICES) { g.discoverServices() }
        for (s in g.services) {
            for (c in s.characteristics) {
                val name = c.uuid.toString().uppercase()
                if (name in REQUIRED) chars[name] = c
            }
        }
        if (REQUIRED.any { it !in chars }) {
            log("Required GATT characteristics missing: found ${chars.keys}")
            throw BleError.missing
        }
        for (name in NOTIFIES) {
            val c = chars[name] ?: continue
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: continue
            gattStep(Op.DESCRIPTOR) { writeDescriptor(g, d) }
        }
        log("GATT ready, notifications enabled")
    }

    fun disconnect() {
        gatt?.run {
            runCatching { disconnect() }
            runCatching { close() }
        }
        gatt = null
        step?.done?.completeExceptionally(BleError.dropped)
    }

    override fun onNotify(handler: (String, ByteArray) -> Unit) {
        handlers += handler
    }

    override suspend fun write(uuid: String, data: ByteArray) = ops.withLock {
        val g = gatt?.takeUnless { dropped } ?: throw BleError.dropped
        val c = chars[uuid] ?: throw BleError.missing
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (data.size > mtu - 3 || !noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        stepLocked(Op.WRITE) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, on) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = on
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private suspend fun gattStep(op: Op, start: () -> Any?) = ops.withLock { stepLocked(op, start) }

    private suspend fun stepLocked(op: Op, start: () -> Any?) {
        val s = Step(op)
        step = s
        if (start() == false) throw BleError("Android rejected BLE operation ($op)")
        withTimeout(STEP_MS) { s.done.await() }
    }

    private suspend fun scanForWatch(): BluetoothDevice? {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return null
        log("BLE scanning for CMF Watch (up to 10s)...")
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: r.device.name
                if (n?.startsWith(WATCH_NAME, ignoreCase = true) == true) {
                    found.complete(r.device)
                }
            }
        }
        scanner.startScan(cb)
        return try {
            withTimeoutOrNull(10_000) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    private fun finish(op: Op, status: Int) {
        val s = step?.takeIf { it.op == op } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            s.done.complete(Unit)
        } else {
            s.done.completeExceptionally(BleError("BLE GATT error (status $status, op $op)"))
        }
    }

    private fun dispatch(c: BluetoothGattCharacteristic, value: ByteArray) {
        val name = c.uuid.toString().uppercase()
        handlers.forEach { it(name, value) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> finish(Op.CONNECT, BluetoothGatt.GATT_SUCCESS)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    dropped = true
                    step?.done?.completeExceptionally(BleError.dropped)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            finish(Op.MTU, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(Op.SERVICES, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(Op.DESCRIPTOR, status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(Op.WRITE, status)

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = dispatch(c, value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) dispatch(c, c.value.copyOf())
        }
    }

    companion object {
        private const val WATCH_NAME = "CMF Watch"
        private const val STEP_MS = 15_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val REQUIRED = listOf(
            Uuid.CMD_WRITE,
            Uuid.CMD_NOTIFY,
            Uuid.SHELL_WRITE,
            Uuid.SHELL_NOTIFY,
        )
        private val NOTIFIES = listOf(Uuid.CMD_NOTIFY, Uuid.SHELL_NOTIFY)

        @SuppressLint("MissingPermission")
        suspend fun scanNearbyDevices(
            context: Context,
            durationMs: Long = 5_000L,
            onUpdate: (List<DiscoveredWatch>) -> Unit,
        ): List<DiscoveredWatch> {
            val manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter ?: throw BleError.off
            if (!adapter.isEnabled) throw BleError.off

            val map = linkedMapOf<String, DiscoveredWatch>()

            fun publish() {
                val sorted = map.values.sortedWith(
                    compareByDescending<DiscoveredWatch> { it.isCmfWatch }
                        .thenBy { it.source.ordinal }
                        .thenBy { it.name.lowercase() }
                )
                onUpdate(sorted)
            }

            manager.getConnectedDevices(BluetoothProfile.GATT).forEach { d ->
                val name = d.name?.takeIf { it.isNotBlank() } ?: return@forEach
                map[d.address] = DiscoveredWatch(name, d.address, DiscoveredWatch.Source.CONNECTED)
            }
            adapter.bondedDevices.forEach { d ->
                if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) return@forEach
                val name = d.name?.takeIf { it.isNotBlank() } ?: return@forEach
                if (d.address !in map) {
                    map[d.address] = DiscoveredWatch(name, d.address, DiscoveredWatch.Source.BONDED)
                }
            }
            publish()

            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val cb = object : ScanCallback() {
                    override fun onScanResult(type: Int, r: ScanResult) {
                        val name = (r.scanRecord?.deviceName ?: r.device.name)?.takeIf { it.isNotBlank() }
                            ?: return
                        val prev = map[r.device.address]
                        if (prev == null) {
                            map[r.device.address] = DiscoveredWatch(
                                name = name,
                                address = r.device.address,
                                source = DiscoveredWatch.Source.SCANNED,
                                rssi = r.rssi,
                            )
                            publish()
                        }
                    }
                }
                scanner.startScan(cb)
                try {
                    delay(durationMs)
                } finally {
                    runCatching { scanner.stopScan(cb) }
                }
            }
            return map.values.sortedWith(
                compareByDescending<DiscoveredWatch> { it.isCmfWatch }
                    .thenBy { it.source.ordinal }
                    .thenBy { it.name.lowercase() }
            )
        }
    }
}
