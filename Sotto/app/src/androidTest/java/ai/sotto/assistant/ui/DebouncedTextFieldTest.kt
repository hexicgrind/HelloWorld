package ai.sotto.assistant.ui

import ai.sotto.assistant.ui.components.DebouncedTextField
import ai.sotto.assistant.ui.theme.SottoTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the typing bug.
 *
 * Sotto's settings fields were bound straight to DataStore-backed state, so every
 * keystroke raced a disk write and a Flow emission. The field kept being reset to a
 * value from several frames earlier, which moved the cursor and scrambled the text.
 * These tests pin the behaviour that fixed it.
 */
@RunWith(AndroidJUnit4::class)
class DebouncedTextFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typedTextIsNotClobberedByASlowExternalValue() {
        // Models the real failure: the external value trails the user's typing.
        var committed = ""
        composeRule.setContent {
            var slowExternal by remember { mutableStateOf("") }
            SottoTheme {
                DebouncedTextField(
                    external = slowExternal,
                    onCommit = {
                        committed = it
                        slowExternal = it
                    },
                    label = "Your name",
                    debounceMs = 50,
                )
            }
        }

        composeRule.onNodeWithText("Your name").performTextInput("Grace Hopper")
        composeRule.waitForIdle()

        // The field must still read exactly what was typed, in order.
        composeRule.onNodeWithText("Grace Hopper").assertExists()
        composeRule.waitUntil(2_000) { committed == "Grace Hopper" }
        assertThat(committed).isEqualTo("Grace Hopper")
    }

    @Test
    fun rapidTypingCommitsOnceWithTheFinalValue() {
        val commits = mutableListOf<String>()
        composeRule.setContent {
            SottoTheme {
                DebouncedTextField(
                    external = "",
                    onCommit = { commits += it },
                    label = "Field",
                    debounceMs = 200,
                )
            }
        }

        composeRule.onNodeWithText("Field").performTextInput("abcdef")
        composeRule.waitUntil(3_000) { commits.isNotEmpty() }
        composeRule.waitForIdle()

        assertThat(commits.last()).isEqualTo("abcdef")
        // Debouncing exists so a burst of keystrokes isn't six separate disk writes.
        assertThat(commits.size).isLessThan(6)
    }

    @Test
    fun aValueArrivingAfterFirstCompositionIsAdopted() {
        // Settings load from disk asynchronously; an empty field must pick that up.
        composeRule.setContent {
            var external by remember { mutableStateOf("") }
            SottoTheme {
                DebouncedTextField(external = external, onCommit = {}, label = "Loaded")
                LaunchedEffect(Unit) {
                    delay(100)
                    external = "loaded from disk"
                }
            }
        }

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTextSafe("loaded from disk").isNotEmpty()
        }
        composeRule.onNodeWithText("loaded from disk").assertExists()
    }

    @Test
    fun aLateExternalValueDoesNotOverwriteWhatTheUserTyped() {
        composeRule.setContent {
            var external by remember { mutableStateOf("") }
            SottoTheme {
                DebouncedTextField(external = external, onCommit = {}, label = "Field")
                LaunchedEffect(Unit) {
                    delay(300)
                    external = "value from disk"
                }
            }
        }

        composeRule.onNodeWithText("Field").performTextInput("my typing")
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        // Once the user has touched the field, nothing may yank it out from under them.
        composeRule.onNodeWithText("my typing").assertExists()
    }

    @Test
    fun maxLengthIsEnforced() {
        var committed = ""
        composeRule.setContent {
            SottoTheme {
                DebouncedTextField(
                    external = "",
                    onCommit = { committed = it },
                    label = "Bio",
                    maxLength = 10,
                    debounceMs = 50,
                )
            }
        }

        composeRule.onNodeWithText("Bio").performTextInput("0123456789overflow")
        composeRule.waitUntil(2_000) { committed.isNotEmpty() }

        assertThat(committed).hasLength(10)
    }

    @Test
    fun replacingTextCommitsTheReplacement() {
        var committed = ""
        composeRule.setContent {
            SottoTheme {
                DebouncedTextField(
                    external = "old",
                    onCommit = { committed = it },
                    label = "Field",
                    debounceMs = 50,
                )
            }
        }

        composeRule.onNodeWithText("old").performTextReplacement("new")
        composeRule.waitUntil(2_000) { committed == "new" }
        assertThat(committed).isEqualTo("new")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(
    text: String,
): List<Any> = runCatching {
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes()
}.getOrDefault(emptyList())
