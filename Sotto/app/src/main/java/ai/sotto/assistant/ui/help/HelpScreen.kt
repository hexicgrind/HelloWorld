package ai.sotto.assistant.ui.help

import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.components.SottoMark
import ai.sotto.assistant.ui.earpiece.Bullet
import ai.sotto.assistant.ui.scaffold.SottoTopBar
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "How Sotto works", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SottoMark(size = 64.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Sotto listens, watches, and whispers.",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You keep talking to the person in front of you. Sotto quietly works out " +
                            "who they are and, when there's a natural pause, gives you one useful " +
                            "thing to say.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            item {
                SectionCard(title = "Setting up, once") {
                    Step(1, "Add your API key", "Settings → API keys. One Google key covers everything.")
                    Step(2, "Upload the attendee list", "A CSV, the conference PDF, a photo of a badge table, or pasted text.")
                    Step(3, "Tap Prepare conference data", "Gemini reads it and builds your list. Two to five minutes for a big one.")
                    Step(4, "Enrol a few faces", "People → tap someone → Enrol face. Only enrolled people can be recognised.")
                    Step(5, "Connect an earpiece", "So the whispers are for you alone.")
                }
            }

            item {
                SectionCard(title = "During a conversation") {
                    Bullet("Hold your phone naturally — chest height, camera roughly toward the person.")
                    Bullet("When Sotto recognises someone, the frame turns teal and their name appears.")
                    Bullet("Sotto only speaks at a pause, and only when it has something specific worth saying. Silence is normal and intended.")
                    Bullet("Every whisper also appears on screen, so you can read it if you missed it.")
                    Bullet("Tap the subtitles button to see a running transcript.")
                }
            }

            item {
                SectionCard(title = "If something isn't working") {
                    Trouble(
                        "Sotto never speaks",
                        "Check the Assistant dot at the top of the live screen. If it's amber, your " +
                            "Gemini key may be rejected — test it in Settings.",
                    )
                    Trouble(
                        "It doesn't recognise anyone",
                        "Only people with an enrolled face can be recognised. Check People, and " +
                            "look for the \"Recognisable\" tag.",
                    )
                    Trouble(
                        "It recognises the wrong person",
                        "Lower the confidence in Settings → Face recognition, or re-enrol both " +
                            "people with better lighting.",
                    )
                    Trouble(
                        "Whispers come out of the phone speaker",
                        "Your earpiece isn't connected. Check the Earpiece screen.",
                    )
                    Trouble(
                        "It talks too much",
                        "Settings → How Sotto behaves → raise the interval, or lengthen the " +
                            "required pause.",
                    )
                }
            }

            item {
                SectionCard(title = "Where your data goes") {
                    Bullet("The attendee list, face embeddings and enrolment photos stay on this phone.")
                    Bullet("API keys are encrypted using your phone's hardware keystore, and are excluded from backups.")
                    Bullet("Microphone audio goes to Google Speech-to-Text and the Gemini Live API while a session is running, and nowhere else.")
                    Bullet("Camera frames never leave the phone. Face detection and matching happen entirely on-device.")
                    Bullet("Nothing is recorded or stored after a session ends unless you keep the transcript open.")
                }
            }

            item {
                SectionCard(title = "A word about the people around you") {
                    Text(
                        "Sotto listens to whoever is nearby, and recognises faces you've enrolled. " +
                            "Depending on where you are, recording or identifying people may need " +
                            "their consent — sometimes as a matter of law. Use it with the same " +
                            "care you'd want someone to use around you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, detail: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Trouble(problem: String, fix: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(problem, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            fix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
