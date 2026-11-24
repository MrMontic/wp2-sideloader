package tools.wp2.sideloader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

    val logs = mutableStateListOf<String>()

    private var scanJob: Job? = null

    val isBusy: Boolean
        get() = watchStatus is WatchStatus.Connecting || transfer != null || removingSlot != null

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

    fun selectManualWatch(device: DiscoveredWatch) {
        selectedAddress = device.address
        selectedWatchLabel = device.name
        prefs.edit()
            .putString("watch_address", device.address)
            .putString("watch_name", device.name)
            .apply()
        log("Выбраны часы: ${device.name} [${device.address}]")
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
                scanError = e.message ?: "Ошибка сканирования Bluetooth"
                log("Ошибка сканирования: ${e.message}")
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
                            log("Кэшированный ключ не подошёл — запрашиваю свежий AT GETSECRET...")
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
                    log("Stub не ответил (стоковая прошивка или старый stub)")
                    watchStatus = WatchStatus.StockFirmware(name)
                } else {
                    log("Готово: Stub v${state.stub.version}, Loader=${state.loader?.crc?.let { "%08x".format(it) } ?: "нет"}, приложений=${state.apps.size}/${state.totalSlots}")
                    watchStatus = WatchStatus.Ready(name, addr, state)
                    // Reset manual slot if out of bounds for this stub
                    targetSlot?.let { if (it >= state.totalSlots) targetSlot = null }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Не удалось подключиться к часам"
                log("Ошибка подключения: $msg")
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
                        ?: throw IllegalStateException("Не удалось открыть файл")
                    stream.use { input ->
                        val data = input.readBytes()
                        if (data.size > MAX_FILE_BYTES) {
                            throw IllegalArgumentException(
                                "Файл слишком большой (${data.size / 1024} КБ). Пакет .wp2app не превышает 256 КБ."
                            )
                        }
                        name to data
                    }
                }
                val inspection = inspectPackage(bytes)
                if (inspection == null) {
                    selectedPackage = null
                    fileError = "Файл «$fileName» не является пакетом .wp2app (нет заголовка WP2A)"
                    log("Отклонён файл $fileName (${bytes.size} Б): не WP2A")
                    return@launch
                }
                selectedPackage = SelectedPackage(fileName, bytes, inspection)
                targetSlot = null
                log(
                    "Открыт пакет: $fileName → ${inspection.header.name} v${formatVersion(inspection.header.version)} " +
                        "(${bytes.size} Б, ABI ${inspection.abiText}, CRC=${if (inspection.crcValid) "OK" else "BAD"})"
                )
            } catch (e: Exception) {
                selectedPackage = null
                fileError = e.message ?: "Не удалось прочитать файл"
                log("Ошибка чтения файла: ${e.message}")
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
            bannerError = "Контрольная сумма (CRC-32) файла повреждена — установка отменена"
            return
        }
        clearBanners()
        val total = pkg.bytes.size
        transfer = TransferProgress(0f, 0, total, "Подключение к часам...")
        val manualSlot = if (pkg.inspection.header.isLoader) null else targetSlot

        viewModelScope.launch {
            val link = BleLink(getApplication(), ::log)
            try {
                val (installedSlot, updatedState, watchName, watchAddr) = withContext(Dispatchers.IO) {
                    withTimeout(120_000) {
                        link.connect(targetAddress = selectedAddress, scan = true)
                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(0f, 0, total, "Авторизация (K-канал)...")
                        }
                        val (session, secret) = KSession.open(link, secretHex = null, register = false)
                        prefs.edit().putString("k-${link.watchId}", secret).apply()

                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(0f, 0, total, "Передача ${pkg.inspection.header.name}...")
                        }
                        log("Начинаю установку ${pkg.inspection.header.name} (${pkg.bytes.size} Б)...")
                        val slot = install(session, pkg.bytes, targetSlot = manualSlot) { p ->
                            val sent = (p * total).toInt().coerceIn(0, total)
                            viewModelScope.launch(Dispatchers.Main) {
                                transfer = TransferProgress(
                                    fraction = p.toFloat().coerceIn(0f, 1f),
                                    sentBytes = sent,
                                    totalBytes = total,
                                    stage = "Передача на часы...",
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            transfer = TransferProgress(1f, total, total, "Обновление списка слотов...")
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
                val where = if (installedSlot == LOADER_SLOT) "слот App Loader" else "слот #$installedSlot"
                val okMsg = "Установлено «${pkg.inspection.header.name}» (v${formatVersion(pkg.inspection.header.version)}) в $where"
                log(okMsg)
                bannerSuccess = okMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallError) {
                val msg = e.message ?: "Ошибка установки"
                log("InstallError: $msg")
                bannerError = msg
            } catch (e: Exception) {
                val msg = e.message ?: "Сбой передачи по Bluetooth"
                log("Ошибка установки: $msg")
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
                        log("Удаление слота #$slot ($name)...")
                        remove(session, slot)
                        val fresh = readLoader(session)
                        Triple(fresh, link.watchName ?: "CMF Watch Pro 2", link.watchAddress ?: selectedAddress ?: "")
                    }
                }
                if (updatedState != null) {
                    watchStatus = WatchStatus.Ready(watchName, watchAddr, updatedState)
                }
                val okMsg = "Удалено «$name» из слота #$slot"
                log(okMsg)
                bannerSuccess = okMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Не удалось удалить приложение"
                log("Ошибка удаления: $msg")
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
