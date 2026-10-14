package app.flip2layout.switcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.core.content.edit
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
 * Checks this project's GitHub Releases and opens a newer APK in Android's own install screen.
 * Android itself rejects an update signed with a different key, so only the maintainer's builds can install.
 */
class Updater(private val app: ProfilesApplication) {
    companion object {
        private const val MAX_APK = 50L * 1024 * 1024
        private const val MAX_JSON = 512 * 1024
        /** Shared read-only with the install screen through the FileProvider (res/xml/update_paths.xml). */
        fun updateFile(context: Context) = File(context.cacheDir, "updates/update.apk")
    }
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow<Release?>(null)
    val available = _available.asStateFlow()
    /** Download progress 0..1 while an update is being fetched; null otherwise. */
    val progress = MutableStateFlow<Float?>(null)
    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank() && !BuildConfig.DEBUG
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit { putBoolean("automatic", value) } }

    /** The install screen, kept until an Activity can show it (Android blocks it from the background). */
    val pendingConfirmation = MutableStateFlow<Intent?>(null)
    /** True while MainActivity is resumed, so the install screen may be started directly. */
    @Volatile var activityVisible = false

    /** A release found earlier survives process restarts so the 6-hour throttle cannot hide it. */
    private fun remembered(): Release? = prefs.getString("found_version", null)?.let { version ->
        Release(version, prefs.getString("found_url", null) ?: return null, prefs.getString("found_notes", "") ?: "")
    }?.takeIf { Versions.compare(it.version, BuildConfig.VERSION_NAME) > 0 }

    /** Returns the newer release, if any. Without [force] it runs at most every 6 hours and only when automatic. */
    suspend fun check(force: Boolean = false): Release? {
        if (!enabled || (!force && !automatic)) return null
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked_at", 0) < 6 * 3600_000L) return remembered().also { _available.value = it }
        val release = withContext(Dispatchers.IO) {
            val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Flip2LayoutSwitcher/${BuildConfig.VERSION_NAME}")
            }
            try {
                check(conn.responseCode == 200) { "GitHub returned ${conn.responseCode}" }
                val bytes = conn.inputStream.use { it.readNBytes(MAX_JSON + 1) }
                check(bytes.size <= MAX_JSON) { "GitHub response too large" }
                val body = String(bytes, Charsets.UTF_8)
                Versions.newest(body)
            } finally { conn.disconnect() }
        }
        val newer = release?.takeIf { Versions.compare(it.version, BuildConfig.VERSION_NAME) > 0 }
        prefs.edit {
            putLong("checked_at", now).putString("found_version", newer?.version)
                .putString("found_url", newer?.apkUrl).putString("found_notes", newer?.notes)
        }
        _available.value = newer
        return newer
    }

    /**
     * Downloads [release] and opens it in Android's install screen, like opening the APK from Files.
     * (An update through the app's own PackageInstaller session once dropped the app from Retroid's whitelist; installs
     * through the install screen kept it.)
     */
    suspend fun install(release: Release) {
        val file = updateFile(app)
        var handedOver = false
        try {
            progress.value = 0f
            withContext(Dispatchers.IO) {
                // Only this repository's release assets, over HTTPS (redirects to GitHub's storage stay on HTTPS).
                val url = URL(release.apkUrl)
                check(url.protocol == "https" && url.host == "github.com" &&
                    url.path.startsWith("/${BuildConfig.UPDATE_REPO}/releases/download/")) { "Unexpected download location" }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Flip2LayoutSwitcher/${BuildConfig.VERSION_NAME}")
                }
                try {
                    check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
                    val total = conn.contentLengthLong
                    check(total <= MAX_APK) { "Download too large" }
                    file.parentFile?.mkdirs()
                    conn.inputStream.use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            out.write(buffer, 0, n); done += n
                            check(done <= MAX_APK) { "Download too large" }
                            if (total > 0) progress.value = done.toFloat() / total
                        }
                    } }
                } finally { conn.disconnect() }
                // Refuse anything that is not a newer build of this app before involving the installer.
                val info = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
                check(info?.packageName == app.packageName) { "Downloaded file is not Flip2 Layout Switcher" }
                check(info!!.longVersionCode > BuildConfig.VERSION_CODE) { "Downloaded build is not newer" }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            val confirm = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            // From the background Android silently blocks this; MainActivity shows it on its next resume instead.
            if (activityVisible) app.startActivity(confirm) else pendingConfirmation.value = confirm
            handedOver = true
            app.report("Update ${release.version} opened in the installer.")
        } finally {
            progress.value = null
            // The install screen reads the file after this returns; it is removed once the update is installed.
            if (!handedOver) file.delete()
        }
    }
}

/** Runs in the new version after an update: reports it and removes the downloaded APK. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Updater.updateFile(context).delete()
        (context.applicationContext as ProfilesApplication).report("Update installed (${BuildConfig.VERSION_NAME}).")
    }
}
