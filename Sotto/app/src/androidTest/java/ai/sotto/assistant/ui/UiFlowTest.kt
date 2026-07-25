package ai.sotto.assistant.ui

import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.Suggestion
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.model.TargetState
import ai.sotto.assistant.ui.components.EmptyState
import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.InitialsAvatar
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.components.StatusPill
import ai.sotto.assistant.ui.help.HelpScreen
import ai.sotto.assistant.ui.onboarding.OnboardingScreen
import ai.sotto.assistant.ui.theme.SottoTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.sotto.assistant.core.AppError
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for the pieces a first-time user meets. The emphasis is on the things that
 * would strand someone: an error with no next step, a disabled button with no
 * explanation, an empty screen with no way forward.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- Onboarding --------------------------------------------------------------

    @Test
    fun onboardingOpensOnTheWelcomePage() {
        composeRule.setContent {
            SottoTheme {
                OnboardingScreen(permissionsGranted = false, onRequestPermissions = {}, onFinish = {})
            }
        }
        composeRule.onNodeWithText("Sotto").assertIsDisplayed()
        composeRule.onNodeWithText("Your networking wingman, in a whisper.").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun onboardingWalksThroughToThePermissionRequest() {
        var requested = false
        composeRule.setContent {
            SottoTheme {
                OnboardingScreen(
                    permissionsGranted = false,
                    onRequestPermissions = { requested = true },
                    onFinish = {},
                )
            }
        }

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("How it works").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Two permissions").assertIsDisplayed()

        composeRule.onNodeWithText("Allow camera and microphone").performClick()
        assertThat(requested).isTrue()
    }

    @Test
    fun onboardingOffersToFinishOncePermissionsAreGranted() {
        var finished = false
        composeRule.setContent {
            SottoTheme {
                OnboardingScreen(
                    permissionsGranted = true,
                    onRequestPermissions = {},
                    onFinish = { finished = true },
                )
            }
        }

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Start using Sotto").performClick()

        assertThat(finished).isTrue()
    }

    @Test
    fun onboardingCanBeSkippedWithoutGrantingPermissions() {
        var finished = false
        composeRule.setContent {
            SottoTheme {
                OnboardingScreen(
                    permissionsGranted = false,
                    onRequestPermissions = {},
                    onFinish = { finished = true },
                )
            }
        }

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Skip for now").performClick()

        assertThat(finished).isTrue()
    }

    // ---- Error presentation ---------------------------------------------------------

    @Test
    fun anErrorShowsBothTheProblemAndTheFix() {
        composeRule.setContent {
            SottoTheme {
                ErrorNotice(error = AppError.MissingApiKey("Gemini"), onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Sotto needs a Gemini key before it can do that.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open Settings and paste your key.").assertIsDisplayed()
    }

    @Test
    fun anErrorCanBeDismissed() {
        var dismissed = false
        composeRule.setContent {
            SottoTheme {
                ErrorNotice(error = AppError.Network(), onDismiss = { dismissed = true })
            }
        }
        composeRule.onNodeWithContentDescriptionSafe("Dismiss").performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun aNoticeActionIsClickable() {
        var acted = false
        composeRule.setContent {
            SottoTheme {
                Notice(
                    text = "You'll need a Gemini API key first.",
                    tone = NoticeTone.WARNING,
                    actionLabel = "Open Settings",
                    onAction = { acted = true },
                )
            }
        }
        composeRule.onNodeWithText("Open Settings").assertHasClickAction().performClick()
        assertThat(acted).isTrue()
    }

    // ---- Empty states -----------------------------------------------------------------

    @Test
    fun anEmptyStateExplainsItselfAndOffersAWayForward() {
        var acted = false
        composeRule.setContent {
            SottoTheme {
                EmptyState(
                    icon = Icons.Rounded.Groups,
                    title = "No attendees yet",
                    body = "Upload a list and Sotto will turn it into people it can recognise.",
                    action = {
                        PrimaryButton(text = "Upload an attendee list", onClick = { acted = true })
                    },
                )
            }
        }

        composeRule.onNodeWithText("No attendees yet").assertIsDisplayed()
        composeRule.onNodeWithText("Upload an attendee list").performClick()
        assertThat(acted).isTrue()
    }

    // ---- Buttons ----------------------------------------------------------------------

    @Test
    fun aDisabledPrimaryButtonCannotBeActivated() {
        var clicked = false
        composeRule.setContent {
            SottoTheme {
                PrimaryButton(text = "Prepare conference data", onClick = { clicked = true }, enabled = false)
            }
        }
        composeRule.onNodeWithText("Prepare conference data").performClick()
        assertThat(clicked).isFalse()
    }

    @Test
    fun aLoadingButtonIgnoresTaps() {
        var clicked = false
        composeRule.setContent {
            SottoTheme {
                PrimaryButton(text = "Working", onClick = { clicked = true }, loading = true)
            }
        }
        composeRule.onNodeWithText("Working").performClick()
        assertThat(clicked).isFalse()
    }

    @Test
    fun aSecondaryButtonWorks() {
        var clicked = false
        composeRule.setContent {
            SottoTheme { SecondaryButton(text = "Check again", onClick = { clicked = true }) }
        }
        composeRule.onNodeWithText("Check again").performClick()
        assertThat(clicked).isTrue()
    }

    // ---- Small components -----------------------------------------------------------------

    @Test
    fun aStatusPillRendersItsLabel() {
        composeRule.setContent {
            SottoTheme { StatusPill(label = "Assistant", tone = NoticeTone.SUCCESS) }
        }
        composeRule.onNodeWithText("Assistant").assertIsDisplayed()
    }

    @Test
    fun anAvatarShowsInitials() {
        composeRule.setContent {
            SottoTheme { InitialsAvatar(initials = Attendee(id = "1", name = "Ada Lovelace").initials) }
        }
        composeRule.onNodeWithText("AL").assertIsDisplayed()
    }

    @Test
    fun aSectionCardShowsItsTitleAndBody() {
        composeRule.setContent {
            SottoTheme {
                SectionCard(title = "API keys", subtitle = "Sotto uses Google's APIs.") {
                    androidx.compose.material3.Text("Body content")
                }
            }
        }
        composeRule.onNodeWithText("API keys").assertIsDisplayed()
        composeRule.onNodeWithText("Sotto uses Google's APIs.").assertIsDisplayed()
        composeRule.onNodeWithText("Body content").assertIsDisplayed()
    }

    // ---- Help ------------------------------------------------------------------------------

    @Test
    fun theHelpScreenCoversSetupAndTroubleshooting() {
        composeRule.setContent { SottoTheme { HelpScreen(onBack = {}) } }

        composeRule.onNodeWithText("Setting up, once").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("If something isn't working").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Where your data goes").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theHelpScreenCanBeDismissed() {
        var backed = false
        composeRule.setContent { SottoTheme { HelpScreen(onBack = { backed = true }) } }
        composeRule.onNodeWithContentDescriptionSafe("Go back").performClick()
        assertThat(backed).isTrue()
    }
}

/** Convenience wrapper so the content-description lookups read cleanly above. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionSafe(
    description: String,
) = onNode(androidx.compose.ui.test.hasContentDescription(description))
