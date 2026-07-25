package ai.sotto.assistant.ui

import ai.sotto.assistant.SottoApplication
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.ui.earpiece.EarpieceScreen
import ai.sotto.assistant.ui.help.HelpScreen
import ai.sotto.assistant.ui.home.HomeScreen
import ai.sotto.assistant.ui.home.HomeViewModel
import ai.sotto.assistant.ui.live.LiveScreen
import ai.sotto.assistant.ui.live.LiveViewModel
import ai.sotto.assistant.ui.onboarding.OnboardingScreen
import ai.sotto.assistant.ui.roster.AttendeeDetailScreen
import ai.sotto.assistant.ui.roster.EnrollScreen
import ai.sotto.assistant.ui.roster.RosterScreen
import ai.sotto.assistant.ui.roster.RosterViewModel
import ai.sotto.assistant.ui.settings.SettingsScreen
import ai.sotto.assistant.ui.settings.SettingsViewModel
import ai.sotto.assistant.ui.theme.SottoTheme
import ai.sotto.assistant.ui.upload.UploadScreen
import ai.sotto.assistant.ui.upload.UploadViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /** Held so a Compose screen can trigger the runtime permission dialog. */
    private var onPermissionResult: ((Map<String, Boolean>) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> onPermissionResult?.invoke(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = SottoApplication.from(this)

        // Hold the splash until we know whether to show onboarding, so the user never
        // sees the home screen flash before onboarding replaces it.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        var startDestination by mutableStateOf(Routes.HOME)
        lifecycleScope.launch {
            val settings = container.settingsRepository.settings.first()
            startDestination = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING
            ready = true
        }

        setContent {
            SottoTheme {
                Surface(Modifier.fillMaxSize()) {
                    SottoApp(
                        container = container,
                        startDestination = startDestination,
                        hasPermissions = ::hasPermissions,
                        requestPermissions = ::requestPermissions,
                    )
                }
            }
        }
    }

    private fun hasPermissions(): Pair<Boolean, Boolean> {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return camera to mic
    }

    private fun requestPermissions(includeBluetooth: Boolean, onResult: () -> Unit) {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (includeBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

        onPermissionResult = {
            container.bluetoothManager.refresh()
            onResult()
        }
        permissionLauncher.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        container.bluetoothManager.refresh()
    }
}

@Composable
private fun SottoApp(
    container: AppContainer,
    startDestination: String,
    hasPermissions: () -> Pair<Boolean, Boolean>,
    requestPermissions: (Boolean, () -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val factory = remember(container) { buildFactory(container) }

    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val rosterViewModel: RosterViewModel = viewModel(factory = factory)
    val uploadViewModel: UploadViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    var permissionTick by remember { mutableStateOf(0) }

    LaunchedEffect(permissionTick, startDestination) {
        val (camera, mic) = hasPermissions()
        homeViewModel.onPermissionsChanged(camera, mic)
    }

    val askForPermissions: (Boolean) -> Unit = { includeBluetooth ->
        requestPermissions(includeBluetooth) { permissionTick++ }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(200)) },
    ) {
        composable(Routes.ONBOARDING) {
            val (camera, mic) = hasPermissions()
            OnboardingScreen(
                permissionsGranted = camera && mic,
                onRequestPermissions = { askForPermissions(true) },
                onFinish = {
                    homeViewModel.markOnboardingComplete()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigate = navController::navigate,
                onStartSession = { navController.navigate(Routes.LIVE) },
                onRequestPermissions = { askForPermissions(true) },
            )
        }

        composable(Routes.LIVE) {
            val liveViewModel: LiveViewModel = viewModel(factory = factory)
            LiveScreen(
                viewModel = liveViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ROSTER) {
            RosterScreen(
                viewModel = rosterViewModel,
                onBack = { navController.popBackStack() },
                onOpenAttendee = { navController.navigate(Routes.attendeeDetail(it)) },
                onUpload = { navController.navigate(Routes.UPLOAD) },
            )
        }

        composable(
            route = Routes.ATTENDEE_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_ATTENDEE_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_ATTENDEE_ID).orEmpty()
            AttendeeDetailScreen(
                attendeeId = id,
                viewModel = rosterViewModel,
                onBack = { navController.popBackStack() },
                onEnroll = { navController.navigate(Routes.enroll(it)) },
            )
        }

        composable(
            route = Routes.ENROLL,
            arguments = listOf(navArgument(Routes.ARG_ATTENDEE_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_ATTENDEE_ID).orEmpty()
            EnrollScreen(
                attendeeId = id,
                viewModel = rosterViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.UPLOAD) {
            UploadScreen(
                viewModel = uploadViewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onViewPeople = {
                    navController.navigate(Routes.ROSTER) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.EARPIECE) {
            EarpieceScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onRequestBluetoothPermission = { askForPermissions(true) },
            )
        }

        composable(Routes.HELP) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun buildFactory(container: AppContainer): ViewModelProvider.Factory =
    SottoViewModelFactory(
        container = container,
        creators = mapOf(
            HomeViewModel::class.java to { c -> HomeViewModel(c) },
            LiveViewModel::class.java to { c -> LiveViewModel(c) },
            RosterViewModel::class.java to { c -> RosterViewModel(c) },
            UploadViewModel::class.java to { c -> UploadViewModel(c) },
            SettingsViewModel::class.java to { c -> SettingsViewModel(c) },
        ),
    )
