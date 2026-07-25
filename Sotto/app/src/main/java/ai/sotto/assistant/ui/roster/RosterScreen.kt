package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.ui.components.EmptyState
import ai.sotto.assistant.ui.components.ErrorNotice
import ai.sotto.assistant.ui.components.InitialsAvatar
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.StatusPill
import ai.sotto.assistant.ui.scaffold.SottoTopBar
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    viewModel: RosterViewModel,
    onBack: () -> Unit,
    onOpenAttendee: (String) -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SottoTopBar(
                title = "People",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = "Add someone manually")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.database.isEmpty) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    placeholder = { Text("Search by name, company or interest") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RosterViewModel.Filter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.onFilterChange(filter) },
                            label = {
                                Text(
                                    when (filter) {
                                        RosterViewModel.Filter.ALL -> "All ${state.database.size}"
                                        RosterViewModel.Filter.ENROLLED ->
                                            "Recognisable ${state.database.enrolledCount}"
                                        RosterViewModel.Filter.NOT_ENROLLED ->
                                            "No face ${state.database.size - state.database.enrolledCount}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            ErrorNotice(
                error = state.error,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            when {
                state.database.isEmpty -> EmptyState(
                    icon = Icons.Rounded.Groups,
                    title = "No attendees yet",
                    body = "Upload a list and Sotto will turn it into people it can recognise " +
                        "and talk to you about. You can also add someone by hand.",
                    action = {
                        Column {
                            PrimaryButton(text = "Upload an attendee list", onClick = onUpload)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(
                                text = "Add someone manually",
                                icon = Icons.Rounded.PersonAdd,
                                onClick = { showAddDialog = true },
                            )
                        }
                    },
                )

                state.visible.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Nobody matches",
                    body = "Try a different search, or switch the filter back to All.",
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.visible, key = { it.id }) { attendee ->
                        AttendeeRow(attendee = attendee, onClick = { onOpenAttendee(attendee.id) })
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Tap someone to add their face. Sotto can only recognise people " +
                                "whose face you've enrolled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAttendeeDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, title, company ->
                viewModel.addManually(name, title, company)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AttendeeRow(attendee: Attendee, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(
                initials = attendee.initials,
                highlighted = attendee.hasFace,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    attendee.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (attendee.headline.isNotBlank()) {
                    Text(
                        attendee.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (attendee.hasFace) {
                StatusPill("Recognisable", NoticeTone.SUCCESS)
            } else {
                Icon(
                    Icons.Rounded.Face,
                    contentDescription = "No face enrolled",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun AddAttendeeDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add someone", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text("Company (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, title, company) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
