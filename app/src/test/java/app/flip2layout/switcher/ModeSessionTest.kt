package app.flip2layout.switcher

import org.junit.Assert.*
import org.junit.Test

class ModeSessionTest {
    @Test fun profileCapturesRetroidPreferenceBeforeSwitch() {
        val session = ModeSession().begin(Mode.RETRO, Mode.XBOX)!!
        assertEquals(Mode.XBOX, session.normal)
        assertEquals(Mode.RETRO, session.target(Mode.RETRO))
        assertEquals(Mode.XBOX, session.target(null))
    }
    @Test fun switchingBetweenProfilesDoesNotReplaceNormalMode() {
        val first = ModeSession().begin(Mode.XBOX, Mode.RETRO)!!
        val second = first.begin(Mode.RETRO, Mode.XBOX)!!
        assertEquals(Mode.RETRO, second.normal)
        assertEquals(Mode.RETRO, second.target(null))
    }
    @Test fun unprofiledAppsDoNotWriteWithoutAnActiveOverride() {
        assertNull(ModeSession(Mode.XBOX).target(null))
    }
    @Test fun restoringClearsOverrideAndLeavesNormalMode() {
        val restored = ModeSession(Mode.XBOX, Mode.RETRO).restored()
        assertEquals(Mode.XBOX, restored.normal)
        assertNull(restored.override)
        assertNull(restored.target(null))
    }
    @Test fun manualRetroidChangeBecomesNewNormalPreference() {
        val external = ModeSession(Mode.XBOX, Mode.RETRO).observeExternal(Mode.XBOX)
        assertEquals(Mode.XBOX, external.normal)
        assertNull(external.override)
    }
    @Test fun sameOrMissingHardwareIdentityDoesNotEraseActiveSession() {
        val active = ModeSession(Mode.XBOX, Mode.RETRO)
        assertEquals(active, active.observeExternal(Mode.RETRO))
        assertEquals(active, active.observeExternal(null))
        assertNull(ModeSession().begin(Mode.RETRO, null))
    }
}
