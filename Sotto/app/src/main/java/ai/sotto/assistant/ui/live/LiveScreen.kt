package ai.sotto.assistant.ui.live

import ai.sotto.assistant.data.model.Speaker
import ai.sotto.assistant.data.model.Suggestion
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.model.TargetState
import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.HeroShape
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.StatusPill
import ai.sotto.assistant.ui.theme.SottoColors
import ai.sotto.assistant.ui.theme.WhisperTextStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live session. Design Doc 1 § UI Components: "Main camera view with face detection
 * overlay showing detected person's name if matched" plus the optional "Real-time
 * transcript display".
 *
 * Everything here is designed to be readable in a one-second glance, because the user's
 * attention belongs to the person in front of them, not to this screen.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showTranscript by viewModel.showTranscript.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // The foreground service exists so the microphone stays live and — more importantly
    // — so there is always a visible notification while Sotto is listening.
    DisposableEffect(Unit) {
        viewModel.startSession()
        ai.sotto.assistant.service.SessionService.start(context)
        onDispose {
            viewModel.stopSession()
            ai.sotto.assistant.service.SessionService.stop(context)
        }
    }

    Box(modifier.fillMaxSize().background(SottoColors.Ink)) {
        if (state.modelsAvailable) {
            CameraSurface(
                onFrame = viewModel::onFrame,
                onError = viewModel::onCameraError,
                modifier = Modifier.fillMaxSize(),
            )
            FaceOverlay(
                face = state.faceBox,
                target = state.target,
                frameWidth = state.frameWidth,
                frameHeight = state.frameHeight,
            )
        }

        // Scrims top and bottom so white text stays legible over any scene.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to SottoColors.Ink.copy(alpha = 0.82f),
                        0.22f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1.0f to SottoColors.Ink.copy(alpha = 0.92f),
                    )
                )
        )

        Column(Modifier.fillMaxSize()) {
            LiveTopBar(state = state, onBack = onBack)

            Spacer(Modifier.weight(1f))

            TargetPlate(
                target = state.target,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(14.dp))

            WhisperCard(
                whisper = state.whisper,
                onReplay = { viewModel.replay(it) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            AnimatedVisibility(visible = showTranscript) {
                TranscriptPanel(
                    lines = state.transcript,
                    onClear = viewModel::clearTranscript,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            ErrorNotice(
                error = state.banner,
                onDismiss = viewModel::dismissBanner,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            state.cameraError?.let {
                ai.sotto.assistant.ui.components.Notice(
                    text = "The camera couldn't start.",
                    detail = it,
                    tone = NoticeTone.ERROR,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            if (!state.modelsAvailable) {
                ai.sotto.assistant.ui.components.Notice(
                    text = "Face recognition isn't available on this install.",
                    detail = "Sotto will still listen and help, it just can't recognise faces.",
                    tone = NoticeTone.WARNING,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            LiveControls(
                state = state,
                showTranscript = showTranscript,
                onToggleTranscript = viewModel::toggleTranscript,
                onStop = {
                    viewModel.stopSession()
                    onBack()
                },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun LiveTopBar(state: LiveViewModel.UiState, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Leave the session",
                tint = SottoColors.Cloud,
            )
        }
        Spacer(Modifier.width(4.dp))
        HealthStrip(state, Modifier.weight(1f))
        MicMeter(level = state.micLevel)
    }
}

/** The four pipeline dots from § Core Architecture, in the order the doc lists them. */
@Composable
private fun HealthStrip(state: LiveViewModel.UiState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusPill(
            label = "Face",
            tone = if (state.health.faceOk) NoticeTone.SUCCESS else NoticeTone.WARNING,
        )
        StatusPill(
            label = "Hearing",
            tone = if (state.health.transcriptionOk) NoticeTone.SUCCESS else NoticeTone.INFO,
        )
        StatusPill(
            label = "Assistant",
            tone = when {
                state.health.assistantOk -> NoticeTone.SUCCESS
                state.isStarting -> NoticeTone.INFO
                else -> NoticeTone.WARNING
            },
            pulsing = state.isStarting,
        )
    }
}

@Composable
private fun MicMeter(level: Float) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(SottoColors.Ink.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.GraphicEq,
            contentDescription = "Microphone level",
            tint = SottoColors.Cloud.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(SottoColors.Cloud.copy(alpha = 0.20f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(SottoColors.Teal)
            )
        }
    }
}

/** Who is in front of the camera. The only place a name is ever shown during a session. */
@Composable
private fun TargetPlate(target: TargetState, modifier: Modifier = Modifier) {
    val (headline, sub, tone) = when (target) {
        TargetState.NoFace ->
            Triple("Point the camera at someone", "Sotto is listening either way", NoticeTone.INFO)

        is TargetState.Tracking ->
            Triple("Holding steady…", "Recognising in a moment", NoticeTone.INFO)

        is TargetState.Unrecognised ->
            Triple("Not in your list", "Sotto stays quiet unless you ask", NoticeTone.INFO)

        is TargetState.Matched -> Triple(
            target.attendee.name,
            target.attendee.headline.ifBlank { "In your attendee list" },
            NoticeTone.SUCCESS,
        )
    }

    val matched = target is TargetState.Matched

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$headline. $sub"
            },
        shape = HeroShape,
        color = if (matched) {
            SottoColors.TealMuted.copy(alpha = 0.92f)
        } else {
            SottoColors.InkElevated.copy(alpha = 0.85f)
        },
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (matched) SottoColors.Teal else SottoColors.Slate)
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (matched) SottoColors.Teal else SottoColors.Cloud,
                )
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = SottoColors.Slate,
                )
            }
            if (target is TargetState.Matched) {
                Text(
                    "${(target.score * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = SottoColors.Teal.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** The whisper. This is the product. */
@Composable
private fun WhisperCard(
    whisper: Suggestion?,
    onReplay: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = whisper != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier,
    ) {
        whisper?.let { s ->
            Surface(
                shape = HeroShape,
                color = SottoColors.VioletMuted.copy(alpha = 0.95f),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = "Sotto suggests: ${s.text}"
                    },
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.kind.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = SottoColors.VioletBright,
                        )
                        if (s.usedWebSearch) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "· LOOKED IT UP",
                                style = MaterialTheme.typography.labelSmall,
                                color = SottoColors.Amber,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { onReplay(s) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Replay,
                                contentDescription = "Say it again",
                                tint = SottoColors.VioletBright,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        s.text,
                        style = WhisperTextStyle,
                        color = SottoColors.Cloud,
                    )
                }
            }
        }
    }
}

