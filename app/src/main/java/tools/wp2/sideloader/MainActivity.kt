package tools.wp2.sideloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import tools.wp2.sideloader.ui.SuccessGreen
import tools.wp2.sideloader.ui.WP2Theme
import tools.wp2.sideloader.ui.WarnAmber
import tools.wp2.sideloader.wire.PackageHeader
import tools.wp2.sideloader.wire.formatVersion

class MainActivity : ComponentActivity() {
    private val vm: SideloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            WP2Theme {
                SideloaderScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        if (uri != null) {
            vm.loadFile(uri)
        }
    }
}

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
)

private fun hasBlePermissions(context: Context): Boolean =
    BLE_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideloaderScreen(vm: SideloaderViewModel) {
    val context = LocalContext.current
    val view = LocalView.current

    // Keep screen on during BLE transfer
    val busy = vm.isBusy
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var showDevicePicker by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permDeniedMsg = stringResource(R.string.perm_ble_required)
    val logCopiedMsg = stringResource(R.string.log_copied)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            pendingBleAction?.invoke()
            pendingBleAction = null
        } else {
            pendingBleAction = null
            Toast.makeText(context, permDeniedMsg, Toast.LENGTH_LONG).show()
        }
    }

    fun runWithBlePermission(action: () -> Unit) {
        if (hasBlePermissions(context)) {
            action()
        } else {
            pendingBleAction = action
            permissionLauncher.launch(BLE_PERMISSIONS)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.loadFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.checkForUpdates(manual = true) },
                        enabled = !vm.checkingUpdate,
                    ) {
                        if (vm.checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = stringResource(R.string.cd_check_updates),
                                tint = if (vm.availableUpdate != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showLogs = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = stringResource(R.string.cd_logs),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Update Available Banner
            item {
                AnimatedVisibility(visible = vm.availableUpdate != null) {
                    vm.availableUpdate?.let { upd ->
                        UpdateBannerCard(
                            update = upd,
                            onDownload = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(upd.downloadUrl))
                                runCatching { context.startActivity(intent) }
                            },
                            onDismiss = { vm.dismissUpdate() },
                        )
                    }
                }
            }

            // Banners (success / error)
            item {
                AnimatedVisibility(visible = vm.bannerSuccess != null || vm.bannerError != null) {
                    val isError = vm.bannerError != null
                    val text = vm.bannerError ?: vm.bannerSuccess ?: ""
                    val containerColor = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        SuccessGreen.copy(alpha = 0.16f)
                    }
                    val contentColor = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        SuccessGreen
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = containerColor,
                        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) contentColor else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { vm.clearBanners() },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cd_close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 1. Watch Connection Card
            item {
                WatchCard(
                    status = vm.watchStatus,
                    savedWatchName = vm.selectedWatchLabel,
                    isBusy = vm.isBusy,
                    onConnect = { runWithBlePermission { vm.connectAndRead() } },
                    onPickWatch = {
                        runWithBlePermission {
                            showDevicePicker = true
                            vm.startDeviceScan()
                        }
                    },
                )
            }

            // 2. File Picker + Install Card
            item {
                InstallPackageCard(
                    pkg = vm.selectedPackage,
                    fileError = vm.fileError,
                    watchStatus = vm.watchStatus,
                    targetSlot = vm.targetSlot,
                    transfer = vm.transfer,
                    isBusy = vm.isBusy,
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onClearFile = { vm.clearSelectedPackage() },
                    onSelectSlot = { vm.targetSlot = it },
                    onInstall = { runWithBlePermission { vm.installSelected() } },
                )
            }

            // 3. Installed Apps Section
            val ready = vm.watchStatus as? WatchStatus.Ready
            if (ready != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.section_installed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = stringResource(
                                R.string.slots_count,
                                ready.loaderState.apps.size,
                                ready.loaderState.totalSlots,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (ready.loaderState.apps.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_apps_installed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    val entries = ready.loaderState.apps.entries.toList()
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                entries.forEachIndexed { idx, (slot, header) ->
                                    InstalledAppRow(
                                        slot = slot,
                                        header = header,
                                        isRemoving = vm.removingSlot == slot,
                                        enabled = !vm.isBusy,
                                        onDelete = { pendingDelete = slot to header.name },
                                    )
                                    if (idx < entries.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Confirm delete dialog
    pendingDelete?.let { (slot, name) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, name, slot)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        runWithBlePermission { vm.removeApp(slot, name) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    // Device picker dialog
    if (showDevicePicker) {
        DevicePickerDialog(
            scanning = vm.scanningDevices,
            devices = vm.discoveredDevices,
            error = vm.scanError,
            onRescan = { vm.startDeviceScan() },
            onSelect = { dev ->
                vm.stopDeviceScan()
                showDevicePicker = false
                vm.selectManualWatch(dev)
            },
            onDismiss = {
                vm.stopDeviceScan()
                showDevicePicker = false
            },
        )
    }

    // Diagnostic logs dialog
    if (showLogs) {
        LogDialog(
            logs = vm.logs,
            onCopy = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("WP2 Sideloader Log", vm.logs.joinToString("\n")))
                Toast.makeText(context, logCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onClear = { vm.logs.clear() },
            onDismiss = { showLogs = false },
        )
    }
}

@Composable
private fun UpdateBannerCard(
    update: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.update_available_title, update.latestVersion),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.update_available_sub, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_download_btn))
            }
        }
    }
}

