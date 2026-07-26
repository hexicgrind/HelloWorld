package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.ui.theme.SottoTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The enrolment controls.
 *
 * A user got as far as "I select enough good images [and] there is no option to
 * proceed, so I cannot submit them". The controls used to be the last children of a
 * plain, unscrollable Column: once the thumbnail strip and the status message had grown,
 * Save was laid out past the bottom edge of the screen with no way to reach it. They now
 * live in the Scaffold's bottom bar. These tests hold the enable/label logic to account,
 * and the short-screen case below is the one that was actually broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class EnrolActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sample() =
        RosterViewModel.EnrolState.Sample(FloatArray(128) { 0.1f }, 0.8f, null)

    private fun render(
        state: RosterViewModel.EnrolState,
        onSave: () -> Unit = {},
        onCapture: () -> Unit = {},
        onPickPhotos: () -> Unit = {},
    ) {
        compose.setContent {
            SottoTheme {
                Box(Modifier.fillMaxSize()) {
                    EnrolActions(
                        enrol = state,
                        onPickPhotos = onPickPhotos,
                        onCapture = onCapture,
                        onSave = onSave,
                    )
                }
            }
        }
    }

    @Test
    fun `with samples captured the save control is on screen and usable`() {
        // The reported failure, at the screen size where it showed up.
        render(RosterViewModel.EnrolState(samples = List(3) { sample() }))

        compose.onNodeWithText("Save face (3)")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
    }

    @Test
    fun `saving is offered even with a single sample`() {
        render(RosterViewModel.EnrolState(samples = listOf(sample())))

        compose.onNodeWithText("Save face (1 of 3)")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun `tapping save actually calls through`() {
        var saved = false
        render(RosterViewModel.EnrolState(samples = listOf(sample())), onSave = { saved = true })

        compose.onNodeWithText("Save face (1 of 3)").performClick()

        assert(saved) { "Save button did not invoke its callback" }
    }

    @Test
    fun `with nothing captured saving is disabled and the reason is stated`() {
        render(RosterViewModel.EnrolState())

        compose.onNodeWithText("Save face").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Capture or pick at least one photo to enable saving.")
            .assertIsDisplayed()
    }

    @Test
    fun `the nudge disappears once something has been captured`() {
        render(RosterViewModel.EnrolState(samples = listOf(sample())))

        compose.onNodeWithText("Capture or pick at least one photo to enable saving.")
            .assertDoesNotExist()
    }

    @Test
    fun `the capture control shows progress towards the recommended count`() {
        render(RosterViewModel.EnrolState(samples = List(2) { sample() }))

        compose.onNodeWithText("Capture (2/3)").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `capturing stops being offered at the maximum`() {
        render(RosterViewModel.EnrolState(samples = List(RosterViewModel.MAX_SAMPLES) { sample() }))

        compose.onNodeWithText("Capture (${RosterViewModel.MAX_SAMPLES}/3)").assertIsNotEnabled()
        // Saving must still be available — otherwise a full set of samples is a dead end.
        compose.onNodeWithText("Save face (${RosterViewModel.MAX_SAMPLES})").assertIsEnabled()
    }

    @Test
    fun `nothing can be pressed mid-capture`() {
        render(RosterViewModel.EnrolState(samples = listOf(sample()), capturing = true))

        compose.onNodeWithText("Save face (1 of 3)").assertIsNotEnabled()
    }

    @Test
    fun `an already-saved enrolment cannot be saved twice`() {
        render(RosterViewModel.EnrolState(samples = List(3) { sample() }, saved = true))

        compose.onNodeWithText("Saved").assertIsNotEnabled()
    }

    @Test
    fun `the photo picker is reachable and described for screen readers`() {
        var picked = false
        render(RosterViewModel.EnrolState(), onPickPhotos = { picked = true })

        compose.onNodeWithContentDescription("Pick photos instead")
            .assertIsDisplayed()
            .performClick()

        assert(picked) { "Photo picker button did not invoke its callback" }
    }

    @Test
    fun `save stays on screen when the content above it overflows`() {
        // The actual regression, reproduced structurally: content far taller than a short
        // phone screen, with the controls in the Scaffold's bottom bar. Laid out as they
        // were before — as the last children of that same Column — Save would be measured
        // past the bottom edge and assertIsDisplayed would fail.
        compose.setContent {
            SottoTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        EnrolActions(
                            enrol = RosterViewModel.EnrolState(samples = List(3) { sample() }),
                            onPickPhotos = {},
                            onCapture = {},
                            onSave = {},
                        )
                    },
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Far more than a 640dp-tall screen can hold.
                        repeat(20) { Box(Modifier.fillMaxWidth().height(80.dp)) }
                    }
                }
            }
        }

        compose.onNodeWithText("Save face (3)").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `everything stays reachable at a large system font size`() {
        // Accessibility font scaling is exactly the condition that pushed the button off
        // the screen in the first place.
        compose.setContent {
            SottoTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(
                            density = androidx.compose.ui.platform.LocalDensity.current.density,
                            fontScale = 2.0f,
                        )
                ) {
                    Box(Modifier.fillMaxSize()) {
                        EnrolActions(
                            enrol = RosterViewModel.EnrolState(samples = List(3) { sample() }),
                            onPickPhotos = {},
                            onCapture = {},
                            onSave = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Save face (3)").assertIsDisplayed().assertIsEnabled()
    }
}
