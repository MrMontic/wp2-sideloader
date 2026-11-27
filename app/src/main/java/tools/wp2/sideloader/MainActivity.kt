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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import tools.wp2.sideloader.ui.AppTheme
import tools.wp2.sideloader.ui.DotMatrixOverlay
import tools.wp2.sideloader.ui.LocalWp2Style
import tools.wp2.sideloader.ui.WP2Theme
import tools.wp2.sideloader.ui.WarnAmber
import tools.wp2.sideloader.ui.Wp2Backdrop
import tools.wp2.sideloader.ui.Wp2SlotSurface
import tools.wp2.sideloader.ui.Wp2ThemedCard
import tools.wp2.sideloader.wire.PackageHeader
import tools.wp2.sideloader.wire.formatVersion

class MainActivity : ComponentActivity() {
    private val vm: SideloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            WP2Theme(appTheme = vm.appTheme) {
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

@Composable
fun SideloaderScreen(vm: SideloaderViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val style = LocalWp2Style.current

    // Auto-connect silently on startup when enabled & Bluetooth permission is already granted
    LaunchedEffect(Unit) {
        vm.maybeAutoConnect(hasBlePermissions(context))
    }

    // Keep screen on during BLE transfer
    val busy = vm.isBusy
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

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

    Wp2Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ThemedTopBar(
                    showSettings = showSettings,
                    checkingUpdate = vm.checkingUpdate,
                    hasUpdate = vm.availableUpdate != null,
                    onToggleSettings = { showSettings = !showSettings },
                    onCheckUpdates = { vm.checkForUpdates(manual = true) },
                    onOpenLogs = { showLogs = true },
                )
            },
        ) { innerPadding ->
            AnimatedContent(
                targetState = showSettings,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen_switch",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { inSettings ->
                if (inSettings) {
                    SettingsContent(
                        vm = vm,
                        onOpenLogs = { showLogs = true },
                        onCheckUpdates = { vm.checkForUpdates(manual = true) },
                        onDownloadUpdate = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                    )
                } else {
                    MainContent(
                        vm = vm,
                        onConnect = { runWithBlePermission { vm.connectAndRead() } },
                        onPickWatch = {
                            runWithBlePermission {
                                showDevicePicker = true
                                vm.startDeviceScan()
                            }
                        },
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        onInstall = { runWithBlePermission { vm.installSelected() } },
                        onAskDelete = { slot, name -> pendingDelete = slot to name },
                        onDownloadUpdate = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                    )
                }
            }
        }
    }

    // Confirm delete dialog
    pendingDelete?.let { (slot, name) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.delete_title), color = style.textPrimary) },
            text = { Text(stringResource(R.string.delete_message, name, slot), color = style.textSecondary) },
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
                    Text(stringResource(R.string.btn_cancel), color = style.textSecondary)
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
private fun ThemedTopBar(
    showSettings: Boolean,
    checkingUpdate: Boolean,
    hasUpdate: Boolean,
    onToggleSettings: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val style = LocalWp2Style.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        DotMatrixOverlay(modifier = Modifier.matchParentSize())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(style.accentDot),
            )
            Spacer(Modifier.width(10.dp))
            val rawTitle = if (showSettings) {
                stringResource(R.string.settings_title)
            } else {
                stringResource(R.string.app_name)
            }
            Text(
                text = if (style.monoTitle) rawTitle.uppercase() else rawTitle,
                fontFamily = if (style.monoTitle) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = if (style.monoTitle) 0.5.sp else (-0.3).sp,
                color = style.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!showSettings) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = style.textMuted,
                )
            }
            Spacer(Modifier.weight(1f))

            if (!showSettings) {
                RoundHeaderButton(
                    icon = Icons.Rounded.SystemUpdate,
                    contentDescription = stringResource(R.string.cd_check_updates),
                    tint = if (hasUpdate) style.accentDot else style.textPrimary,
                    loading = checkingUpdate,
                    onClick = onCheckUpdates,
                )
                Spacer(Modifier.width(8.dp))
                RoundHeaderButton(
                    icon = Icons.Rounded.Terminal,
                    contentDescription = stringResource(R.string.cd_logs),
                    tint = style.textPrimary,
                    onClick = onOpenLogs,
                )
                Spacer(Modifier.width(8.dp))
            }

            RoundHeaderButton(
                icon = if (showSettings) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Settings,
                contentDescription = stringResource(if (showSettings) R.string.cd_back else R.string.cd_settings),
                tint = style.textPrimary,
                onClick = onToggleSettings,
            )
        }
    }
}

