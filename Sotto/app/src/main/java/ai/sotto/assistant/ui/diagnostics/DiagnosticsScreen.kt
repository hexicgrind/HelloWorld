package ai.sotto.assistant.ui.diagnostics

import ai.sotto.assistant.diagnostics.ModelDiagnostics
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.ui.components.LoadingBlock
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.scaffold.SottoTopBar
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * A plain-language "what is wrong with this install" report.
 *
 * Every line is safe to share: no API keys, no attendee data, no transcripts. Its whole
 * purpose is that a failure on a phone I cannot reach can be copied into a message in
 * one tap, instead of being guessed at from a screenshot of an error banner.
 */
@Composable
fun DiagnosticsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<ModelDiagnostics.Report?>(null) }
    var runCount by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(runCount) {
        report = null
        report = ModelDiagnostics.run(
            context = container.appContext,
            io = container.dispatchers.io,
            pipelineSummary = {
                if (container.facePipeline != null) {
                    "running — detector: ${container.activeDetector}"
                } else {
                    "unavailable — face recognition is off"
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "Diagnostics", onBack = onBack) },
    ) { padding ->
        val current = report
        if (current == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingBlock("Checking this install…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (current.allGood) {
                    Notice(
                        text = "Everything checks out.",
                        detail = "Both face models loaded on this phone.",
                        tone = NoticeTone.SUCCESS,
                    )
                } else {
                    Notice(
                        text = "${current.failures.size} problem" +
                            (if (current.failures.size == 1) "" else "s") + " found.",
                        detail = "Tap \"Copy report\" below and send it on — it says exactly " +
                            "what failed and why.",
                        tone = NoticeTone.ERROR,
                    )
                }
            }

            items(current.checks.size) { index ->
                CheckRow(current.checks[index])
            }

            item {
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = if (copied) "Copied" else "Copy report",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        copyToClipboard(context, current.asText())
                        copied = true
                    },
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = "Share report",
                    icon = Icons.Rounded.Share,
                    onClick = { shareReport(context, current.asText()) },
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = "Run again",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        copied = false
                        runCount++
                    },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This report contains no API keys, attendee details or transcripts — " +
                        "only what Sotto needs to explain a failure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(check: ModelDiagnostics.Check) {
    val accent = when (check.status) {
        ModelDiagnostics.Check.Status.OK -> MaterialTheme.colorScheme.secondary
        ModelDiagnostics.Check.Status.WARN -> MaterialTheme.colorScheme.tertiary
        ModelDiagnostics.Check.Status.FAIL -> MaterialTheme.colorScheme.error
        ModelDiagnostics.Check.Status.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = if (check.status == ModelDiagnostics.Check.Status.FAIL) 0.5f else 0.2f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Text(check.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    check.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Monospaced and horizontally scrollable: these lines are technical detail
            // meant to be read exactly, not wrapped prose.
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Sotto diagnostics", text))
}

private fun shareReport(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Sotto diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Share diagnostics")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
