package ai.sotto.assistant.ui.earpiece

import ai.sotto.assistant.audio.BluetoothAudioManager
import ai.sotto.assistant.data.model.AudioRoute
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.ui.components.Notice
import ai.sotto.assistant.ui.components.NoticeTone
import ai.sotto.assistant.ui.components.PrimaryButton
import ai.sotto.assistant.ui.components.SecondaryButton
import ai.sotto.assistant.ui.components.SectionCard
import ai.sotto.assistant.ui.scaffold.SottoTopBar
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
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Design Doc 1 § UI Components: "Earpiece connection manager for Bluetooth device
 * pairing."
 *
 * Android does not let an app drive pairing itself, so this screen does the honest
 * thing: it shows exactly what Sotto can see, explains what will happen, and hands off
 * to system Bluetooth settings for the pairing itself.
 */
@Composable
fun EarpieceScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = container.bluetoothManager
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        manager.startMonitoring()
        onDispose { manager.stopMonitoring() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SottoTopBar(title = "Earpiece", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { RouteHero(state) }

            if (!state.permissionGranted) {
                item {
                    Notice(
                        text = "Sotto can't see your Bluetooth devices yet.",
                        detail = "Grant the Bluetooth permission so it can tell you which " +
                            "earpiece is connected.",
                        tone = NoticeTone.WARNING,
                        actionLabel = "Grant permission",
                        onAction = onRequestBluetoothPermission,
                    )
                }
            }

            if (state.bluetoothAvailable && !state.bluetoothEnabled) {
                item {
                    Notice(
                        text = "Bluetooth is off.",
                        detail = "Turn it on to use a wireless earpiece.",
                        tone = NoticeTone.WARNING,
                        actionLabel = "Open Bluetooth settings",
                        onAction = { context.startActivity(manager.pairingSettingsIntent()) },
                    )
                }
            }

            item {
                SectionCard(
                    title = "Pair an earpiece",
                    subtitle = "Pairing happens in Android's own Bluetooth settings. " +
                        "Once it's connected, come back here and Sotto will pick it up.",
                ) {
                    PrimaryButton(
                        text = "Open Bluetooth settings",
                        icon = Icons.AutoMirrored.Rounded.BluetoothSearching,
                        onClick = { context.startActivity(manager.pairingSettingsIntent()) },
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = "Check again",
                        icon = Icons.Rounded.Refresh,
                        onClick = { manager.refresh() },
                    )
                }
            }

            if (state.permissionGranted) {
                val devices = manager.connectedDeviceNames()
                if (devices.isNotEmpty()) {
                    item {
                        SectionCard(title = "Connected now") {
                            devices.forEach { name ->
                                Row(
                                    Modifier.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.Headphones,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "What to expect") {
                    Bullet("Whispers go to your earpiece automatically — there's nothing to select.")
                    Bullet("If the earpiece disconnects mid-conversation, Sotto falls back to the phone speaker so you don't miss anything.")
                    Bullet("A single-ear earpiece works best: you keep one ear on the actual conversation.")
                    Bullet("Wired headphones work too, and have slightly lower latency than Bluetooth.")
                }
            }
        }
    }
}

@Composable
private fun RouteHero(state: BluetoothAudioManager.EarpieceState) {
    val accent = when (state.route) {
        AudioRoute.BLUETOOTH, AudioRoute.WIRED_HEADSET -> MaterialTheme.colorScheme.secondary
        AudioRoute.PHONE_SPEAKER -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(accent.copy(alpha = 0.10f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isEarpiece) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(state.description, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.isEarpiece) {
                "Only you will hear Sotto."
            } else {
                "Everyone nearby will hear Sotto. Connect an earpiece before a real conversation."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
