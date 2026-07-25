package ai.sotto.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.model.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * Earpiece connection manager.
 *
 * Design Doc 1 § UI Components lists an "Earpiece connection manager for Bluetooth
 * device pairing", § Audio Output routes TTS "to a Bluetooth earpiece connected to the
 * phone", and § Error Handling requires that "if the Bluetooth earpiece is
 * disconnected, TTS output defaults to phone speaker".
 *
 * Pairing itself is deliberately delegated to the system Bluetooth settings — an app
 * cannot (and should not) drive the pairing UI. What this class owns is knowing which
 * device is connected, routing playback to it, and falling back cleanly.
 */
class BluetoothAudioManager(
    private val context: Context,
) : Closeable {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    data class EarpieceState(
        val route: AudioRoute = AudioRoute.PHONE_SPEAKER,
        val deviceName: String? = null,
        val bluetoothAvailable: Boolean = false,
        val bluetoothEnabled: Boolean = false,
        val permissionGranted: Boolean = false,
        val scoActive: Boolean = false,
    ) {
        val isEarpiece: Boolean get() = route != AudioRoute.PHONE_SPEAKER
        val description: String
            get() = when (route) {
                AudioRoute.BLUETOOTH -> deviceName ?: "Bluetooth earpiece"
                AudioRoute.WIRED_HEADSET -> "Wired headset"
                AudioRoute.PHONE_SPEAKER -> "Phone speaker"
            }
    }

    private val _state = MutableStateFlow(EarpieceState())
    val state: StateFlow<EarpieceState> = _state.asStateFlow()

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                AudioManager.ACTION_HEADSET_PLUG,
                -> refresh()

                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    _state.value = _state.value.copy(
                        scoActive = state == AudioManager.SCO_AUDIO_STATE_CONNECTED,
                    )
                    refresh()
                }
            }
        }
    }

    fun startMonitoring() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        refresh()
    }

    fun stopMonitoring() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    /** Recomputes the current route from the platform's device list. */
    fun refresh(): EarpieceState {
        val hasPermission = hasBluetoothPermission()
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        val bluetooth = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val wired = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val route = when {
            bluetooth != null -> AudioRoute.BLUETOOTH
            wired != null -> AudioRoute.WIRED_HEADSET
            else -> AudioRoute.PHONE_SPEAKER
        }

        val name = when (route) {
            AudioRoute.BLUETOOTH -> bluetooth?.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: connectedBluetoothName()
            AudioRoute.WIRED_HEADSET -> wired?.productName?.toString()?.takeIf { it.isNotBlank() }
            AudioRoute.PHONE_SPEAKER -> null
        }

        val next = EarpieceState(
            route = route,
            deviceName = name,
            bluetoothAvailable = bluetoothAdapter != null,
            bluetoothEnabled = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false),
            permissionGranted = hasPermission,
            scoActive = _state.value.scoActive,
        )
        if (next != _state.value) {
            _state.value = next
            SLog.i(TAG, "Audio route -> ${next.description}")
        }
        return next
    }

    /**
     * Names of currently-connected headset-profile devices, for the picker UI.
     *
     * Suppressed because the permission *is* checked, one line down — lint cannot see
     * through [hasBluetoothPermission], which exists so the API-31 split is expressed
     * once instead of at every call site. The runCatching is the belt to that braces.
     */
    @SuppressLint("MissingPermission")
    fun connectedDeviceNames(): List<String> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = bluetoothAdapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices
                .filter { device ->
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { out ->
                        out.address == device.address
                    }
                }
                .mapNotNull { it.name }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    private fun connectedBluetoothName(): String? {
        if (!hasBluetoothPermission()) return null
        return runCatching {
            bluetoothAdapter?.bondedDevices?.firstOrNull {
                it.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
            }?.name
        }.getOrNull()
    }

    /**
     * Opens a SCO link when the connected earpiece is a hands-free (mono) device. A2DP
     * headphones don't need this and sound far better without it, so we only escalate
     * to SCO when no A2DP output is present.
     */
    fun startScoIfNeeded() {
        if (!hasBluetoothPermission()) return
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        val hasA2dp = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val hasSco = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (hasSco && !hasA2dp) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                SLog.i(TAG, "Opened Bluetooth SCO link")
            }
        }
    }

    fun stopSco() {
        runCatching {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }
        _state.value = _state.value.copy(scoActive = false)
    }

    /** Intent that drops the user into the system Bluetooth settings to pair a device. */
    fun pairingSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }

    override fun close() {
        stopSco()
        stopMonitoring()
    }

    private companion object {
        const val TAG = "BluetoothAudio"
    }
}
