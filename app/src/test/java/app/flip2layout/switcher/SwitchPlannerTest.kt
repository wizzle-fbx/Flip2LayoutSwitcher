package app.flip2layout.switcher

import org.junit.Assert.*
import org.junit.Test

class SwitchPlannerTest {
    @Test fun rapidAppChangesOnlyApplyLatest() {
        val p = SwitchPlanner()
        p.offer("eden", Mode.RETRO, 0); p.offer("albion", Mode.XBOX, 400)
        assertNull(p.take(700)); assertEquals("albion", p.take(1100)?.packageName)
    }
    @Test fun noForegroundAppCancelsPendingWithoutDefaultReset() {
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
    @Test fun returningToAnAppAfterAnotherAppSwitchesAgain() {
        // e.g. the game's switch was cancelled by a sign-in screen from another package before anything was written.
        val p = SwitchPlanner()
        p.offer("game", Mode.RETRO, 0); assertNotNull(p.take(700))
        p.offer("signin", Mode.XBOX, 800)
        p.offer("game", Mode.RETRO, 1000)
        assertEquals("game", p.pending?.packageName)
    }
    @Test fun cooldownAndLauncherGraceAreHonored() {
        val p = SwitchPlanner()
        p.switched(1000); p.offer("home", Mode.XBOX, 1100, true)
        assertNull(p.take(3000)); assertEquals("home", p.take(3500)?.packageName)
    }
    @Test fun resetCancelsAndAllowsAFreshAttempt() {
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
