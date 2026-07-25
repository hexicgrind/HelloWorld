package ai.sotto.assistant.ui.upload

import ai.sotto.assistant.domain.DocumentExtractor
import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.scaffold.SottoTopBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Design Doc 1 § User Input and § Offline Processing.
 *
 * The screen is written for someone who has never seen the app: it says in plain words
 * what to give it, what will happen, and roughly how long it takes.
 */
@Composable
fun UploadScreen(
    viewModel: UploadViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onViewPeople: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addFiles(uris) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "Attendee list", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Give Sotto whatever you have about who's attending. It doesn't need to " +
                        "be tidy — a spreadsheet, a conference PDF, a photo of a badge table, " +
                        "or text you pasted from an email all work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.hasGeminiKey) {
                item {
                    Notice(
                        text = "You'll need a Gemini API key first.",
                        detail = "That's what reads your files and turns them into attendee records.",
                        tone = NoticeTone.WARNING,
                        actionLabel = "Open Settings",
                        onAction = onOpenSettings,
                    )
                }
            }

            item {
                SectionCard(
                    title = "1. Add your files",
                    subtitle = "CSV, PDF, photos, or plain text. Up to ${UploadViewModel.MAX_FILES}.",
                ) {
                    SecondaryButton(
                        text = "Choose files",
                        icon = Icons.Rounded.AttachFile,
                        onClick = { filePicker.launch(DocumentExtractor.PICKER_MIME_TYPES) },
                        enabled = !state.running,
                    )

                    if (state.sources.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        state.sources.forEach { source ->
                            SourceRow(
                                source = source,
                                enabled = !state.running,
                                onRemove = { viewModel.removeSource(source) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "2. Or paste text",
                    subtitle = "Names, one per line, or anything copied from a programme.",
                ) {
                    OutlinedTextField(
                        value = state.pastedText,
                        onValueChange = viewModel::onPastedTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        placeholder = {
                            Text(
                                "Ada Lovelace — Chief Scientist, Analytical Engines\n" +
                                    "Grace Hopper — VP Engineering, Compilers Inc",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        enabled = !state.running,
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                SectionCard(
                    title = "3. Options",
                    subtitle = "Sensible defaults — you can leave these alone.",
                ) {
                    OutlinedTextField(
                        value = state.contextHint,
                        onValueChange = viewModel::onContextHintChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("What is this event? (optional)") },
                        placeholder = { Text("e.g. DevCon 2026, Berlin — infrastructure track") },
                        enabled = !state.running,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Replace my current list",
                        detail = if (state.existingCount == 0) {
                            "Nothing saved yet, so this makes no difference."
                        } else {
                            "Off means merge with the ${state.existingCount} people you already have. " +
                                "Enrolled faces are kept either way."
                        },
                        checked = state.replaceExisting,
                        enabled = !state.running,
                        onCheckedChange = viewModel::setReplaceExisting,
                    )
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Look people up on the web",
                        detail = "More background on each person, but noticeably slower.",
                        checked = state.webGrounding,
                        enabled = !state.running,
                        onCheckedChange = viewModel::setWebGrounding,
                    )
                }
            }

            item {
                AnimatedVisibility(visible = state.running) {
                    SectionCard {
                        Text(state.stageLabel, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.stageDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                        )
                        Spacer(Modifier.height(14.dp))
                        SecondaryButton(
                            text = "Cancel",
                            onClick = viewModel::cancel,
                            destructive = true,
                        )
                    }
                }
            }

            item {
                ErrorNotice(
                    error = state.error,
                    onDismiss = viewModel::dismissError,
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                )
            }

            state.result?.let { done ->
                item {
                    Notice(
                        text = "Added ${done.added} ${if (done.added == 1) "person" else "people"}.",
                        detail = buildString {
                            append("Took ${done.elapsedMs / 1000} seconds. ")
                            append("Next: enrol a few faces so Sotto can recognise them on sight.")
                            if (done.notes.isNotBlank()) append("\n\n${done.notes}")
                        },
                        tone = NoticeTone.SUCCESS,
                        actionLabel = "See the people",
                        onAction = onViewPeople,
                        onDismiss = viewModel::dismissResult,
                    )
                }
            }

            item {
                PrimaryButton(
                    text = "Prepare conference data",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = viewModel::prepare,
                    enabled = state.canRun,
                    loading = state.running,
                )
            }

            item {
                Text(
                    "Everything stays on your phone. Your files are sent to Google's Gemini API " +
                        "once, to be read, and the finished list is saved locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: DocumentExtractor.Source,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val icon: ImageVector = when {
        source.mimeType.startsWith("image/") -> Icons.Rounded.Image
        source.mimeType == "application/pdf" -> Icons.Rounded.PictureAsPdf
        else -> Icons.Rounded.Description
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    source.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (source.isBinary) {
                        "${source.sizeBytes / 1024} KB"
                    } else {
                        "${source.sizeBytes} characters"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove ${source.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
