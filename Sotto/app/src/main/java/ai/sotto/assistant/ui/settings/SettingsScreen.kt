package ai.sotto.assistant.ui.settings

import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.remote.TextToSpeechClient
import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.components.StatusPill
import ai.sotto.assistant.ui.scaffold.SottoTopBar
import ai.sotto.assistant.ui.upload.ToggleRow
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * Design Doc 1 § UI Components: "Settings panel for Gemini Live context configuration
 * and API key management."
 *
 * Written so a non-technical person can get from "I have a Google account" to "it
 * works" without leaving the screen confused: what each key is for, where to get it,
 * and a button that actually checks it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearKeys by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ErrorNotice(error = state.error, onDismiss = viewModel::dismissError)
            }

            state.savedNotice?.let { notice ->
                item {
                    Notice(
                        text = notice,
                        tone = NoticeTone.SUCCESS,
                        onDismiss = viewModel::dismissNotice,
                    )
                }
            }

            // ---- API keys ---------------------------------------------------------
            item {
                SectionCard(
                    title = "API keys",
                    subtitle = "Sotto uses Google's APIs. Your keys are encrypted with your " +
                        "phone's hardware keystore and never leave the device except to Google.",
                ) {
                    Notice(
                        text = "One key usually covers everything.",
                        detail = "Create a key in Google AI Studio, then enable Speech-to-Text " +
                            "and Text-to-Speech on the same Google Cloud project.",
                        tone = NoticeTone.INFO,
                        actionLabel = "Open Google AI Studio",
                        onAction = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )

                    Spacer(Modifier.height(16.dp))

                    ToggleRow(
                        title = "Use one key for everything",
                        detail = "Turn off only if you have separate keys per service.",
                        checked = state.keyState.useSharedKey,
                        onCheckedChange = viewModel::setUseSharedKey,
                    )

                    Spacer(Modifier.height(16.dp))

                    val services = if (state.keyState.useSharedKey) {
                        listOf(ApiService.GEMINI)
                    } else {
                        ApiService.entries
                    }

                    services.forEach { service ->
                        ApiKeyField(
                            service = service,
                            value = state.keyDrafts[service].orEmpty(),
                            revealed = service in state.revealed,
                            stored = state.keyState.has(service),
                            sharedLabel = state.keyState.useSharedKey,
                            onValueChange = { viewModel.onKeyDraftChange(service, it) },
                            onToggleReveal = { viewModel.toggleReveal(service) },
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    PrimaryButton(text = "Save keys", onClick = viewModel::saveAllKeys)

                    Spacer(Modifier.height(10.dp))

                    SecondaryButton(
                        text = when (state.keyTest) {
                            SettingsViewModel.KeyTestState.TESTING -> "Checking…"
                            SettingsViewModel.KeyTestState.VALID -> "Key works"
                            else -> "Test my key"
                        },
                        icon = if (state.keyTest == SettingsViewModel.KeyTestState.VALID) {
                            Icons.Rounded.Check
                        } else {
                            null
                        },
                        onClick = viewModel::testGeminiKey,
                        loading = state.keyTest == SettingsViewModel.KeyTestState.TESTING,
                    )

                    state.keyTestMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Notice(
                            text = message,
                            tone = if (state.keyTest == SettingsViewModel.KeyTestState.VALID) {
                                NoticeTone.SUCCESS
                            } else {
                                NoticeTone.ERROR
                            },
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = "Remove all keys",
                        onClick = { confirmClearKeys = true },
                        destructive = true,
                    )
                }
            }

            // ---- Assistant --------------------------------------------------------
            item {
                SectionCard(
                    title = "About you",
                    subtitle = "Helps Sotto spot what you have in common with people.",
                ) {
                    OutlinedTextField(
                        value = state.settings.userName,
                        onValueChange = { v -> viewModel.update { it.copy(userName = v) } },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.settings.userRole,
                        onValueChange = { v -> viewModel.update { it.copy(userRole = v) } },
                        label = { Text("What you do") },
                        placeholder = { Text("e.g. Founder of a robotics startup") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.settings.userGoal,
                        onValueChange = { v -> viewModel.update { it.copy(userGoal = v) } },
                        label = { Text("What you want out of the event") },
                        placeholder = { Text("e.g. Find two design partners for our pilot") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }

            item {
                SectionCard(
                    title = "How Sotto behaves",
                    subtitle = "Design Doc default: analyse every 5-10 seconds, only at a pause.",
                ) {
                    SliderRow(
                        label = "How often it may speak",
                        value = state.settings.suggestionIntervalSec.toFloat(),
                        valueRange = SottoSettings.SUGGESTION_INTERVAL_RANGE.first.toFloat()..
                            SottoSettings.SUGGESTION_INTERVAL_RANGE.last.toFloat(),
                        steps = 4,
                        display = { "At most every ${it.roundToInt()} seconds" },
                        onValueChange = { v ->
                            viewModel.update { it.copy(suggestionIntervalSec = v.roundToInt()) }
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    SliderRow(
                        label = "How long a pause must be",
                        value = state.settings.pauseThresholdMs.toFloat(),
                        valueRange = 500f..3_000f,
                        steps = 9,
                        display = { "${(it / 100).roundToInt() / 10f} seconds of quiet" },
                        onValueChange = { v ->
                            viewModel.update { it.copy(pauseThresholdMs = v.roundToInt()) }
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    ToggleRow(
                        title = "Allow live web lookups",
                        detail = "Design doc calls this \"an exception, not the rule\" — Sotto " +
                            "only searches when it really needs to.",
                        checked = state.settings.allowWebSearch,
                        onCheckedChange = { v -> viewModel.update { it.copy(allowWebSearch = v) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.settings.customInstructions,
                        onValueChange = { v -> viewModel.update { it.copy(customInstructions = v) } },
                        label = { Text("Extra instructions (optional)") },
                        placeholder = {
                            Text("e.g. Never suggest small talk about the weather. Be blunter.")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- Voice ------------------------------------------------------------
            item {
                SectionCard(title = "Voice", subtitle = "What the whisper sounds like in your ear.") {
                    VoicePicker(
                        selected = state.settings.ttsVoice,
                        onSelected = { v -> viewModel.update { it.copy(ttsVoice = v) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    SliderRow(
                        label = "Speaking speed",
                        value = state.settings.speakingRate,
                        valueRange = 0.75f..1.5f,
                        steps = 14,
                        display = { "${(it * 100).roundToInt()}% of normal" },
                        onValueChange = { v -> viewModel.update { it.copy(speakingRate = v) } },
                    )
                    Spacer(Modifier.height(18.dp))
                    SliderRow(
                        label = "Whisper volume",
                        value = state.settings.whisperVolume,
                        valueRange = 0.2f..1f,
                        steps = 7,
                        display = { "${(it * 100).roundToInt()}%" },
                        onValueChange = { v -> viewModel.update { it.copy(whisperVolume = v) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton(
                        text = "Hear it",
                        icon = Icons.Rounded.PlayCircle,
                        onClick = viewModel::previewVoice,
                    )
                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        title = "Send whispers to the earpiece first",
                        detail = "Falls back to the phone speaker if nothing is connected.",
                        checked = state.settings.preferBluetoothOutput,
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(preferBluetoothOutput = v) }
                        },
                    )
                }
            }

            // ---- Recognition ------------------------------------------------------
            item {
                SectionCard(
                    title = "Face recognition",
                    subtitle = "Design Doc defaults: 0.70 confidence, one second of tracking.",
                ) {
                    SliderRow(
                        label = "How sure Sotto must be",
                        value = state.settings.matchThreshold,
                        valueRange = SottoSettings.MATCH_THRESHOLD_RANGE,
                        steps = 10,
                        display = {
                            val pct = (it * 100).roundToInt()
                            when {
                                it < 0.6f -> "$pct% — more matches, more mistakes"
                                it > 0.8f -> "$pct% — very strict"
                                else -> "$pct% — balanced"
                            }
                        },
                        onValueChange = { v -> viewModel.update { it.copy(matchThreshold = v) } },
                    )
                    Spacer(Modifier.height(18.dp))
                    SliderRow(
                        label = "Hold on a face for",
                        value = state.settings.trackDwellMs.toFloat(),
                        valueRange = SottoSettings.TRACK_DWELL_RANGE.first.toFloat()..
                            SottoSettings.TRACK_DWELL_RANGE.last.toFloat(),
                        steps = 8,
                        display = { "${(it / 100).roundToInt() / 10f} seconds before recognising" },
                        onValueChange = { v ->
                            viewModel.update { it.copy(trackDwellMs = v.roundToInt()) }
                        },
                    )
                }
            }

            // ---- Feedback and debug -----------------------------------------------
            item {
                SectionCard(title = "Cues and feedback") {
                    ToggleRow(
                        title = "Sound cues",
                        detail = "A soft chime when someone is recognised.",
                        checked = state.settings.soundCuesEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(soundCuesEnabled = v) } },
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Vibration",
                        detail = "A gentle tap so you don't have to look at the screen.",
                        checked = state.settings.hapticsEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(hapticsEnabled = v) } },
                    )
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(text = "Play a cue", onClick = viewModel::playCuePreview)
                }
            }

            item {
                SectionCard(
                    title = "Advanced",
                    subtitle = "Only change these if something isn't working.",
                ) {
                    ToggleRow(
                        title = "Transcribe with Google Cloud",
                        detail = "Off means Sotto relies on Gemini's own transcription instead.",
                        checked = state.settings.useCloudTranscription,
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(useCloudTranscription = v) }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Speak with Google Cloud voices",
                        detail = "Off silences spoken whispers — you'll only read them on screen.",
                        checked = state.settings.useCloudTts,
                        onCheckedChange = { v -> viewModel.update { it.copy(useCloudTts = v) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.settings.geminiModel,
                        onValueChange = { v -> viewModel.update { it.copy(geminiModel = v) } },
                        label = { Text("Live model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.settings.enrichmentModel,
                        onValueChange = { v -> viewModel.update { it.copy(enrichmentModel = v) } },
                        label = { Text("Model for preparing data") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.settings.sttLanguage,
                        onValueChange = { v -> viewModel.update { it.copy(sttLanguage = v) } },
                        label = { Text("Language code") },
                        placeholder = { Text("en-US") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton(
                        text = "Reset all settings",
                        onClick = { confirmReset = true },
                        destructive = true,
                    )
                }
            }

            item {
                Text(
                    "Sotto ${ai.sotto.assistant.BuildConfig.VERSION_NAME} · " +
                        "Proof of concept built to Design Doc 1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = { Text("Everything goes back to the design-doc defaults. Your attendee list and API keys are untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetToDefaults()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (confirmClearKeys) {
        AlertDialog(
            onDismissRequest = { confirmClearKeys = false },
            title = { Text("Remove all API keys?") },
            text = { Text("Sotto won't be able to recognise, listen or speak until you add a key again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearKeys = false
                    viewModel.clearKeys()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearKeys = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApiKeyField(
    service: ApiService,
    value: String,
    revealed: Boolean,
    stored: Boolean,
    sharedLabel: Boolean,
    onValueChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sharedLabel) "Google API key" else "${service.displayName} key",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            if (stored) StatusPill("Saved", NoticeTone.SUCCESS)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AIza…") },
            singleLine = true,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                    )
                }
            },
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = TextToSpeechClient.VOICES.firstOrNull { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TextToSpeechClient.VOICES.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            display(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

private const val AI_STUDIO_URL = "https://aistudio.google.com/app/apikey"