private fun SuggestionKind.label(): String = when (this) {
    SuggestionKind.FOLLOW_UP -> "ASK THIS"
    SuggestionKind.CONNECTION -> "YOU BOTH"
    SuggestionKind.TOPIC_SHIFT -> "NEW ANGLE"
    SuggestionKind.IDENTITY -> "WHO THIS IS"
    SuggestionKind.GENERIC -> "TIP"
}

/** Design Doc 1 § UI Components: "Real-time transcript display (optional, for debugging)." */
@Composable
private fun TranscriptPanel(
    lines: List<ai.sotto.assistant.data.model.TranscriptLine>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp),
        shape = RoundedCornerShape(20.dp),
        color = SottoColors.Ink.copy(alpha = 0.88f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TRANSCRIPT",
                    style = MaterialTheme.typography.labelSmall,
                    color = SottoColors.Slate,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Clear the transcript",
                        tint = SottoColors.Slate,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing heard yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SottoColors.SlateDim,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(lines, key = { it.id }) { line ->
                        TranscriptRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(line: ai.sotto.assistant.data.model.TranscriptLine) {
    // Built per-composition rather than held in a file-level val, so changing the
    // device language takes effect without restarting the app.
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row {
        Text(
            timeFormat.format(Date(line.timestampMs)),
            style = MaterialTheme.typography.labelSmall,
            color = SottoColors.SlateDim,
            modifier = Modifier.width(58.dp),
        )
        Text(
            when (line.speaker) {
                Speaker.USER -> "You"
                Speaker.THEM -> "Them"
                Speaker.UNKNOWN -> "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (line.speaker == Speaker.USER) SottoColors.Violet else SottoColors.Teal,
            modifier = Modifier.width(40.dp),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = SottoColors.Cloud.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LiveControls(
    state: LiveViewModel.UiState,
    showTranscript: Boolean,
    onToggleTranscript: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onToggleTranscript,
            shape = CircleShape,
            color = if (showTranscript) {
                SottoColors.Violet.copy(alpha = 0.25f)
            } else {
                SottoColors.InkElevated.copy(alpha = 0.85f)
            },
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Subtitles,
                    contentDescription = if (showTranscript) "Hide transcript" else "Show transcript",
                    tint = if (showTranscript) SottoColors.VioletBright else SottoColors.Slate,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            PrimaryButton(
                text = if (state.isStarting) "Starting…" else "End session",
                icon = Icons.Rounded.Stop,
                onClick = onStop,
                loading = state.isStarting,
            )
        }
    }
}
