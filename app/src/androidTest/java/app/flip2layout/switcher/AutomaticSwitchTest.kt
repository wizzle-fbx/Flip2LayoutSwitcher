package app.flip2layout.switcher

import android.content.Intent
import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Configurator
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in hardware test. Run only with -e hardwareTests true on the selected Flip 2. */
@RunWith(AndroidJUnit4::class)
class AutomaticSwitchTest {
    @Test fun profilesShadeAndSystemRestoration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareTests") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as ProfilesApplication
        // UI automation suppresses other accessibility services unless explicitly disabled.
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val eden = "dev.eden.eden_emulator.nightly"
        val albion = "com.albiononline"
        assumeTrue(app.controller.supported)
        assumeTrue(context.packageManager.getLaunchIntentForPackage(eden) != null && context.packageManager.getLaunchIntentForPackage(albion) != null)
        fun await(timeout: Long = 12000, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (predicate()) return
                Thread.sleep(100)
            }
            assertTrue("Condition timed out; events: ${app.log.value}", predicate())
        }
        await { app.controller.ready.value && app.detectorConnected.value }
        val previous = app.store.state.value.profiles
        val normal = app.controller.current.value ?: error("Unknown original controller")
        val opposite = if (normal == Mode.XBOX) Mode.RETRO else Mode.XBOX
        fun open(pkg: String) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: error("No launcher: $pkg")
            instrumentation.runOnMainSync { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        fun confirm(mode: Mode, label: String) {
            await { app.controller.current.value == mode && !app.controller.transitioning.value }
            assertEquals(mode.temp.toString(), device.executeShellCommand("settings get system temp_abxy_layout_mode").trim())
            assertEquals(mode.flip.toString(), device.executeShellCommand("settings get system flip_button_layout").trim())
            assertEquals("0", device.executeShellCommand("settings get system no_create_gamepad_button_layout").trim())
            assertEquals(mode.flip.toString(), device.executeShellCommand("getprop persist.sys.gamepad.type").trim())
            println("VERIFIED $label: ${mode.label}; settings ${mode.temp}/${mode.flip}/0; property ${mode.flip}")
        }
        fun gamepadId(): Int = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .single { it.vendorId == 0x2022 && it.supportsSource(InputDevice.SOURCE_GAMEPAD) }.id
        try {
            instrumentation.runOnMainSync { app.store.setProfile(eden, opposite); app.store.setProfile(albion, normal) }
            open(eden)
            confirm(opposite, "external launch Eden")
            val id = gamepadId()
            assertTrue(device.openNotification())
            Thread.sleep(1500)
            confirm(opposite, "notification shade")
            assertEquals("Shade must not reconnect controller", id, gamepadId())
            device.pressBack()
            device.pressHome()
            confirm(normal, "launcher restores original Retroid mode")
            await { app.store.session.value.override == null }
            open(eden)
            confirm(opposite, "second external launch Eden")
            open(albion)
            confirm(normal, "external launch Albion")
            println("EVENTS\n${app.log.value.joinToString("\n")}")
        } finally {
            instrumentation.runOnMainSync {
                app.store.setProfile(eden, previous[eden]); app.store.setProfile(albion, previous[albion])
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            await { app.controller.current.value == normal && !app.controller.transitioning.value }
        }
    }
}
