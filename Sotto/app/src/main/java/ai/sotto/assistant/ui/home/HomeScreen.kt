package ai.sotto.assistant.ui.home

import ai.sotto.assistant.ui.Routes
import ai.sotto.assistant.ui.components.EmptyState
import ai.sotto.assistant.ui.components.NavRow
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.components.SottoMark
import ai.sotto.assistant.ui.components.StatusPill
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigate: (String) -> Unit,
    onStartSession: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { HomeHeader(state) }

            if (!state.modelsAvailable) {
                item {
                    Notice(
                        text = "The on-device face models couldn't be loaded.",
                        detail = "Reinstalling Sotto should fix this. Everything else still works.",
                        tone = NoticeTone.ERROR,
                    )
                }
            }

            item {
                StartSessionCard(
                    state = state,
                    onStart = onStartSession,
                    onFixPermissions = onRequestPermissions,
                    onNavigate = onNavigate,
                )
            }

            item { ReadinessCard(state, onNavigate, onRequestPermissions) }

            item {
                SectionCard(title = "Set up") {
                    NavRow(
                        title = "Attendee list",
                        subtitle = if (state.database.isEmpty) {
                            "Upload a CSV, PDF, photo or paste text"
                        } else {
                            "${state.database.size} people · ${state.database.conferenceName.ifBlank { "Untitled conference" }}"
                        },
                        icon = Icons.Rounded.CloudUpload,
                        onClick = { onNavigate(Routes.UPLOAD) },
                    )
                    NavRow(
                        title = "People",
                        subtitle = if (state.database.isEmpty) {
                            "Nobody yet"
                        } else {
                            "${state.database.enrolledCount} of ${state.database.size} faces enrolled"
                        },
                        icon = Icons.Rounded.Groups,
                        onClick = { onNavigate(Routes.ROSTER) },
                    )
                    NavRow(
                        title = "Earpiece",
                        subtitle = state.audioDeviceName ?: "Phone speaker",
                        icon = Icons.Rounded.BluetoothAudio,
                        onClick = { onNavigate(Routes.EARPIECE) },
                    )
                    NavRow(
                        title = "Settings",
                        subtitle = "API keys, assistant behaviour, voice",
                        icon = Icons.Rounded.Settings,
                        onClick = { onNavigate(Routes.SETTINGS) },
                    )
                    NavRow(
                        title = "How Sotto works",
                        subtitle = "A two-minute read",
                        icon = Icons.AutoMirrored.Rounded.HelpOutline,
                        onClick = { onNavigate(Routes.HELP) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: HomeViewModel.UiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SottoMark(size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Sotto", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your networking wingman, in a whisper.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartSessionCard(
    state: HomeViewModel.UiState,
    onStart: () -> Unit,
    onFixPermissions: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                    )
                )
            )
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(
                if (state.canStartSession) "Ready when you are" else "Almost ready",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !state.canStartSession -> {
                        val missing = state.blockers.firstOrNull()
                        missing?.let { "Still needed: ${it.title.lowercase()}." }
                            ?: "One more step before you can start."
                    }
                    state.database.isEmpty ->
                        "No attendee list loaded, so Sotto will give general networking help."
                    state.database.enrolledCount == 0 ->
                        "${state.database.size} people loaded. Enrol a few faces so Sotto can recognise them."
                    else ->
                        "${state.database.enrolledCount} people can be recognised on sight."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            if (state.canStartSession) {
                PrimaryButton(
                    text = "Start live session",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onStart,
                )
            } else {
                val blocker = state.blockers.firstOrNull()
                PrimaryButton(
                    text = when (blocker?.id) {
                        "permissions" -> "Grant camera and microphone"
                        "key" -> "Add your API key"
                        else -> "Finish setup"
                    },
                    onClick = {
                        when (blocker?.id) {
                            "permissions" -> onFixPermissions()
                            else -> onNavigate(blocker?.route ?: Routes.SETTINGS)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    state: HomeViewModel.UiState,
    onNavigate: (String) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val done = state.readiness.count { it.done }
    SectionCard(
        title = "Setup checklist",
        subtitle = "$done of ${state.readiness.size} done. Only the first two are required.",
    ) {
        state.readiness.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            ReadinessRow(
                item = item,
                onClick = {
                    if (item.id == "permissions") onRequestPermissions() else onNavigate(item.route)
                },
            )
        }
    }
}

@Composable
private fun ReadinessRow(
    item: HomeViewModel.ReadinessItem,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "${item.title}. ${if (item.done) "Done" else "Not done"}. ${item.detail}"
            },
        color = Color.Transparent,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(vertical = 11.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = when {
                item.done -> MaterialTheme.colorScheme.secondary
                item.required -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (item.done) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.done) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.7f))
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.done && !item.required) {
                StatusPill("Optional", NoticeTone.INFO)
            }
        }
    }
}