@Composable
private fun RoundHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val style = LocalWp2Style.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(style.secondaryBtnBg)
            .border(1.dp, style.secondaryBtnBorder, CircleShape)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun MainContent(
    vm: SideloaderViewModel,
    onConnect: () -> Unit,
    onPickWatch: () -> Unit,
    onPickFile: () -> Unit,
    onInstall: () -> Unit,
    onAskDelete: (Int, String) -> Unit,
    onDownloadUpdate: (String) -> Unit,
) {
    val style = LocalWp2Style.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Update Available Banner
        item {
            AnimatedVisibility(visible = vm.availableUpdate != null) {
                vm.availableUpdate?.let { upd ->
                    UpdateBannerCard(
                        update = upd,
                        onDownload = { onDownloadUpdate(upd.downloadUrl) },
                        onDismiss = { vm.dismissUpdate() },
                    )
                }
            }
        }

        // Success / Error Banners
        item {
            AnimatedVisibility(visible = vm.bannerSuccess != null || vm.bannerError != null) {
                StatusBanner(
                    isError = vm.bannerError != null,
                    text = vm.bannerError ?: vm.bannerSuccess ?: "",
                    onDismiss = { vm.clearBanners() },
                )
            }
        }

        // 1. Watch Connection Hero Card
        item {
            WatchCard(
                status = vm.watchStatus,
                savedWatchName = vm.selectedWatchLabel,
                autoConnect = vm.autoConnect,
                isBusy = vm.isBusy,
                onConnect = onConnect,
                onPickWatch = onPickWatch,
            )
        }

        // 2. Package Picker + Install Card
        item {
            InstallPackageCard(
                pkg = vm.selectedPackage,
                fileError = vm.fileError,
                watchStatus = vm.watchStatus,
                targetSlot = vm.targetSlot,
                transfer = vm.transfer,
                isBusy = vm.isBusy,
                onPickFile = onPickFile,
                onClearFile = { vm.clearSelectedPackage() },
                onSelectSlot = { vm.targetSlot = it },
                onInstall = onInstall,
            )
        }

        // 3. Installed Apps Section
        val ready = vm.watchStatus as? WatchStatus.Ready
        if (ready != null) {
            item {
                Wp2ThemedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${stringResource(R.string.section_installed)} (${ready.loaderState.apps.size} / ${ready.loaderState.totalSlots})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = style.textSecondary,
                            letterSpacing = 0.8.sp,
                        )
                        Text(
                            text = stringResource(R.string.slots_free_count, ready.loaderState.freeSlots),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = style.textMuted,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (ready.loaderState.apps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.no_apps_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = style.textSecondary,
                            )
                        }
                    } else {
                        val entries = ready.loaderState.apps.entries.toList()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            entries.forEach { (slot, header) ->
                                InstalledAppRow(
                                    slot = slot,
                                    header = header,
                                    isRemoving = vm.removingSlot == slot,
                                    enabled = !vm.isBusy,
                                    onDelete = { onAskDelete(slot, header.name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsContent(
    vm: SideloaderViewModel,
    onOpenLogs: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (String) -> Unit,
) {
    val style = LocalWp2Style.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Optional update / status banner right in Settings too
        item {
            AnimatedVisibility(visible = vm.availableUpdate != null) {
                vm.availableUpdate?.let { upd ->
                    UpdateBannerCard(
                        update = upd,
                        onDownload = { onDownloadUpdate(upd.downloadUrl) },
                        onDismiss = { vm.dismissUpdate() },
                    )
                }
            }
        }
        item {
            AnimatedVisibility(visible = vm.bannerSuccess != null || vm.bannerError != null) {
                StatusBanner(
                    isError = vm.bannerError != null,
                    text = vm.bannerError ?: vm.bannerSuccess ?: "",
                    onDismiss = { vm.clearBanners() },
                )
            }
        }

        // 1. Auto-Connect Hero Card
        item {
            Wp2ThemedCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_autoconnect_title),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = style.textPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.settings_autoconnect_sub),
                            fontSize = 12.5.sp,
                            color = style.textSecondary,
                            lineHeight = 17.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = vm.autoConnect,
                        onCheckedChange = { vm.setAutoConnectEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = style.primaryBtnInk,
                            checkedTrackColor = style.primaryBtnTop,
                            uncheckedThumbColor = style.textMuted,
                            uncheckedTrackColor = style.secondaryBtnBg,
                            uncheckedBorderColor = style.secondaryBtnBorder,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Wp2SlotSurface {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_saved_watch),
                                fontSize = 11.sp,
                                color = style.textMuted,
                            )
                            val savedAddr = vm.selectedAddress
                            val savedLabel = if (savedAddr != null) {
                                val name = vm.selectedWatchLabel ?: "CMF Watch Pro 2"
                                "$name · ${savedAddr.takeLast(5)}"
                            } else {
                                stringResource(R.string.settings_saved_watch_none)
                            }
                            Text(
                                text = savedLabel,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = style.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (vm.selectedAddress != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_forget),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = style.badgeInk,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(style.badgeBg)
                                    .clickable { vm.clearSavedWatch() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // 2. Theme Selector Card (4 themes matching HTML mockups)
        item {
            Wp2ThemedCard {
                Text(
                    text = stringResource(R.string.settings_appearance),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = style.textSecondary,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { themeOpt ->
                        val active = vm.appTheme == themeOpt
                        val (nameRes, tagRes) = when (themeOpt) {
                            AppTheme.CMF_NOTHING -> R.string.theme_cmf_name to R.string.theme_cmf_tag
                            AppTheme.LIQUID_GLASS -> R.string.theme_glass_name to R.string.theme_glass_tag
                            AppTheme.NOTHING_LIGHT -> R.string.theme_light_name to R.string.theme_light_tag
                            AppTheme.AMOLED_STEALTH -> R.string.theme_oled_name to R.string.theme_oled_tag
                        }
                        val rowShape = RoundedCornerShape(14.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .background(if (active) style.badgeBg else style.slotBg)
                                .border(
                                    width = 1.dp,
                                    color = if (active) style.accentDot else style.slotBorder,
                                    shape = rowShape,
                                )
                                .clickable { vm.setTheme(themeOpt) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(themeOpt.swatchColor)
                                    .border(1.dp, style.textMuted.copy(alpha = 0.7f), CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(nameRes),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = style.textPrimary,
                                )
                                Text(
                                    text = stringResource(tagRes),
                                    fontSize = 11.5.sp,
                                    color = style.textSecondary,
                                )
                            }
                            if (active) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = style.accentDot,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Updates & BLE Logs Cards
        item {
            Wp2ThemedCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_updates_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = style.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.settings_updates_sub, BuildConfig.VERSION_NAME),
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = style.textMuted,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(style.secondaryBtnBg)
                            .border(1.dp, style.secondaryBtnBorder, CircleShape)
                            .clickable(enabled = !vm.checkingUpdate, onClick = onCheckUpdates)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (vm.checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = style.secondaryBtnInk,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_check_btn),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = style.secondaryBtnInk,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Wp2SlotSurface(onClick = onOpenLogs) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = style.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_logs_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = style.textPrimary,
                            )
                            Text(
                                text = stringResource(R.string.settings_logs_sub, vm.logs.size),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = style.textMuted,
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_open_btn),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = style.badgeInk,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusBanner(
    isError: Boolean,
    text: String,
    onDismiss: () -> Unit,
) {
    val style = LocalWp2Style.current
    val accent = if (isError) MaterialTheme.colorScheme.error else style.success
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else accent.copy(alpha = 0.14f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
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
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else style.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = style.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateBannerCard(
    update: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val style = LocalWp2Style.current
    Wp2ThemedCard(elevated = true, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = style.accentDot,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title, update.latestVersion),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = style.textPrimary,
                )
                Text(
                    text = stringResource(R.string.update_available_sub, BuildConfig.VERSION_NAME),
                    fontSize = 12.sp,
                    color = style.textSecondary,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = style.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryPillButton(
            text = stringResource(R.string.update_download_btn),
            icon = Icons.Rounded.Download,
            enabled = true,
            onClick = onDownload,
        )
    }
}

@Composable
private fun WatchCard(
    status: WatchStatus,
    savedWatchName: String?,
    autoConnect: Boolean,
    isBusy: Boolean,
    onConnect: () -> Unit,
    onPickWatch: () -> Unit,
) {
    val style = LocalWp2Style.current
    Wp2ThemedCard(elevated = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Glowing live status indicator dot or spinner
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (status is WatchStatus.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = style.accentDot,
                    )
                } else {
                    val dotColor = when (status) {
                        is WatchStatus.Ready -> style.success
                        is WatchStatus.Error -> MaterialTheme.colorScheme.error
                        is WatchStatus.StockFirmware -> WarnAmber
                        else -> style.textMuted
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .shadow(
                                elevation = if (status is WatchStatus.Ready) 8.dp else 0.dp,
                                shape = CircleShape,
                                ambientColor = dotColor,
                                spotColor = dotColor,
                            )
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (status) {
                        is WatchStatus.Idle -> savedWatchName ?: "CMF Watch Pro 2"
                        is WatchStatus.Connecting -> stringResource(R.string.watch_connecting)
                        is WatchStatus.Ready -> status.watchName
                        is WatchStatus.StockFirmware -> status.watchName
                        is WatchStatus.Error -> savedWatchName ?: stringResource(R.string.watch_connect_error)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = style.textPrimary,
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
                    fontSize = 12.sp,
                    fontFamily = if (status is WatchStatus.Ready) FontFamily.Monospace else FontFamily.Default,
                    color = when (status) {
                        is WatchStatus.Error -> MaterialTheme.colorScheme.error
                        is WatchStatus.StockFirmware -> WarnAmber
                        else -> style.textSecondary
                    },
                )
            }

            if (autoConnect) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.badge_auto),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = style.badgeInk,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(style.badgeBg)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }

        if (status is WatchStatus.Ready) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
            SecondaryActionButton(
                text = stringResource(
                    if (status is WatchStatus.Ready) R.string.btn_refresh else R.string.btn_connect,
                ),
                icon = if (status is WatchStatus.Ready) Icons.Rounded.Refresh else Icons.Rounded.Bluetooth,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                onClick = onConnect,
            )
            SecondaryActionButton(
                text = stringResource(R.string.btn_pick),
                icon = Icons.Rounded.BluetoothSearching,
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                onClick = onPickWatch,
            )
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
    val style = LocalWp2Style.current
    Wp2ThemedCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.section_install_pkg),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = style.textSecondary,
                letterSpacing = 0.8.sp,
            )
            if (pkg != null) {
                val ok = pkg.inspection.crcValid
                Text(
                    text = stringResource(if (ok) R.string.pill_crc_ok else R.string.pill_crc_bad),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ok) style.success else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (pkg == null) {
            Wp2SlotSurface(onClick = onPickFile, enabled = !isBusy) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileOpen,
                        contentDescription = null,
                        tint = style.accentDot,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.pick_file_title),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = style.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pick_file_hint),
                        fontSize = 12.sp,
                        color = style.textSecondary,
                    )
                }
            }
        } else {
            val ins = pkg.inspection
            val hdr = ins.header

            Wp2SlotSurface(
                borderColor = if (ins.crcValid) style.slotBorder else MaterialTheme.colorScheme.error,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = hdr.name,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = style.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = pkg.fileName,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = style.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!isBusy) {
                            Text(
                                text = stringResource(R.string.btn_change_file),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = style.secondaryBtnInk,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(style.secondaryBtnBg)
                                    .border(1.dp, style.secondaryBtnBorder, RoundedCornerShape(10.dp))
                                    .clickable(onClick = onPickFile)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = onClearFile,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cd_clear_file),
                                    tint = style.textSecondary,
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
                                if (hdr.isLoader) R.string.pill_kind_loader else R.string.pill_kind_app,
                            ),
                        )
                        InfoPill("v${formatVersion(hdr.version)}")
                        InfoPill(stringResource(R.string.pill_size_kb, ins.totalBytes / 1024f))
                        InfoPill("ABI ${ins.abiText}")
                    }
                }
            }

            // Slot selector (only for apps, not loader)
            if (hdr.isApp && watchStatus is WatchStatus.Ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.slot_selector_label),
                    fontSize = 12.sp,
                    color = style.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                val totalSlots = watchStatus.loaderState.totalSlots
                val existingSlot = watchStatus.loaderState.apps.entries
                    .firstOrNull { it.value.name == hdr.name }?.key
                val chipColors = FilterChipDefaults.filterChipColors(
                    containerColor = style.slotBg,
                    labelColor = style.textSecondary,
                    selectedContainerColor = style.badgeBg,
                    selectedLabelColor = style.badgeInk,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = targetSlot == null,
                        onClick = { onSelectSlot(null) },
                        enabled = !isBusy,
                        colors = chipColors,
                        label = {
                            Text(
                                if (existingSlot != null) {
                                    stringResource(R.string.slot_auto_update, existingSlot)
                                } else {
                                    stringResource(R.string.slot_auto)
                                },
                            )
                        },
                    )
                    for (slot in 0 until totalSlots) {
                        val occupant = watchStatus.loaderState.apps[slot]
                        FilterChip(
                            selected = targetSlot == slot,
                            onClick = { onSelectSlot(slot) },
                            enabled = !isBusy,
                            colors = chipColors,
                            label = {
                                Text(
                                    if (occupant != null) "#$slot (${occupant.name})"
                                    else "#$slot",
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
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = transfer.stage,
                    fontSize = 12.sp,
                    color = style.textSecondary,
                )
                val pct = (transfer.fraction * 100).toInt()
                val kbText = stringResource(
                    R.string.transfer_kb,
                    transfer.sentBytes / 1024f,
                    transfer.totalBytes / 1024f,
                )
                Text(
                    text = "$pct% ($kbText)",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = style.textPrimary,
                )
            }
            Spacer(Modifier.height(6.dp))
            val trackColor = style.secondaryBtnBg
            if (transfer.fraction <= 0f) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = style.primaryBtnTop,
                    trackColor = trackColor,
                )
            } else {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = style.primaryBtnTop,
                    trackColor = trackColor,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        PrimaryPillButton(
            text = stringResource(
                when {
                    transfer != null -> R.string.btn_installing
                    pkg == null -> R.string.btn_pick_first
                    pkg.inspection.header.isLoader -> R.string.btn_install_loader
                    else -> R.string.btn_install_app
                },
            ),
            icon = Icons.Rounded.InstallMobile,
            enabled = pkg != null && pkg.inspection.crcValid && !isBusy,
            onClick = onInstall,
        )
    }
}

@Composable
private fun PrimaryPillButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val style = LocalWp2Style.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(style.primaryBtnTop, style.primaryBtnBottom)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = style.primaryBtnInk,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = style.primaryBtnInk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
            )
        }
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val style = LocalWp2Style.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(42.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(style.secondaryBtnBg)
            .border(1.dp, style.secondaryBtnBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = style.secondaryBtnInk,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = style.secondaryBtnInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val style = LocalWp2Style.current
    Wp2SlotSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "%02d".format(slot),
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = style.badgeInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.badgeBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = style.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "v${formatVersion(header.version)}  •  CRC %08x".format(header.crc),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = style.textMuted,
                )
            }
            if (isRemoving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                    color = style.accentDot,
                )
            } else {
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete),
                        tint = style.textMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, tint: Color? = null) {
    val style = LocalWp2Style.current
    val bg = tint?.copy(alpha = 0.14f) ?: style.badgeBg
    val fg = tint ?: style.badgeInk
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = fg,
    )
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
    val style = LocalWp2Style.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.picker_title),
                    color = style.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = style.accentDot,
                    )
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
                            if (scanning) R.string.picker_scanning else R.string.picker_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = style.textSecondary,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices, key = { it.address }) { dev ->
                            val rowShape = RoundedCornerShape(12.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (dev.isCmfWatch) style.badgeBg else style.slotBg)
                                    .border(
                                        1.dp,
                                        if (dev.isCmfWatch) style.accentDot.copy(alpha = 0.5f) else style.slotBorder,
                                        rowShape,
                                    )
                                    .clickable { onSelect(dev) }
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = dev.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (dev.isCmfWatch) FontWeight.Bold else FontWeight.Medium,
                                    color = style.textPrimary,
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
                                    color = style.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !scanning) {
                Text(stringResource(R.string.btn_rescan), color = style.accentDot)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close), color = style.textSecondary)
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
    val style = LocalWp2Style.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.logs_title), color = style.textPrimary) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(style.slotBg)
                    .border(1.dp, style.slotBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (logs.isEmpty()) stringResource(R.string.logs_empty) else logs.joinToString("\n"),
                    color = style.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Row {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy),
                            tint = style.textPrimary,
                        )
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.btn_clear), color = style.textSecondary)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_close), color = style.accentDot)
                }
            }
        },
    )
}
