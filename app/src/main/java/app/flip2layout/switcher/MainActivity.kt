package app.flip2layout.switcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

data class InstalledApp(val pkg: String, val label: String, val system: Boolean, val launchable: Boolean, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {
    private val app get() = application as ProfilesApplication
    private var resumeVersion by mutableIntStateOf(0)
    private var testingKeys by mutableStateOf(false)
    private var keyReport by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF8AD7C5), onPrimary = Color(0xFF06382D),
                primaryContainer = Color(0xFF244B43), onPrimaryContainer = Color(0xFFB8F5E2),
                secondaryContainer = Color(0xFF293A36), onSecondaryContainer = Color(0xFFD9E9E1),
                background = Color(0xFF101418), surface = Color(0xFF101418),
                surfaceContainer = Color(0xFF191F25), surfaceContainerHigh = Color(0xFF222930),
                surfaceContainerHighest = Color(0xFF2A323A), outline = Color(0xFF7E8B87))) {
                Surface(Modifier.fillMaxSize()) { ProfilesScreen() }
            }
        }
    }
    override fun onResume() { super.onResume(); resumeVersion++; app.controller.connectIfAllowed(); app.controller.refreshCurrent() }
    // Leaving without pressing Save must not lose choices; recreation keeps the draft in the Application.
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) app.saveDraft() }
    // Public Activity callback; inherited AndroidX core annotation is a lint false positive.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (testingKeys && event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) &&
            event.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                keyReport = "${KeyEvent.keyCodeToString(event.keyCode)} · id ${event.deviceId} · scan ${event.scanCode}"
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.packageName != packageName }.mapNotNull { info -> runCatching {
                val icon = info.loadIcon(packageManager)
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 64, 64); icon.draw(Canvas(bitmap))
                InstalledApp(info.packageName, info.loadLabel(packageManager).toString(),
                    info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    packageManager.getLaunchIntentForPackage(info.packageName) != null, bitmap.asImageBitmap())
            }.getOrNull() }.sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
    }
    private fun open(intent: Intent) { runCatching { startActivity(intent) }.onFailure { app.report("Activity unavailable: ${it.message}") } }
    private fun openShizuku() { packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(::open) }
    // Retroid devices ship the Play Store; fall back to the official download page without it.
    private fun getShizuku() = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).setPackage("com.android.vending"))
    }.onFailure { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
    private fun openHandheldSettings() = open(Intent().setClassName("com.rp.settings", "com.ro.settings.activity.MainSettingsActivity"))
    /** Retroid's launcher skips force-stop for packages in this vendor key. Null if it cannot be read. */
    private fun retroidWhitelisted(): Boolean? = runCatching {
        Settings.System.getString(contentResolver, "app_whiteList")?.split(',')?.contains(packageName) == true
    }.getOrNull()
    /** Retroid's optional "Clean process when standby" force-stops every app outside its own ignore list. */
    private fun standbyCleanupStopsApp(): Boolean = runCatching {
        Settings.System.getInt(contentResolver, "auto_clean_processes", 0) != 0 &&
            Settings.System.getString(contentResolver, "auto_clean_ignored_packages")?.split(',')?.contains(packageName) != true
    }.getOrDefault(false)
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ProfilesScreen() {
        val prefs by app.store.state.collectAsState()
        val draft by app.draft.collectAsState()
        val ready by app.controller.ready.collectAsState()
        val connected by app.detectorConnected.collectAsState()
        val error by app.error.collectAsState()
        val logs by app.log.collectAsState()
        val status by app.status.collectAsState()
        var page by rememberSaveable { mutableStateOf("apps") }
        var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var loadFailed by remember { mutableStateOf(false) }
        var search by rememberSaveable { mutableStateOf("") }
        var restrictedHelp by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val lifecycleVersion = resumeVersion
        val detectionEnabled = remember(lifecycleVersion, connected) {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it == "$packageName/.ForegroundService" || it == "$packageName/$packageName.ForegroundService" } == true
        }
        // Automation runs whenever both permissions work; there is no separate on/off switch.
        val complete = ready && connected && detectionEnabled
        val whitelisted = remember(lifecycleVersion) { retroidWhitelisted() }
        val standbyCleanup = remember(lifecycleVersion) { standbyCleanupStopsApp() }
        // Without the whitelist, clearing Recents silently turns automation off later; treat it as a setup step.
        val setupDone = complete && whitelisted != false && !standbyCleanup
        // One priority for the banner and the Settings card: unsupported firmware, then setup, then a failed switch.
        val problem = when {
            !app.controller.supported -> R.string.unsupported_hint
            !setupDone -> R.string.setup_banner
            error != null -> R.string.automation_error
            else -> null
        }
        val shizukuInstalled = remember(lifecycleVersion, ready, status) { app.controller.shizukuInstalled() }
        val shizukuRunning = remember(lifecycleVersion, ready, status) { app.controller.shizukuRunning() }
        val permission = remember(lifecycleVersion, ready, status) { app.controller.hasPermission() }
        fun refresh() { if (loading && apps.isNotEmpty()) return; scope.launch {
            loading = true; loadFailed = false
            try { apps = installedApps() } catch (_: Exception) { loadFailed = true }
            finally { loading = false }
        } }
        fun choose(pkg: String, mode: Mode) = app.draft.update { current ->
            // Read the live draft, not the last composed one, so quick successive taps are not lost.
            val saved = app.store.state.value.profiles[pkg]
            val shown = if (pkg in current) current[pkg] else saved
            // Tapping the selected mode again clears the profile.
            val next = if (shown == mode) null else mode
            if (next == saved) current - pkg else current + (pkg to next)
        }
        LaunchedEffect(Unit) { refresh() }
        val update by app.updater.available.collectAsState()
        val downloading by app.updater.progress.collectAsState()
        var updateDismissed by rememberSaveable { mutableStateOf<String?>(null) }
        var autoUpdate by remember { mutableStateOf(app.updater.automatic) }
        var showLicenses by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try { app.updater.check() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.report("Update check failed: ${e.message}") }
        }
        fun checkNow() = scope.launch {
            try {
                if (app.updater.check(force = true) == null) snackbar.showSnackbar(getString(R.string.up_to_date)) else updateDismissed = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.report("Update check failed: ${e.message}"); snackbar.showSnackbar(getString(R.string.update_check_failed)) }
        }
        // Setup can be opened from the app list or from Settings; Back and Done return to where it was opened.
        var setupFrom by rememberSaveable { mutableStateOf("apps") }
        fun openSetup(from: String) { setupFrom = from; page = "setup" }
        fun back() { page = when (page) { "diagnostics" -> "settings"; "setup" -> setupFrom; else -> "apps" }; testingKeys = false }
        BackHandler(page != "apps") { back() }
        LaunchedEffect(page) { if (page != "diagnostics") testingKeys = false }
        // The button tester consumes A/B/X/Y; it switches itself off so controller-only users are never stuck in it.
        LaunchedEffect(testingKeys, keyReport) { if (testingKeys) { delay(10_000); testingKeys = false } }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (page != "apps") TopAppBar(title = { Text(stringResource(when (page) {
                    "setup" -> R.string.setup; "diagnostics" -> R.string.diagnostics; else -> R.string.settings
                }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton({ back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    } })
            },
            bottomBar = {
                if (page == "apps") SaveBar(draft.size) {
                    if (app.saveDraft()) scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(getString(R.string.saved)) }
                }
            },
            modifier = Modifier.safeDrawingPadding()
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                when (page) {
                    "apps" -> {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.search_apps)) },
                                leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                                } }, singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).testTag("search"))
                            IconButton({ refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                            IconButton({ page = "settings" }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                        }
                        problem?.let { message ->
                            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(message), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    // A failed switch is not fixed by setup; let the user acknowledge it instead.
                                    if (message == R.string.automation_error) TextButton({ app.clearError() }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dismiss)) }
                                    else TextButton({ openSetup("apps") }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup)) }
                                }
                            }
                        }
                        // Installed games and apps, plus anything that already has (or is getting) a profile.
                        val visible = apps.filter {
                            ((it.launchable && !it.system) || it.pkg in prefs.profiles || it.pkg in draft) &&
                                (it.label.contains(search, true) || it.pkg.contains(search, true))
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (!loading && (visible.isEmpty() || loadFailed)) {
                            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(if (loadFailed) R.string.list_load_failed else R.string.no_matches))
                                if (!loadFailed) Text(stringResource(R.string.empty_search_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        } else LazyVerticalGrid(GridCells.Adaptive(380.dp), Modifier.weight(1f).testTag("app_list"),
                            contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            items(visible, key = { it.pkg }) { entry ->
                                val selected = if (entry.pkg in draft) draft[entry.pkg] else prefs.profiles[entry.pkg]
                                AppCell(entry, selected, entry.pkg in draft) { choose(entry.pkg, it) }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(stringResource(R.string.profile_hint), Modifier.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    "setup" -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item { Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium) }
                        if (!app.controller.supported) item { Text(stringResource(R.string.unsupported_hint), color = MaterialTheme.colorScheme.error) }
                        // Whitelist first: until it is set, clearing Recents mid-setup would undo the accessibility step.
                        // An unreadable whitelist (null) is not treated as missing, matching the banner.
                        item { SetupStep("1", R.string.step_whitelist, whitelisted != false && !standbyCleanup) {
                            Text(stringResource(if (whitelisted == true) R.string.whitelist_done_hint else R.string.whitelist_hint), style = MaterialTheme.typography.bodyMedium)
                            if (standbyCleanup) Text(stringResource(R.string.standby_cleanup_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            if (whitelisted == false || standbyCleanup) Button({ openHandheldSettings() }) { Text(stringResource(R.string.open_handheld_settings)) }
                        } }
                        item { SetupStep("2", R.string.step_control, ready) {
                            val hint = when {
                                ready -> R.string.control_done_hint; !shizukuInstalled -> R.string.install_shizuku_hint;
                                !shizukuRunning -> R.string.start_shizuku_hint; !permission -> R.string.grant_shizuku_hint; else -> R.string.connecting_hint
                            }
                            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                            if (!ready && app.controller.supported) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ when {
                                    !shizukuInstalled -> getShizuku()
                                    !shizukuRunning -> openShizuku()
                                    else -> app.controller.requestAccess()
                                } }) { Text(stringResource(when {
                                    !shizukuInstalled -> R.string.download_shizuku; !shizukuRunning -> R.string.open_shizuku
                                    // Permission already granted but not ready yet: retry the connection instead of asking again.
                                    permission -> R.string.retry; else -> R.string.grant_access
                                })) }
                                if (!shizukuRunning && shizukuInstalled) TextButton({ open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }) { Text(stringResource(R.string.shizuku_guide)) }
                            }
                        } }
                        item { SetupStep("3", R.string.step_detection, connected && detectionEnabled) {
                            Text(stringResource(if (connected && detectionEnabled) R.string.detection_done_hint else R.string.detect_hint), style = MaterialTheme.typography.bodyMedium)
                            if (!connected || !detectionEnabled) Button({
                                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text(stringResource(R.string.enable_detection)) }
                            // A sideloaded APK is blocked by Android's restricted settings until the user allows it.
                            if (!connected || !detectionEnabled) TextButton({ restrictedHelp = true }) { Text(stringResource(R.string.restricted_help)) }
                        } }
                        item { Text(stringResource(R.string.restart_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { Button({ back() }) { Text(stringResource(R.string.done)) } }
                    }
                    "settings" -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (problem == null) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                                    tint = if (problem == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                Text(stringResource(problem ?: R.string.status_ready), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton({ openSetup("settings") }) { Text(stringResource(R.string.setup)) }
                            }
                        } }
                        if (app.updater.enabled) item { Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.updates_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton({ checkNow() }) { Text(stringResource(R.string.check_now)) }
                            val label = stringResource(R.string.updates)
                            Switch(autoUpdate, { autoUpdate = it; app.updater.automatic = it }, Modifier.semantics { contentDescription = label })
                        } }
                        item { Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall); Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
                            Text("${BuildConfig.VERSION_NAME} · ${android.os.Build.MODEL}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton({ page = "diagnostics" }) { Text(stringResource(R.string.technical_details)) }
                                TextButton({ showLicenses = true }) { Text(stringResource(R.string.licenses)) }
                            }
                        }
                    }
                    "diagnostics" -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item { SelectionContainer { Text("${getString(R.string.supported_firmware)}: ${app.controller.supported}\n${android.os.Build.FINGERPRINT}\n\n${app.controller.devices()}", style = MaterialTheme.typography.bodySmall) } }
                        item {
                            val label = stringResource(R.string.test_buttons)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f))
                                Switch(testingKeys, { testingKeys = it }, Modifier.semantics { contentDescription = label })
                            }
                            Text(keyReport.ifEmpty { getString(R.string.press_buttons) })
                        }
                        item { Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleSmall); Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall) }
                        items(logs.takeLast(12)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        update?.takeIf { it.version != updateDismissed || downloading != null }?.let { release ->
            AlertDialog(onDismissRequest = { if (downloading == null) updateDismissed = release.version },
                title = { Text(stringResource(R.string.update_title, release.version.removePrefix("v"))) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Release notes are Markdown; show them as plain, scrollable text so the buttons stay on screen.
                    val notes = release.notes.take(1500).replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").replace(Regex("[#*_`>]"), "").trim()
                    Text(notes.ifBlank { getString(R.string.update_text) }, Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium)
                    downloading?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
                } },
                confirmButton = { TextButton({ scope.launch {
                    runCatching { app.updater.install(release) }.onFailure {
                        app.report("Update failed: ${it.message}"); snackbar.showSnackbar(getString(R.string.update_failed))
                    }
                } }, enabled = downloading == null) { Text(stringResource(R.string.update_now)) } },
                dismissButton = { TextButton({ updateDismissed = release.version }, enabled = downloading == null) { Text(stringResource(R.string.later)) } })
        }
        if (showLicenses) AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text(stringResource(R.string.licenses)) },
            text = {
                val text = remember { resources.openRawResource(R.raw.open_source_licenses).bufferedReader().use { it.readText() } }
                SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
            },
            confirmButton = { TextButton({ showLicenses = false }) { Text(stringResource(R.string.done)) } })
        if (restrictedHelp) AlertDialog(onDismissRequest = { restrictedHelp = false }, title = { Text(stringResource(R.string.restricted_title)) },
            text = { Text(stringResource(R.string.restricted_text)) }, confirmButton = { TextButton({ restrictedHelp = false; open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text(stringResource(R.string.open_app_info)) } },
            dismissButton = { TextButton({ restrictedHelp = false }) { Text(stringResource(R.string.done)) } })
    }
    @Composable private fun SaveBar(unsaved: Int, onSave: () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (unsaved == 0) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (unsaved == 0) stringResource(R.string.all_saved) else pluralStringResource(R.plurals.unsaved_changes, unsaved, unsaved),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (unsaved == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Button(onSave, enabled = unsaved > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("save")) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.save))
                }
            }
        }
    }
    @Composable private fun SetupStep(number: String, title: Int, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (done) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) else Text(number, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (done) Text(stringResource(R.string.step_done), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                content()
            }
        }
    }
    @Composable private fun AppCell(entry: InstalledApp, selected: Mode?, changed: Boolean, onSelect: (Mode) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(entry.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Text(entry.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Small dot marks a choice that is not saved yet.
            val unsaved = stringResource(R.string.not_saved)
            if (changed) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = unsaved })
            ModePicker(selected, entry.pkg, entry.label, onSelect)
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ModePicker(selected: Mode?, pkg: String, appLabel: String, onSelect: (Mode) -> Unit) {
        SingleChoiceSegmentedButtonRow(Modifier.width(184.dp)) {
            Mode.entries.forEachIndexed { index, target ->
                // Screen readers announce "Retro for 9Kings" instead of a bare "Retro".
                val description = stringResource(R.string.mode_for_app, target.label, appLabel)
                SegmentedButton(selected = selected == target, onClick = { onSelect(target) },
                    shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("profile-$pkg-${target.name}").semantics { contentDescription = description },
                    label = { Text(target.label) })
            }
        }
    }
}
