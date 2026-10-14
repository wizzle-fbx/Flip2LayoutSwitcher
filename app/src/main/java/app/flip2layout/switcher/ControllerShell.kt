package app.flip2layout.switcher

import android.os.Build
import android.os.Process
import java.util.concurrent.TimeUnit

/** Shizuku user service. No arbitrary commands, no root, no properties or files written. */
class ControllerShell : IControllerShell.Stub() {
    private val keys = listOf("temp_abxy_layout_mode", "flip_button_layout", "no_create_gamepad_button_layout")
    private fun guard() {
        check(Process.myUid() == 2000) { "Only the ADB shell is supported; root is refused." }
        check(Build.MODEL == "Retroid Pocket Flip2" && Build.FINGERPRINT == VERIFIED_FIRMWARE) { "Firmware not verified." }
    }
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Command timed out") }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0 && !output.contains("Exception") && !output.contains("Error")) { output.take(240) }
        return output
    }
    private fun values(): List<Int> = keys.map { command("/system/bin/settings", "get", "system", it).toInt() }
    override fun readState(): String = try {
        guard()
        values().joinToString(",") + "," + command("/system/bin/getprop", "persist.sys.gamepad.type")
    } catch (e: Exception) { "ERR:${e.message}" }
    @Synchronized override fun setMode(mode: Int): String {
        var before: List<Int>? = null
        return try {
            guard()
            val desired = Mode.entries.getOrNull(mode) ?: error("Invalid mode")
            before = values()
            // Controller must be enabled; a half-written layout pair (interrupted earlier write) is repaired by the full write.
            check(before[2] == 0 && before[0] in 0..1 && before[1] in 0..1) { "Controller off or settings unknown; nothing changed." }
            val target = listOf(desired.temp, desired.flip, 0)
            if (before != target) keys.zip(target).forEach { (key, value) ->
                command("/system/bin/settings", "put", "system", key, value.toString())
            }
            check(values() == target) { "Settings readback failed" }
            "OK"
        } catch (e: Exception) {
            // Return to the known two-mode state after a partial failure, never disconnect.
            val old = before
            val rollback = if (old != null && Mode.fromSettings(old[0], old[1], old[2]) != null) {
                runCatching { keys.zip(old).forEach { (key, value) -> command("/system/bin/settings", "put", "system", key, value.toString()) } }.exceptionOrNull()?.message
            } else null
            "ERR:${e.message}" + (rollback?.let { "; rollback failed: $it" } ?: "")
        }
    }
    override fun destroy() { kotlin.system.exitProcess(0) }
    companion object {
        const val VERIFIED_FIRMWARE = "qti/kona/kona:13/TKQ1.240621.001/eng.RPFlip.20250501.115911:user/release-keys"
    }
}