@Composable
private fun WatchCard(
    status: WatchStatus,
    savedWatchName: String?,
    isBusy: Boolean,
    onConnect: () -> Unit,
    onPickWatch: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconBg = when (status) {
                    is WatchStatus.Ready -> SuccessGreen.copy(alpha = 0.16f)
                    is WatchStatus.Error -> MaterialTheme.colorScheme.errorContainer
                    is WatchStatus.StockFirmware -> WarnAmber.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                val iconTint = when (status) {
                    is WatchStatus.Ready -> SuccessGreen
                    is WatchStatus.Error -> MaterialTheme.colorScheme.error
                    is WatchStatus.StockFirmware -> WarnAmber
                    else -> MaterialTheme.colorScheme.primary
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (status is WatchStatus.Connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = iconTint,
                        )
                    } else {
                        Icon(
                            imageVector = when (status) {
                                is WatchStatus.Ready -> Icons.Rounded.BluetoothConnected
                                else -> Icons.Rounded.Watch
                            },
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (status) {
                            is WatchStatus.Idle -> savedWatchName ?: "CMF Watch Pro 2"
                            is WatchStatus.Connecting -> stringResource(R.string.watch_connecting)
                            is WatchStatus.Ready -> status.watchName
                            is WatchStatus.StockFirmware -> status.watchName
                            is WatchStatus.Error -> savedWatchName ?: stringResource(R.string.watch_connect_error)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (status) {
                            is WatchStatus.Idle -> stringResource(R.string.watch_idle_hint)
                            is WatchStatus.Connecting -> stringResource(R.string.watch_connecting_hint)
                            is WatchStatus.Ready -> {
                                val st = status.loaderState
                                val loaderText = if (st.loader != null) {
                                    stringResource(R.string.watch_loader_ok)
                                } else {
                                    stringResource(R.string.watch_loader_missing)
                                }
                                stringResource(
                                    R.string.watch_ready_summary,
                                    st.stub.version,
                                    loaderText,
                                    st.freeSlots,
                                    st.totalSlots,
                                )
                            }
                            is WatchStatus.StockFirmware -> stringResource(R.string.watch_stock_firmware)
                            is WatchStatus.Error -> status.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            is WatchStatus.Error -> MaterialTheme.colorScheme.error
                            is WatchStatus.StockFirmware -> WarnAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (status is WatchStatus.Ready) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoPill("STUB v${status.loaderState.stub.version}")
                    val loaderCrc = status.loaderState.loader?.crc?.let { "%08x".format(it) }
                        ?: stringResource(R.string.pill_loader_none)
                    InfoPill("LOADER $loaderCrc")
                    status.loaderState.stub.abiMinor?.let { InfoPill("ABI 1.$it") }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onConnect,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = if (status is WatchStatus.Ready) Icons.Rounded.Refresh else Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (status is WatchStatus.Ready) R.string.btn_refresh else R.string.btn_connect
                        )
                    )
                }

                OutlinedButton(
                    onClick = onPickWatch,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_pick))
                }
            }
        }
    }
}

