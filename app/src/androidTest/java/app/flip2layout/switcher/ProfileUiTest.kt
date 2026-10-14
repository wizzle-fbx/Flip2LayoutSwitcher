package app.flip2layout.switcher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saves only app-local profiles; never launches a game or requests a controller switch. */
@RunWith(AndroidJUnit4::class)
class ProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun searchSelectSaveRefreshAndRecreateKeepTheProfile() {
        val app = compose.activity.application as ProfilesApplication
        val pkg = "dev.eden.eden_emulator.nightly"
        org.junit.Assume.assumeTrue(runCatching { app.packageManager.getApplicationInfo(pkg, 0) }.isSuccess)
        val previous = app.store.state.value.profiles[pkg]
        // Tapping the already selected mode clears it, so pick the other one.
        val target = if (previous == Mode.RETRO) Mode.XBOX else Mode.RETRO
        val tag = "profile-$pkg-${target.name}"
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("app_list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search").performTextInput("Eden")
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).performClick().assertIsSelected()
            assertEquals(previous, app.store.state.value.profiles[pkg])
            compose.onNodeWithTag("save").assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(target, app.store.state.value.profiles[pkg])
            compose.onNodeWithTag("save").assertIsNotEnabled()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.refresh)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).assertIsSelected()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).assertIsSelected()
        } finally {
            compose.runOnUiThread { app.draft.value = emptyMap(); app.store.setProfile(pkg, previous) }
        }
    }
}
