package app.flip2layout.switcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val apkUrl: String, val notes: String)

/** Compares versions such as "0.3.0-beta" and "v0.10.0"; a version without a pre-release suffix is newer. */
object Versions {
    private fun parts(v: String): Pair<List<Int>, String?> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', "").ifEmpty { null }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
    fun compare(a: String, b: String): Int {
        val (an, ap) = parts(a); val (bn, bp) = parts(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrElse(i) { 0 }).compareTo(bn.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (ap == bp) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ax = ap.split('.'); val bx = bp.split('.')
        for (i in 0 until maxOf(ax.size, bx.size)) {
            val x = ax.getOrNull(i) ?: return -1
            val y = bx.getOrNull(i) ?: return 1
            val c = if (x.toIntOrNull() != null && y.toIntOrNull() != null) x.toInt().compareTo(y.toInt()) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }
    /** Newest non-draft release with an APK asset from a GitHub "list releases" response. */
    fun newest(json: String): Release? {
        val list = JSONArray(json)
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("draft") }.mapNotNull { r ->
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return@mapNotNull null
            Release(r.optString("tag_name"), apk.optString("browser_download_url"), r.optString("body"))
        }.maxWithOrNull { x, y -> compare(x.version, y.version) }
    }
}

/**
 * Checks this project's GitHub Releases and installs a newer APK through Android's package installer.
 * Android itself rejects an update signed with a different key, so only the maintainer's builds can install.
 */
class Updater(private val app: ProfilesApplication) {
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow<Release?>(null)
    val available = _available.asStateFlow()
    /** Download progress 0..1 while an update is being fetched; null otherwise. */
    val progress = MutableStateFlow<Float?>(null)
    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank() && !BuildConfig.DEBUG
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

    /** Returns the newer release, if any. Without [force] it runs at most every 6 hours and only when automatic. */
    suspend fun check(force: Boolean = false): Release? {
        if (!enabled || (!force && !automatic)) return null
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked_at", 0) < 6 * 3600_000L) return _available.value
        val release = withContext(Dispatchers.IO) {
            val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Flip2LayoutSwitcher/${BuildConfig.VERSION_NAME}")
            }
            try {
                check(conn.responseCode == 200) { "GitHub returned ${conn.responseCode}" }
                Versions.newest(conn.inputStream.bufferedReader().use { it.readText() })
            } finally { conn.disconnect() }
        }
        prefs.edit().putLong("checked_at", now).apply()
        _available.value = release?.takeIf { Versions.compare(it.version, BuildConfig.VERSION_NAME) > 0 }
        return _available.value
    }

    /** Downloads [release] and hands it to the system installer, which asks the user to confirm. */
    suspend fun install(release: Release) {
        val file = File(app.cacheDir, "update.apk")
        try {
            progress.value = 0f
            withContext(Dispatchers.IO) {
                val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Flip2LayoutSwitcher/${BuildConfig.VERSION_NAME}")
                }
                try {
                    check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            out.write(buffer, 0, n); done += n
                            if (total > 0) progress.value = done.toFloat() / total
                        }
                    } }
                } finally { conn.disconnect() }
                // Refuse anything that is not a newer build of this app before involving the installer.
                val info = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
                check(info?.packageName == app.packageName) { "Downloaded file is not Flip2 Layout Switcher" }
                check(info!!.longVersionCode > BuildConfig.VERSION_CODE) { "Downloaded build is not newer" }
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(app.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val callback = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(callback.intentSender)
                }
            }
            app.report("Update ${release.version} handed to the installer.")
        } finally {
            progress.value = null
            file.delete()
        }
    }
}

/** Receives the installer status; the system's confirmation screen is started from here. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ProfilesApplication
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> app.report("Update installed.")
            else -> app.report("Update not installed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
