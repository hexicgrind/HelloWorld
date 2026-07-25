package ai.sotto.assistant.ui.onboarding

import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.QuietButton
import ai.sotto.assistant.ui.components.SottoMark
import ai.sotto.assistant.ui.earpiece.Bullet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.sotto.assistant.ui.theme.SottoColors

/**
 * First run. Design Doc 1 § UI Components: "Splash screen with permission requests."
 *
 * Three short screens: what this is, what it needs, and permission. Nothing here asks
 * the user to understand anything technical — the API key comes later, in context, on
 * the screen that actually needs it.
 */
@Composable
fun OnboardingScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableIntStateOf(0) }
    val lastPage = 2

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SottoColors.Ink, SottoColors.InkElevated, SottoColors.Ink)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(48.dp))

            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding",
                modifier = Modifier.weight(1f),
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (current) {
                        0 -> WelcomePage()
                        1 -> HowItWorksPage()
                        else -> PermissionsPage(permissionsGranted)
                    }
                }
            }

            PageDots(page, lastPage + 1)

            Spacer(Modifier.height(20.dp))

            when (page) {
                lastPage -> {
                    if (permissionsGranted) {
                        PrimaryButton(text = "Start using Sotto", onClick = onFinish)
                    } else {
                        PrimaryButton(
                            text = "Allow camera and microphone",
                            onClick = onRequestPermissions,
                        )
                        Spacer(Modifier.height(6.dp))
                        QuietButton(
                            text = "Skip for now",
                            onClick = onFinish,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
                else -> PrimaryButton(text = "Next", onClick = { page++ })
            }

            Spacer(Modifier.height(8.dp))
            if (page in 1..lastPage) {
                QuietButton(
                    text = "Back",
                    onClick = { page-- },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        SottoMark(size = 96.dp)
        Spacer(Modifier.height(28.dp))
        Text("Sotto", style = MaterialTheme.typography.displayMedium, color = SottoColors.Cloud)
        Spacer(Modifier.height(10.dp))
        Text(
            "Your networking wingman, in a whisper.",
            style = MaterialTheme.typography.titleLarge,
            color = SottoColors.Violet,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "You're at a conference. Someone walks up. You know you've met — but the name, " +
                "the company, the thing they're working on? Gone.\n\n" +
                "Sotto remembers for you, and tells you quietly, in your ear, right when you " +
                "need it.",
            style = MaterialTheme.typography.bodyLarge,
            color = SottoColors.Slate,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HowItWorksPage() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "How it works",
            style = MaterialTheme.typography.headlineLarge,
            color = SottoColors.Cloud,
        )
        Spacer(Modifier.height(20.dp))
        HowStep("It sees", "The camera spots the face in front of you and matches it against your attendee list — all on this phone, nothing uploaded.")
        HowStep("It listens", "The microphone follows the conversation so Sotto understands what you're actually talking about.")
        HowStep("It waits", "This is the important part. Sotto stays silent until there's a natural pause, and only speaks when it has something genuinely useful.")
        HowStep("It whispers", "One short line in your earpiece: a question to ask, something you have in common, or a better direction to take things.")
    }
}

@Composable
private fun HowStep(title: String, body: String) {
    Row(Modifier.padding(bottom = 22.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(SottoColors.Teal)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = SottoColors.Cloud)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = SottoColors.Slate)
        }
    }
}

@Composable
private fun PermissionsPage(granted: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Two permissions",
            style = MaterialTheme.typography.headlineLarge,
            color = SottoColors.Cloud,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Sotto can't do its job without these, and it doesn't ask for anything else.",
            style = MaterialTheme.typography.bodyLarge,
            color = SottoColors.Slate,
        )
        Spacer(Modifier.height(24.dp))

        Bullet("Camera — to see who you're talking to. Frames are analysed on this phone and never sent anywhere.")
        Bullet("Microphone — to follow the conversation, so a suggestion actually fits the moment.")

        Spacer(Modifier.height(20.dp))

        if (granted) {
            Notice(
                text = "All set.",
                detail = "You can add your API key and attendee list from the home screen.",
                tone = NoticeTone.SUCCESS,
            )
        } else {
            Notice(
                text = "You can change your mind any time.",
                detail = "Android will ask you next. Both are revocable in system settings.",
                tone = NoticeTone.INFO,
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (index == current) 22.dp else 7.dp, height = 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) SottoColors.Violet else SottoColors.InkOutline
                    )
            )
        }
    }
}
