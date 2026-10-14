package app.flip2layout.switcher

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SwitchPlannerTest {
    @Test fun failedSwitchNeverLaunchesGame() = runBlocking {
        var launched = false
        val result = launchAfterSwitch(Mode.RETRO, { SwitchResult(false, "Disconnected") }, { launched = true })
        assertFalse(result.confirmed); assertFalse(launched)
    }
    @Test fun confirmedSwitchLaunchesExactlyOnce() = runBlocking {
        var count = 0
        launchAfterSwitch(Mode.RETRO, { SwitchResult(true, "Confirmed") }, { count++ })
        assertEquals(1, count)
    }
    @Test fun rapidAppChangesOnlyApplyLatest() {
        val p = SwitchPlanner()
        p.offer("eden", Mode.RETRO, 0); p.offer("albion", Mode.XBOX, 400)
        assertNull(p.take(700)); assertEquals("albion", p.take(1100)?.packageName)
    }
    @Test fun notificationPanelCancelsPendingWithoutDefaultReset() {
        val p = SwitchPlanner()
        p.offer("eden", Mode.RETRO, 0); p.offer(null, Mode.XBOX, 300)
        assertNull(p.take(2000))
    }
    @Test fun duplicateActivityEventsDoNotDelaySwitchForever() {
        val p = SwitchPlanner()
        p.offer("eden", Mode.RETRO, 0); p.offer("eden", Mode.RETRO, 600)
        assertEquals(Mode.RETRO, p.take(700)?.mode)
        p.offer("eden", Mode.RETRO, 2000); assertNull(p.pending)
    }
    @Test fun cooldownAndLauncherGraceAreHonored() {
        val p = SwitchPlanner()
        p.switched(1000); p.offer("home", Mode.XBOX, 1100, true)
        assertNull(p.take(3000)); assertEquals("home", p.take(3500)?.packageName)
    }
    @Test fun disabledAutomationCancelsAndAllowsFreshEnable() {
        val p = SwitchPlanner()
        p.offer("eden", Mode.RETRO, 0); p.reset(); assertNull(p.take(700))
        p.offer("eden", Mode.RETRO, 1000); assertNotNull(p.take(1700))
    }
    @Test fun disconnectedAndInconsistentValuesAreNeverGuessed() {
        assertNull(Mode.fromSettings(1, 1, 0)); assertNull(Mode.fromSettings(0, 1, 1))
        assertNull(Mode.fromSettings(null, null, null))
        assertEquals(Mode.XBOX, Mode.fromSettings(0, 1, 0)); assertEquals(Mode.RETRO, Mode.fromSettings(1, 0, 0))
    }
}
