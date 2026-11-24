package tools.wp2.sideloader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tools.wp2.sideloader.wire.InstallError
import tools.wp2.sideloader.wire.KSession
import tools.wp2.sideloader.wire.LOADER_SLOT
import tools.wp2.sideloader.wire.LoaderState
import tools.wp2.sideloader.wire.PackageInspection
import tools.wp2.sideloader.wire.WireError
import tools.wp2.sideloader.wire.formatVersion
import tools.wp2.sideloader.wire.inspectPackage
import tools.wp2.sideloader.wire.install
import tools.wp2.sideloader.wire.readLoader
import tools.wp2.sideloader.wire.remove

data class SelectedPackage(
    val fileName: String,
    val bytes: ByteArray,
    val inspection: PackageInspection,
)

sealed interface WatchStatus {
    data object Idle : WatchStatus
    data object Connecting : WatchStatus
    data class Ready(
        val watchName: String,
        val watchAddress: String,
        val loaderState: LoaderState,
    ) : WatchStatus
    data class StockFirmware(val watchName: String) : WatchStatus
    data class Error(val message: String) : WatchStatus
}

data class TransferProgress(
    val fraction: Float,
    val sentBytes: Int,
    val totalBytes: Int,
    val stage: String,
)

class SideloaderViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("wp2_sideloader", Context.MODE_PRIVATE)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    var watchStatus by mutableStateOf<WatchStatus>(WatchStatus.Idle)
        private set

    var selectedAddress by mutableStateOf(prefs.getString("watch_address", null))
        private set

    var selectedWatchLabel by mutableStateOf(prefs.getString("watch_name", null))
        private set

    var selectedPackage by mutableStateOf<SelectedPackage?>(null)
        private set

    var fileError by mutableStateOf<String?>(null)
        private set

    /** null = auto (update existing slot by name, else first free slot). */
    var targetSlot by mutableStateOf<Int?>(null)

    var transfer by mutableStateOf<TransferProgress?>(null)
        private set

    var removingSlot by mutableStateOf<Int?>(null)
        private set

    var bannerSuccess by mutableStateOf<String?>(null)
        private set

    var bannerError by mutableStateOf<String?>(null)
        private set

    var scanningDevices by mutableStateOf(false)
        private set

    var discoveredDevices by mutableStateOf<List<DiscoveredWatch>>(emptyList())
        private set

    var scanError by mutableStateOf<String?>(null)
        private set

    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
        private set

    var checkingUpdate by mutableStateOf(false)
        private set

    val logs = mutableStateListOf<String>()

    private var scanJob: Job? = null

    val isBusy: Boolean
        get() = watchStatus is WatchStatus.Connecting || transfer != null || removingSlot != null

    init {
        // Silently check GitHub Releases for a newer APK on startup
        checkForUpdates(manual = false)
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun localizeError(e: Throwable, @StringRes fallbackRes: Int): String = when (e) {
        is BleError -> when (e.kind) {
            BleError.Kind.OFF -> str(R.string.err_ble_off)
            BleError.Kind.NO_WATCH -> str(R.string.err_ble_no_watch)
            BleError.Kind.MISSING_GATT -> str(R.string.err_ble_missing_gatt)
            BleError.Kind.DROPPED -> str(R.string.err_ble_dropped)
            BleError.Kind.OTHER -> e.message ?: str(fallbackRes)
        }
        is InstallError.Busy -> str(R.string.err_install_busy)
        is InstallError.NoFreeSlot -> str(R.string.err_install_no_slot, e.slots)
        is InstallError.NotAPackage -> str(R.string.err_install_bad_pkg)
        is InstallError.Refused -> str(R.string.err_install_refused, "%02X".format(e.status))
        is WireError.NoApp -> str(R.string.err_wire_no_app)
        is WireError.Refused -> str(R.string.err_wire_refused, "%02X".format(e.status))
        else -> e.message ?: str(fallbackRes)
    }

    fun log(msg: String) {
        val ts = synchronized(timeFmt) { timeFmt.format(Date()) }
        val line = "$ts  $msg"
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (logs.size >= 200) logs.removeAt(0)
            logs.add(line)
        }
    }

    fun clearBanners() {
        bannerSuccess = null
        bannerError = null
    }

    fun dismissUpdate() {
        availableUpdate = null
    }

    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) clearBanners()
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    UpdateChecker.checkLatest(BuildConfig.VERSION_NAME)
                }
                if (found != null) {
                    availableUpdate = found
                    log("Update available: v${found.latestVersion} (${found.downloadUrl})")
                } else if (manual) {
                    bannerSuccess = str(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
                    log("App is up to date (v${BuildConfig.VERSION_NAME})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Update check failed: ${e.message}")
                if (manual) {
                    bannerError = str(R.string.update_check_failed, e.message ?: "network error")
                }
            } finally {
                checkingUpdate = false
            }
        }
    }

    fun selectManualWatch(device: DiscoveredWatch) {
        selectedAddress = device.address
        selectedWatchLabel = device.name
        prefs.edit()
            .putString("watch_address", device.address)
            .putString("watch_name", device.name)
            .apply()
        log("Selected watch: ${device.name} [${device.address}]")
        connectAndRead(preferredAddress = device.address)
    }

    fun clearSavedWatch() {
        selectedAddress = null
        selectedWatchLabel = null
        prefs.edit().remove("watch_address").remove("watch_name").apply()
    }

    fun startDeviceScan() {
        scanJob?.cancel()
        scanError = null
        scanningDevices = true
        discoveredDevices = emptyList()
        scanJob = viewModelScope.launch {
            try {
                BleLink.scanNearbyDevices(getApplication(), durationMs = 6_000L) { list ->
                    discoveredDevices = list
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanError = localizeError(e, R.string.err_scan_generic)
                log("Scan error: ${e.message}")
            } finally {
                scanningDevices = false
            }
        }
    }

    fun stopDeviceScan() {
        scanJob?.cancel()
        scanJob = null
        scanningDevices = false
    }

    fun connectAndRead(preferredAddress: String? = selectedAddress) {
        if (isBusy) return
        clearBanners()
        watchStatus = WatchStatus.Connecting
        viewModelScope.launch {
            val link = BleLink(getApplication(), ::log)
            try {
                val state = withContext(Dispatchers.IO) {
                    withTimeout(35_000) {
                        link.connect(targetAddress = preferredAddress, scan = true)
                        val keyName = "k-${link.watchId}"
                        val cached = prefs.getString(keyName, null)
                        val (session, secret) = KSession.open(link, cached, register = false)
                        prefs.edit().putString(keyName, secret).apply()

                        var result = readLoader(session)
                        if (result == null && cached != null) {
                            log("Cached key rejected — requesting fresh AT GETSECRET...")
                            val (fresh, newSecret) = KSession.open(link, secretHex = null, register = false)
                            if (newSecret != cached) {
                                prefs.edit().putString(keyName, newSecret).apply()
                                result = readLoader(fresh)
                            }
                        }
                        result
                    }
                }
                val name = link.watchName ?: "CMF Watch Pro 2"
                val addr = link.watchAddress ?: preferredAddress ?: ""
                if (addr.isNotEmpty()) {
                    selectedAddress = addr
                    selectedWatchLabel = name
                    prefs.edit()
                        .putString("watch_address", addr)
                        .putString("watch_name", name)
                        .apply()
                }
                if (state == null) {
                    log("Stub did not respond (stock firmware or unsupported stub)")
                    watchStatus = WatchStatus.StockFirmware(name)
                } else {
                    log(
                        "Ready: Stub v${state.stub.version}, Loader=${state.loader?.crc?.let { "%08x".format(it) } ?: "none"}, " +
                            "apps=${state.apps.size}/${state.totalSlots}"
                    )
                    watchStatus = WatchStatus.Ready(name, addr, state)
                    targetSlot?.let { if (it >= state.totalSlots) targetSlot = null }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = localizeError(e, R.string.err_connect_generic)
                log("Connect error: $msg")
                watchStatus = WatchStatus.Error(msg)
            } finally {
                link.disconnect()
            }
        }
    }

    fun loadFile(uri: Uri) {
        clearBanners()
        fileError = null
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val (fileName, bytes) = withContext(Dispatchers.IO) {
                    val name = queryFileName(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "app.wp2app"
                    val stream = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(str(R.string.err_file_open))
                    stream.use { input ->
                        val data = input.readBytes()
                        if (data.size > MAX_FILE_BYTES) {
                            throw IllegalArgumentException(str(R.string.err_file_too_large, data.size / 1024))
                        }
                        name to data
                    }
                }
                val inspection = inspectPackage(bytes)
                if (inspection == null) {
                    selectedPackage = null
                    fileError = str(R.string.err_not_wp2a, fileName)
                    log("Rejected $fileName (${bytes.size} B): not WP2A")
                    return@launch
                }
                selectedPackage = SelectedPackage(fileName, bytes, inspection)
                targetSlot = null
                log(
                    "Loaded package: $fileName -> ${inspection.header.name} v${formatVersion(inspection.header.version)} " +
                        "(${bytes.size} B, ABI ${inspection.abiText}, CRC=${if (inspection.crcValid) "OK" else "BAD"})"
                )
            } catch (e: Exception) {
                selectedPackage = null
                fileError = e.message ?: str(R.string.err_file_read)
                log("File read error: ${e.message}")
            }
        }
    }

    fun clearSelectedPackage() {
        selectedPackage = null
        fileError = null
        targetSlot = null
    }

    fun installSelected() {
        val pkg = selectedPackage ?: return
        if (isBusy) return
        if (!pkg.inspection.crcValid) {
            bannerError = str(R.string.err_crc_corrupt)
            return
        }
        clearBanners()
        val total = pkg.bytes.size
        transfer = TransferProgress(0f, 0, total, str(R.string.stage_connecting))
        val manualSlot = if (pkg.inspection.header.isLoader) null else targetSlot

        viewModelScope.launch {
            val link = BleLink(getApplication(), ::log)
            try {
                val (installedSlot, updatedState, watchName, watchAddr) = withContext(Dispatchers.IO) {
                    withTimeout(120_000) {
                        link.connect(targetAddress = selectedAddress, scan = true)
                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(0f, 0, total, str(R.string.stage_auth))
                        }
                        val (session, secret) = KSession.open(link, secretHex = null, register = false)
                        prefs.edit().putString("k-${link.watchId}", secret).apply()

                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(
                                0f,
                                0,
                                total,
                                str(R.string.stage_sending_name, pkg.inspection.header.name),
                            )
                        }
                        log("Installing ${pkg.inspection.header.name} (${pkg.bytes.size} B)...")
                        val sendingStage = str(R.string.stage_sending)
                        val slot = install(session, pkg.bytes, targetSlot = manualSlot) { p ->
                            val sent = (p * total).toInt().coerceIn(0, total)
                            viewModelScope.launch(Dispatchers.Main) {
                                transfer = TransferProgress(
                                    fraction = p.toFloat().coerceIn(0f, 1f),
                                    sentBytes = sent,
                                    totalBytes = total,
                                    stage = sendingStage,
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(1f, total, total, str(R.string.stage_refreshing))
                        }
                        val freshState = readLoader(session)
                        val name = link.watchName ?: "CMF Watch Pro 2"
                        val addr = link.watchAddress ?: selectedAddress ?: ""
                        Quad(slot, freshState, name, addr)
                    }
                }
                if (watchAddr.isNotEmpty()) {
                    selectedAddress = watchAddr
                    selectedWatchLabel = watchName
                    prefs.edit()
                        .putString("watch_address", watchAddr)
                        .putString("watch_name", watchName)
                        .apply()
                }
                if (updatedState != null) {
                    watchStatus = WatchStatus.Ready(watchName, watchAddr, updatedState)
                }
                val where = if (installedSlot == LOADER_SLOT) {
                    str(R.string.slot_loader_name)
                } else {
                    str(R.string.slot_number_name, installedSlot)
                }
                val okMsg = str(
                    R.string.msg_installed,
                    pkg.inspection.header.name,
                    formatVersion(pkg.inspection.header.version),
                    where,
                )
                log(okMsg)
                bannerSuccess = okMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallError) {
                val msg = localizeError(e, R.string.err_install_generic)
                log("InstallError: $msg")
                bannerError = msg
            } catch (e: Exception) {
                val msg = localizeError(e, R.string.err_ble_transfer)
                log("Install error: $msg")
                bannerError = msg
            } finally {
                transfer = null
                link.disconnect()
            }
        }
    }

    fun removeApp(slot: Int, name: String) {
        if (isBusy) return
        clearBanners()
        removingSlot = slot
        viewModelScope.launch {
            val link = BleLink(getApplication(), ::log)
            try {
                val (updatedState, watchName, watchAddr) = withContext(Dispatchers.IO) {
                    withTimeout(45_000) {
                        link.connect(targetAddress = selectedAddress, scan = true)
                        val (session, secret) = KSession.open(link, secretHex = null, register = false)
                        prefs.edit().putString("k-${link.watchId}", secret).apply()
                        log("Removing slot #$slot ($name)...")
                        remove(session, slot)
                        val fresh = readLoader(session)
                        Triple(fresh, link.watchName ?: "CMF Watch Pro 2", link.watchAddress ?: selectedAddress ?: "")
                    }
                }
                if (updatedState != null) {
                    watchStatus = WatchStatus.Ready(watchName, watchAddr, updatedState)
                }
                val okMsg = str(R.string.msg_removed, name, slot)
                log(okMsg)
                bannerSuccess = okMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = localizeError(e, R.string.err_remove_generic)
                log("Remove error: $msg")
                bannerError = msg
            } finally {
                removingSlot = null
                link.disconnect()
            }
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        private const val MAX_FILE_BYTES = 512 * 1024
    }
}
