package app.flip2layout.switcher

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

class ControllerAccess(private val app: ProfilesApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var remote: IControllerShell? = null
    private var binding = false
    private var lastWriteAt = -10_000L
    private val input = app.getSystemService(InputManager::class.java)
    private val _current = MutableStateFlow<Mode?>(null)
    val current = _current.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _transitioning = MutableStateFlow(false)
    val transitioning = _transitioning.asStateFlow()
    val supported get() = Build.MODEL == "Retroid Pocket Flip2" && Build.FINGERPRINT == ControllerShell.VERIFIED_FIRMWARE
    private val args = Shizuku.UserServiceArgs(ComponentName(app, ControllerShell::class.java))
        .daemon(false).processNameSuffix("controller_shell").tag("controller-style-v1").version(BuildConfig.VERSION_CODE)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binding = false; remote = IControllerShell.Stub.asInterface(service)
            scope.launch {
                val read = withContext(Dispatchers.IO) { runCatching { remote?.readState() }.getOrNull() }
                _ready.value = read != null && !read.startsWith("ERR:") && (parseMode(read) != null || repairable(read))
                app.report(if (_ready.value) "Shizuku shell ready. Controller: ${_current.value?.label ?: "unknown"}" else "Control unavailable: $read")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binding = false; remote = null; _ready.value = false
            // The shell process can die on its own (low memory); rebind instead of waiting for the UI.
            scope.launch { delay(2000); connectIfAllowed() }
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { connectIfAllowed() }
    private val dead = Shizuku.OnBinderDeadListener { remote = null; binding = false; _ready.value = false; app.report("Shizuku connection lost; switching stopped.") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) connectIfAllowed() else app.report("Shizuku permission denied.")
    }
    init {
        refreshCurrent()
        input.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { refreshCurrent() }
            override fun onInputDeviceRemoved(deviceId: Int) { refreshCurrent() }
            override fun onInputDeviceChanged(deviceId: Int) { refreshCurrent() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    fun refreshCurrent() {
        val modes = input.inputDeviceIds.toList().mapNotNull { input.getInputDevice(it) }.filter {
            it.vendorId == 0x2022 && it.supportsSource(InputDevice.SOURCE_GAMEPAD)
        }.mapNotNull { d -> Mode.entries.find { it.product == d.productId } }.distinct()
        _current.value = modes.singleOrNull()
    }
    fun devices(): String = input.inputDeviceIds.toList().mapNotNull { input.getInputDevice(it) }.filter {
        it.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }.joinToString("\n") { "${it.name}: id=${it.id}, vendor=${it.vendorId.toString(16)}, product=${it.productId.toString(16)}" }.ifEmpty { "No gamepad connected" }
    fun shizukuInstalled(): Boolean = runCatching { app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 }.getOrDefault(false)
    fun hasPermission(): Boolean = runCatching { shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    private fun parseMode(read: String): Mode? {
        val values = read.split(',').map { it.toIntOrNull() }
        return if (values.size == 4) Mode.fromSettings(values[0], values[1], values[2]) else null
    }
    /** Controller enabled but the two layout keys disagree, e.g. a write interrupted between keys; a full write repairs it. */
    private fun repairable(read: String): Boolean {
        val v = read.split(',').map { it.toIntOrNull() }
        return v.size == 4 && v[2] == 0 && v[0] in 0..1 && v[1] in 0..1
    }
    fun requestAccess() {
        runCatching {
            check(supported) { "Controller control is not verified for this firmware." }
            check(Shizuku.pingBinder()) { "Start Shizuku with Wireless debugging." }
            check(Shizuku.getUid() == 2000) { "Only Shizuku in ADB mode is supported." }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connectIfAllowed()
            else Shizuku.requestPermission(7)
        }.onFailure { app.report(it.message ?: "Shizuku unavailable") }
    }
    fun connectIfAllowed() {
        runCatching {
            if (!supported || binding || remote != null || !Shizuku.pingBinder()) return
            if (Shizuku.getUid() != 2000 || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            binding = true
            Shizuku.bindUserService(args, connection)
            // If the service never connects, allow a later attempt instead of staying "binding" forever.
            scope.launch { delay(10_000); if (binding && remote == null) binding = false }
        }.onFailure { binding = false; _ready.value = false; app.report("Shizuku connection: ${it.message}") }
    }
    suspend fun switch(mode: Mode, allowed: () -> Boolean = { true }): SwitchResult = mutex.withLock {
        var written = false
        try {
            check(allowed()) { "Switch cancelled." }
            check(supported && _ready.value && remote != null) { "Controller control is not ready." }
            check(!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Screen is locked." }
            val wait = lastWriteAt + 2500 - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            check(allowed()) { "Switch cancelled." }
            val shell = remote ?: error("Shizuku connection lost.")
            val state = withContext(Dispatchers.IO) { shell.readState() }
            val known = parseMode(state)
            check(known != null || repairable(state)) { "Controller off or settings unreadable: $state" }
            refreshCurrent()
            if (known == mode && _current.value == mode) return@withLock SwitchResult(true, "${mode.label} already active.")
            check(allowed()) { "Switch cancelled." }
            _transitioning.value = true
            lastWriteAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { shell.setMode(mode.ordinal) }
            check(result == "OK") { result }
            written = true
            // Bounded transition check only; there is no periodic background polling.
            var stableSince = 0L
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                refreshCurrent()
                val now = SystemClock.elapsedRealtime()
                if (_current.value == mode) {
                    if (stableSince == 0L) stableSince = now
                    if (now - stableSince >= 600) {
                        val verified = withContext(Dispatchers.IO) { shell.readState() }
                        check(parseMode(verified) == mode && verified.substringAfterLast(',') == mode.flip.toString()) { "Firmware verification failed: $verified" }
                        return@withLock SwitchResult(true, "${mode.label} confirmed; controller reconnected.")
                    }
                } else stableSince = 0
                delay(100)
            }
            SwitchResult(false, "Settings written but the controller reconnect could not be confirmed.", written = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SwitchResult(false, e.message ?: "Switch failed", written, cancelled = !written && !allowed())
        } finally {
            _transitioning.value = false
        }
    }
}
