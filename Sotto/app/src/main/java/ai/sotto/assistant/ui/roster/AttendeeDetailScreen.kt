package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.ui.components.InitialsAvatar
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.scaffold.SottoTopBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AttendeeDetailScreen(
    attendeeId: String,
    viewModel: RosterViewModel,
    onBack: () -> Unit,
    onEnroll: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attendee = state.database.findById(attendeeId)

    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(attendee) {
        if (attendee == null) onBack()
    }
    if (attendee == null) return

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val photo = viewModel.photoFile(attendee)
                    if (photo != null) {
                        Image(
                            painter = rememberAsyncImagePainter(photo),
                            contentDescription = "Enrolled face for ${attendee.name}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        InitialsAvatar(
                            initials = attendee.initials,
                            size = 104.dp,
                            highlighted = attendee.hasFace,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(attendee.name, style = MaterialTheme.typography.headlineMedium)
                    if (attendee.headline.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            attendee.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                if (attendee.hasFace) {
                    Notice(
                        text = "Sotto can recognise ${attendee.name.substringBefore(' ')}.",
                        detail = attendee.enrolledAt?.let {
                            "Face added ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}."
                        },
                        tone = NoticeTone.SUCCESS,
                    )
                } else {
                    Notice(
                        text = "No face enrolled yet.",
                        detail = "Add a face and Sotto will know who this is the moment they're " +
                            "in front of the camera.",
                        tone = NoticeTone.INFO,
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        text = if (attendee.hasFace) "Re-enrol face" else "Enrol face",
                        icon = Icons.Rounded.Face,
                        onClick = { onEnroll(attendee.id) },
                    )
                    if (attendee.hasFace) {
                        SecondaryButton(
                            text = "Forget this face",
                            onClick = { viewModel.clearEnrolment(attendee.id) },
                        )
                    }
                }
            }

            if (attendee.bio.isNotBlank()) {
                item {
                    SectionCard(title = "Background") {
                        Text(attendee.bio, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (attendee.interests.isNotEmpty()) {
                item {
                    SectionCard(title = "Interests", subtitle = "Sotto uses these to find common ground.") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attendee.interests.forEach { interest ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(interest, style = MaterialTheme.typography.labelMedium)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            attendee.location?.let { location ->
                item {
                    SectionCard(title = "Where to find them") {
                        Text(location, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(text = "Edit details", onClick = { editing = true })
                    SecondaryButton(
                        text = "Remove from list",
                        icon = Icons.Rounded.Delete,
                        onClick = { confirmDelete = true },
                        destructive = true,
                    )
                }
            }
        }
    }

    if (editing) {
        EditAttendeeDialog(
            attendee = attendee,
            onDismiss = { editing = false },
            onSave = { updated ->
                viewModel.update(updated)
                editing = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${attendee.name}?") },
            text = { Text("This deletes their record and enrolled face from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(attendee.id)
                    onBack()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun EditAttendeeDialog(
    attendee: ai.sotto.assistant.data.model.Attendee,
    onDismiss: () -> Unit,
    onSave: (ai.sotto.assistant.data.model.Attendee) -> Unit,
) {
    var name by remember { mutableStateOf(attendee.name) }
    var title by remember { mutableStateOf(attendee.title) }
    var company by remember { mutableStateOf(attendee.company) }
    var bio by remember { mutableStateOf(attendee.bio) }
    var interests by remember { mutableStateOf(attendee.interests.joinToString(", ")) }
    var location by remember { mutableStateOf(attendee.location.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details", style = MaterialTheme.typography.headlineSmall) },
        text = {
            val scroll = rememberScrollState()
            Column(
                Modifier.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = company, onValueChange = { company = it },
                    label = { Text("Company") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { if (it.length <= 200) bio = it },
                    label = { Text("Bio") },
                    supportingText = { Text("${bio.length}/200") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = interests, onValueChange = { interests = it },
                    label = { Text("Interests, comma separated") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("Location (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        attendee.copy(
                            name = name,
                            title = title,
                            company = company,
                            bio = bio,
                            interests = interests.split(',').map { it.trim() }.filter { it.isNotBlank() },
                            location = location.trim().takeIf { it.isNotBlank() },
                        ).sanitised()
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
