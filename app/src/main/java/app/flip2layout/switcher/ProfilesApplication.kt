package app.flip2layout.switcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Preferences(val profiles: Map<String, Mode> = emptyMap())

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles_v1", Context.MODE_PRIVATE)
    private val sessionPrefs = context.getSharedPreferences("mode_session_v2", Context.MODE_PRIVATE)
    private fun read() = Preferences(
        prefs.all.filterKeys { it.startsWith("profile:") }.mapNotNull { (key, value) ->
            Mode.entries.find { it.name == value }?.let { key.removePrefix("profile:") to it }
        }.toMap())
    private fun readSession() = ModeSession(
        Mode.entries.find { it.name == sessionPrefs.getString("normal", null) },
        Mode.entries.find { it.name == sessionPrefs.getString("override", null) })
    private val _state = MutableStateFlow(read())
    val state = _state.asStateFlow()
    private val _session = MutableStateFlow(readSession())
    val session = _session.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _state.value = read() }
    init {
        // Earlier betas stored a default mode and an automation on/off switch; automation is now always on.
        val legacy = listOf("default", "automatic", "boot", "setup_seen").filter(prefs::contains)
        if (legacy.isNotEmpty()) prefs.edit().apply { legacy.forEach(::remove) }.apply()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    fun setProfile(pkg: String, mode: Mode?) = setProfiles(mapOf(pkg to mode))
    /** One commit for a batch of edits; a null mode removes the app's profile. */
    fun setProfiles(changes: Map<String, Mode?>) {
        prefs.edit().apply { changes.forEach { (pkg, mode) -> if (mode == null) remove("profile:$pkg") else putString("profile:$pkg", mode.name) } }.apply()
        _state.value = read()
    }
    /** Persists before an override is applied so process death cannot lose the system preference; false if the write failed. */
    fun session(value: ModeSession): Boolean {
        val saved = sessionPrefs.edit().putString("normal", value.normal?.name).putString("override", value.override?.name).commit()
        if (saved) _session.value = value
        return saved
    }
}

class ProfilesApplication : Application() {
    lateinit var store: ProfileStore
    lateinit var controller: ControllerAccess
    lateinit var updater: Updater
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private var applying = false
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val detectorConnected = MutableStateFlow(false)
    override fun onCreate() {
        super.onCreate()
        // Keep events from before a process restart or force-stop for diagnostics.
        _log.value = runCatching { java.io.File(filesDir, "events.txt").readLines().filter { it.isNotBlank() }.takeLast(40) }.getOrDefault(emptyList())
        store = ProfileStore(this); controller = ControllerAccess(this); updater = Updater(this)
        scope.launch {
            controller.current.collect { observed ->
                if (!applying && !controller.transitioning.value) {
                    val updated = store.session.value.observeExternal(observed)
                    if (updated != store.session.value) store.session(updated)
                }
            }
        }
    }
    /** Unsaved profile choices; kept here so Activity recreation (e.g. controller reconnect) does not lose them. */
    val draft = MutableStateFlow<Map<String, Mode?>>(emptyMap())
    fun clearError() { _error.value = null }
    fun saveDraft(): Boolean {
        val changes = draft.value
        if (changes.isEmpty()) return false
        store.setProfiles(changes); draft.value = emptyMap()
        return true
    }
    suspend fun applyProfile(profile: Mode?, allowed: () -> Boolean = { true }): SwitchResult = sessionMutex.withLock {
        if (!allowed()) return@withLock SwitchResult(false, "Cancelled before switching")
        val before = store.session.value
        val target = before.target(profile) ?: return@withLock SwitchResult(true, "System preference kept")
        // Session bookkeeping must follow the switch even if the caller is cancelled meanwhile.
        withContext(NonCancellable) {
            applying = true
            try {
                if (profile != null) {
                    val begun = before.begin(profile, controller.current.value)
                        ?: return@withContext SwitchResult(false, "Controller identity is unknown; no setting changed")
                    if (!store.session(begun)) return@withContext SwitchResult(false, "Could not save the session; no setting changed")
                }
                val result = controller.switch(target, allowed)
                if (result.confirmed) {
                    _error.value = null
                    if (profile == null) store.session(store.session.value.restored())
                } else {
                    if (!result.cancelled) _error.value = result.message
                    // Keep the override if settings were written: a late reconnect must not become the new normal mode.
                    if (profile != null && !result.written) store.session(before)
                }
                result
            } finally { applying = false }
        }
    }
    fun report(message: String) {
        _status.value = message
        _log.value = (_log.value + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}  $message").takeLast(40)
        // Release builds keep diagnostics in app-private storage only, not in the shared system log.
        if (BuildConfig.DEBUG) android.util.Log.i("Flip2LayoutSwitcher", message)
        // Bounded app-private diagnostics; no window text or pairing codes.
        runCatching { java.io.File(filesDir, "events.txt").writeText(_log.value.joinToString("\n")) }
    }
}
