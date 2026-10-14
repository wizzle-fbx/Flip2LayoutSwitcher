package app.flip2layout.switcher

enum class Mode(val label: String, val temp: Int, val flip: Int, val product: Int) {
    RETRO("Retro", 1, 0, 0x3001), XBOX("Xbox", 0, 1, 0x3002);
    companion object {
        fun fromSettings(temp: Int?, flip: Int?, disconnected: Int?): Mode? =
            entries.singleOrNull { disconnected == 0 && it.temp == temp && it.flip == flip }
    }
}

data class PlannedSwitch(val packageName: String, val mode: Mode, val readyAt: Long)

/** Monotonic clock; transient windows cancel pending work without restoring default. */
class SwitchPlanner {
    var pending: PlannedSwitch? = null
        private set
    private var lastAttempt: Pair<String, Mode>? = null
    private var lastSwitchAt = -10_000L
    fun cancel() { pending = null }
    fun reset() { cancel(); lastAttempt = null }
    fun offer(packageName: String?, mode: Mode, now: Long, isHome: Boolean = false) {
        if (packageName == null) { cancel(); return }
        val key = packageName to mode
        if (lastAttempt == key) { cancel(); return }
        if (pending?.let { it.packageName to it.mode } == key) return
        pending = PlannedSwitch(packageName, mode, maxOf(now + if (isHome) 1800 else 700, lastSwitchAt + 2500))
    }
    fun take(now: Long): PlannedSwitch? {
        val p = pending ?: return null
        if (now < p.readyAt) return null
        pending = null
        lastAttempt = p.packageName to p.mode
        return p
    }
    fun switched(now: Long) { lastSwitchAt = now }
}

/** [written]: settings were changed (even if the reconnect was not confirmed); [cancelled]: stopped on purpose, not an error. */
data class SwitchResult(val confirmed: Boolean, val message: String, val written: Boolean = false, val cancelled: Boolean = false)

/** The user's system preference is captured once, before the first override. */
data class ModeSession(val normal: Mode? = null, val override: Mode? = null) {
    fun target(profile: Mode?): Mode? = profile ?: normal.takeIf { override != null }
    fun begin(profile: Mode, observed: Mode?): ModeSession? =
        if (override != null) copy(override = profile)
        else observed?.let { ModeSession(it, profile) }
    fun restored() = copy(override = null)
    fun observeExternal(observed: Mode?): ModeSession = when {
        observed == null || observed == override -> this
        else -> ModeSession(observed)
    }
}

/** Launcher calls its action only after the backend confirms both settings and hardware. */
suspend fun launchAfterSwitch(mode: Mode, switch: suspend (Mode) -> SwitchResult, launch: () -> Unit): SwitchResult {
    val result = switch(mode)
    if (result.confirmed) launch()
    return result
}