@Composable
private fun InstallPackageCard(
    pkg: SelectedPackage?,
    fileError: String?,
    watchStatus: WatchStatus,
    targetSlot: Int?,
    transfer: TransferProgress?,
    isBusy: Boolean,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onSelectSlot: (Int?) -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.section_install_pkg),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )

            Spacer(Modifier.height(12.dp))

            if (pkg == null) {
                Surface(
                    onClick = onPickFile,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.pick_file_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.pick_file_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val ins = pkg.inspection
                val hdr = ins.header

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (ins.crcValid) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hdr.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = pkg.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!isBusy) {
                                TextButton(onClick = onPickFile) {
                                    Text(stringResource(R.string.btn_change_file))
                                }
                                IconButton(
                                    onClick = onClearFile,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.cd_clear_file),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            InfoPill(
                                stringResource(
                                    if (hdr.isLoader) R.string.pill_kind_loader else R.string.pill_kind_app
                                )
                            )
                            InfoPill("v${formatVersion(hdr.version)}")
                            InfoPill(stringResource(R.string.pill_size_kb, ins.totalBytes / 1024f))
                            InfoPill("ABI ${ins.abiText}")
                            InfoPill(
                                text = stringResource(
                                    if (ins.crcValid) R.string.pill_crc_ok else R.string.pill_crc_bad
                                ),
                                tint = if (ins.crcValid) SuccessGreen else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // Slot selector (only for apps, not loader)
                if (hdr.isApp && watchStatus is WatchStatus.Ready) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.slot_selector_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    val totalSlots = watchStatus.loaderState.totalSlots
                    val existingSlot = watchStatus.loaderState.apps.entries
                        .firstOrNull { it.value.name == hdr.name }?.key
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = targetSlot == null,
                            onClick = { onSelectSlot(null) },
                            enabled = !isBusy,
                            label = {
                                Text(
                                    if (existingSlot != null) {
                                        stringResource(R.string.slot_auto_update, existingSlot)
                                    } else {
                                        stringResource(R.string.slot_auto)
                                    }
                                )
                            },
                        )
                        for (slot in 0 until totalSlots) {
                            val occupant = watchStatus.loaderState.apps[slot]
                            FilterChip(
                                selected = targetSlot == slot,
                                onClick = { onSelectSlot(slot) },
                                enabled = !isBusy,
                                label = {
                                    Text(
                                        if (occupant != null) "#$slot (${occupant.name})"
                                        else "#$slot"
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (fileError != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = fileError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Progress bar during BLE transfer
            if (transfer != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = transfer.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pct = (transfer.fraction * 100).toInt()
                    val kbText = stringResource(
                        R.string.transfer_kb,
                        transfer.sentBytes / 1024f,
                        transfer.totalBytes / 1024f,
                    )
                    Text(
                        text = "$pct% ($kbText)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (transfer.fraction <= 0f) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { transfer.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onInstall,
                enabled = pkg != null && pkg.inspection.crcValid && !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.InstallMobile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        when {
                            transfer != null -> R.string.btn_installing
                            pkg == null -> R.string.btn_pick_first
                            pkg.inspection.header.isLoader -> R.string.btn_install_loader
                            else -> R.string.btn_install_app
                        }
                    ),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    slot: Int,
    header: PackageHeader,
    isRemoving: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = "#$slot",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = header.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "v${formatVersion(header.version)}  •  CRC %08x".format(header.crc),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isRemoving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(
                onClick = onDelete,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, tint: Color? = null) {
    val bg = tint?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

@Composable
private fun DevicePickerDialog(
    scanning: Boolean,
    devices: List<DiscoveredWatch>,
    error: String?,
    onRescan: () -> Unit,
    onSelect: (DiscoveredWatch) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.picker_title), modifier = Modifier.weight(1f))
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (scanning) R.string.picker_scanning else R.string.picker_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(devices, key = { it.address }) { dev ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (dev.isCmfWatch) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(dev) },
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = dev.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (dev.isCmfWatch) FontWeight.Bold else FontWeight.Medium,
                                    )
                                    val src = when (dev.source) {
                                        DiscoveredWatch.Source.CONNECTED -> stringResource(R.string.picker_src_connected)
                                        DiscoveredWatch.Source.BONDED -> stringResource(R.string.picker_src_bonded)
                                        DiscoveredWatch.Source.SCANNED -> {
                                            val base = stringResource(R.string.picker_src_nearby)
                                            "$base${dev.rssi?.let { " ($it dBm)" } ?: ""}"
                                        }
                                    }
                                    Text(
                                        text = "${dev.address} • $src",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !scanning) {
                Text(stringResource(R.string.btn_rescan))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
    )
}

@Composable
private fun LogDialog(
    logs: List<String>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logs_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 380.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (logs.isEmpty()) stringResource(R.string.logs_empty) else logs.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Row {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.cd_copy))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.btn_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        },
    )
}
