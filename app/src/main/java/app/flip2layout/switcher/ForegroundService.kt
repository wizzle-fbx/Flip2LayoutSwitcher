package app.flip2layout.switcher

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

/** Reads package/class metadata only. No node hierarchy, input injection or UI clicking. */
class ForegroundService : AccessibilityService() {
    private val app get() = application as ProfilesApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val planner = SwitchPlanner()
    private var pending: Job? = null
    private var generation = 0
    private var latest: String? = null
    /** Last real app activity in front; re-evaluated when profiles change (e.g. saved while leaving this app). */
    private var foreground: String? = null
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            cancelPending("Screen off; pending switch cancelled."); planner.reset(); latest = null; foreground = null
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        app.detectorConnected.value = true
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        app.controller.connectIfAllowed()
        scope.launch {
            app.store.state.collect {
                cancelPending(); planner.reset(); latest = null
                // A save can land after the target app's window event; apply the new profile to the app in front.
                foreground?.let { if (app.controller.ready.value && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) evaluate(it) }
            }
        }
        scope.launch {
            // When control becomes ready (Shizuku started or reconnected), apply the profile of the app in front.
            app.controller.ready.collect { ready ->
                if (ready) foreground?.let { if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) evaluate(it) }
            }
        }
        app.report("App detection service connected.")
    }
    private var notReadyReported: String? = null
    private fun cancelPending(reason: String? = null) {
        planner.pending?.let { if (reason != null) app.report("${it.packageName} → ${it.mode.label}: $reason") }
        generation++; pending?.cancel(); pending = null; planner.cancel()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val prefs = app.store.state.value
        val pkg = event.packageName?.toString() ?: return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val ignored = pkg == "android" || pkg == "com.android.systemui" ||
            pkg == "com.rp.gameassistant" || pkg == ime || pkg.contains("permissioncontroller")
        val className = event.className?.toString()
        val activity = if (className != null) runCatching {
            packageManager.getActivityInfo(ComponentName(pkg, className), PackageManager.ComponentInfoFlags.of(0))
        }.isSuccess else false
        // Overlays, dialogs, the shade and the keyboard sit on top of the same app: keep any pending switch.
        if (ignored || !activity) return
        // Track the real app in front even while control is unavailable, so it is applied once control is ready.
        foreground = pkg
        if (!app.controller.ready.value) {
            if (pkg in prefs.profiles && notReadyReported != pkg) {
                notReadyReported = pkg; app.report("$pkg: controller control not ready; switch skipped.")
            }
            cancelPending(); return
        }
        notReadyReported = null
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            cancelPending("Screen locked; pending switch cancelled."); planner.reset(); return
        }
        evaluate(pkg)
    }
    private fun evaluate(pkg: String) {
        val profile = app.store.state.value.profiles[pkg]
        val mode = app.store.session.value.target(profile)
        if (mode == null) { cancelPending(); planner.reset(); latest = pkg; return }
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val now = SystemClock.elapsedRealtime()
        val old = planner.pending
        planner.offer(pkg, mode, now, pkg == home || pkg == packageName)
        val next = planner.pending ?: return
        if (old == next && pending?.isActive == true) return
        pending?.cancel(); generation++; latest = pkg
        val token = generation
        pending = scope.launch {
            delay(maxOf(0, next.readyAt - SystemClock.elapsedRealtime()))
            val plan = planner.take(SystemClock.elapsedRealtime()) ?: return@launch
            fun allowed() = generation == token && latest == plan.packageName &&
                app.controller.ready.value &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (!allowed()) {
                // take() marked this attempt; clear it so the same app can be switched on its next event.
                planner.reset(); app.report("${plan.packageName} → ${plan.mode.label}: Switch cancelled.")
                return@launch
            }
            // Complete an already-started transition even if a later window cancels this job.
            // Bookkeeping stays inside: withContext rethrows the caller's cancellation on return.
            withContext(NonCancellable) {
                val result = app.applyProfile(profile, ::allowed)
                if (result.confirmed) planner.switched(SystemClock.elapsedRealtime())
                app.report("${plan.packageName} → ${plan.mode.label}: ${result.message}")
            }
        }
    }
    override fun onInterrupt() { cancelPending() }
    override fun onDestroy() {
        app.detectorConnected.value = false
        cancelPending(); scope.cancel(); runCatching { unregisterReceiver(screen) }; super.onDestroy()
    }
}
