package me.magnum.melonds.ui.emulator

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.squareup.picasso.Picasso
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.common.PermissionHandler
import me.magnum.melonds.databinding.ActivityEmulatorBinding
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.DualScreenPreset
import me.magnum.melonds.domain.model.FpsCounterPosition
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.domain.model.layout.Insets
import me.magnum.melonds.domain.model.layout.LayoutComponent
import me.magnum.melonds.domain.model.layout.ScreenFold
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.ui.Orientation
import me.magnum.melonds.extensions.insetsControllerCompat
import me.magnum.melonds.extensions.setLayoutOrientation
import me.magnum.melonds.impl.emulator.LifecycleOwnerProvider
import me.magnum.melonds.impl.emulator.debug.RendererDebugBridge
import me.magnum.melonds.impl.layout.DeviceLayoutDisplayMapper
import me.magnum.melonds.impl.layout.SecondaryDisplaySelector
import me.magnum.melonds.impl.system.AppForegroundStateObserver
import me.magnum.melonds.parcelables.RomInfoParcelable
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.ui.cheats.CheatsActivity
import me.magnum.melonds.ui.common.rom.EmulatorLaunchValidatorDelegate
import me.magnum.melonds.ui.emulator.component.EmulatorOverlayTracker
import me.magnum.melonds.ui.emulator.input.ConnectedControllerManager
import me.magnum.melonds.ui.emulator.input.EmulatorRumbleManager
import me.magnum.melonds.ui.emulator.input.FrontendInputHandler
import me.magnum.melonds.ui.emulator.input.INativeInputListener
import me.magnum.melonds.ui.emulator.input.InputProcessor
import me.magnum.melonds.ui.emulator.input.MelonTouchHandler
import me.magnum.melonds.ui.emulator.model.EmulatorOverlay
import me.magnum.melonds.ui.emulator.model.EmulatorState
import me.magnum.melonds.ui.emulator.model.EmulatorUiEvent
import me.magnum.melonds.ui.emulator.model.HardcorePendingExitChoice
import me.magnum.melonds.ui.emulator.model.LaunchArgs
import me.magnum.melonds.ui.emulator.model.OfflineAchievementsSyncChoice
import me.magnum.melonds.ui.emulator.model.PauseMenu
import me.magnum.melonds.ui.emulator.model.RAEventUi
import me.magnum.melonds.ui.emulator.model.RumbleEvent
import me.magnum.melonds.ui.emulator.model.RuntimeInputLayoutConfiguration
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import me.magnum.melonds.ui.emulator.model.ToastEvent
import me.magnum.melonds.ui.emulator.model.RetroAchievementsLoadStage
import me.magnum.melonds.ui.emulator.model.VulkanCompileProgress
import me.magnum.melonds.ui.emulator.model.VulkanPresentationConfig
import me.magnum.melonds.ui.emulator.render.ChoreographerFrameRenderer
import me.magnum.melonds.ui.emulator.render.ChoreographerFrameRendererFactory
import me.magnum.melonds.ui.emulator.render.ExternalPresentation
import me.magnum.melonds.ui.emulator.render.FrameRenderCoordinator
import me.magnum.melonds.ui.emulator.render.OpenGlFrameRenderCoordinator
import me.magnum.melonds.ui.emulator.render.VulkanFrameRenderCoordinator
import me.magnum.melonds.ui.emulator.rewind.EdgeSpacingDecorator
import me.magnum.melonds.ui.emulator.rewind.RewindSaveStateAdapter
import me.magnum.melonds.ui.emulator.rewind.model.RewindWindow
import me.magnum.melonds.ui.emulator.rom.SaveStateAdapter
import me.magnum.melonds.ui.emulator.ui.AchievementListDialog
import me.magnum.melonds.ui.emulator.ui.AchievementUpdatesUi
import me.magnum.melonds.ui.emulator.ui.DualScreenPresetsDialog
import me.magnum.melonds.ui.emulator.ui.PendingSubmissionsDialog
import me.magnum.melonds.ui.layouteditor.model.LayoutTarget
import me.magnum.melonds.ui.settings.SettingsActivity
import me.magnum.melonds.ui.theme.MelonTheme
import java.text.SimpleDateFormat
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class EmulatorActivity : AppCompatActivity() {
    companion object {
        const val KEY_ROM = "rom"
        const val KEY_PATH = "PATH"
        const val KEY_URI = "uri"
        const val KEY_BOOT_FIRMWARE_CONSOLE = "boot_firmware_console"
        const val KEY_BOOT_FIRMWARE_ONLY = "boot_firmware_only"
        private const val STARTUP_PRESENTATION_REFRESH_ATTEMPTS = 24
        private const val STARTUP_PRESENTATION_REFRESH_INTERVAL_MS = 100L

        fun getRomEmulatorActivityIntent(context: Context, rom: Rom): Intent {
            return Intent(context, EmulatorActivity::class.java).apply {
                putExtra(KEY_ROM, RomParcelable(rom))
            }
        }

        fun getFirmwareEmulatorActivityIntent(context: Context, consoleType: ConsoleType): Intent {
            return Intent(context, EmulatorActivity::class.java).apply {
                putExtra(KEY_BOOT_FIRMWARE_ONLY, true)
                putExtra(KEY_BOOT_FIRMWARE_CONSOLE, consoleType.ordinal)
            }
        }
    }

    private lateinit var binding: ActivityEmulatorBinding
    private val viewModel: EmulatorViewModel by viewModels(
        extrasProducer = {
            val extras = MutableCreationExtras(defaultViewModelCreationExtras)
            // Inject intent data into view-model creation extras to make it accessible through the SavedStateHandle
            intent.data?.let { dataUri ->
                val existingExtras = extras[DEFAULT_ARGS_KEY]?.let { Bundle(it) } ?: Bundle()
                existingExtras.putString(KEY_URI, dataUri.toString())
                extras[DEFAULT_ARGS_KEY] = existingExtras
            }
            extras
        }
    )

    @Inject
    lateinit var secondaryDisplaySelector: SecondaryDisplaySelector

    @Inject
    lateinit var deviceLayoutDisplayMapper: DeviceLayoutDisplayMapper

    @Inject
    lateinit var picasso: Picasso

    @Inject
    lateinit var permissionHandler: PermissionHandler

    @Inject
    lateinit var lifecycleOwnerProvider: LifecycleOwnerProvider

    @Inject
    lateinit var appForegroundStateObserver: AppForegroundStateObserver

    private var presentation: ExternalPresentation? = null

    private lateinit var handler: Handler
    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            runOnUiThread {
                updateDisplays()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            runOnUiThread {
                updateDisplays()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            updateDisplays()
        }
    }

    private val connectedControllerManager = ConnectedControllerManager()
    private lateinit var emulatorLaunchValidatorDelegate: EmulatorLaunchValidatorDelegate
    private lateinit var emulatorRumbleManager: EmulatorRumbleManager
    private lateinit var frameRenderCoordinator: FrameRenderCoordinator
    private lateinit var choreographerFrameRenderer: ChoreographerFrameRenderer
    private lateinit var mainScreenRenderer: DSRenderer
    private lateinit var melonTouchHandler: MelonTouchHandler
    private lateinit var nativeInputListener: INativeInputListener
    private var currentRuntimeRendererConfiguration: RuntimeRendererConfiguration? = null
    private var currentMainScreenBackground = me.magnum.melonds.domain.model.RuntimeBackground.None
    private var currentPresentationBackend = PresentationBackend.OPEN_GL
    private var startupPresentationRefreshRunnable: Runnable? = null
    private var startupPresentationRefreshAttempts = 0
    private var rendererDebugPauseEmulation = true
    private val frontendInputHandler = object : FrontendInputHandler() {
        var fastForwardEnabled = false
            private set
        var microphoneEnabled = true
            private set

        override fun onSoftInputTogglePressed() {
            binding.viewLayoutControls.toggleSoftInputVisibility()
            presentation?.layoutView?.toggleSoftInputVisibility()
        }

        override fun onPausePressed() {
            viewModel.pauseEmulator(true)
        }

        override fun onFastForwardPressed() {
            if (!viewModel.onFastForwardToggleRequested()) {
                return
            }
            fastForwardEnabled = !fastForwardEnabled
            binding.viewLayoutControls.setLayoutComponentToggleState(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE, fastForwardEnabled)
            presentation?.layoutView?.setLayoutComponentToggleState(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE, fastForwardEnabled)
            MelonEmulator.setFastForwardEnabled(fastForwardEnabled)
        }

        override fun onMicrophonePressed() {
            microphoneEnabled = !microphoneEnabled
            binding.viewLayoutControls.setLayoutComponentToggleState(LayoutComponent.BUTTON_MICROPHONE_TOGGLE, microphoneEnabled)
            presentation?.layoutView?.setLayoutComponentToggleState(LayoutComponent.BUTTON_MICROPHONE_TOGGLE, microphoneEnabled)
            MelonEmulator.setMicrophoneEnabled(microphoneEnabled)
        }

        override fun onResetPressed() {
            viewModel.resetEmulator()
        }

        override fun onSwapScreens() {
            swapScreen()
        }

        override fun onQuickSave() {
            viewModel.doQuickSave()
        }

        override fun onQuickLoad() {
            viewModel.doQuickLoad()
        }

        override fun onRewind() {
            viewModel.onOpenRewind()
        }
    }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onSettingsChanged()
        setupSustainedPerformanceMode()
        setupFpsCounter()
        viewModel.resumeEmulator()
    }
    private val cheatsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onCheatsChanged()
        viewModel.resumeEmulator()
    }
    private val permissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch {
            it.keys.forEach { permission ->
                permissionHandler.notifyPermissionStatusUpdated(permission)
            }
        }
    }
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleBackPressed()
        }
    }

    private var offlineSyncChoiceDialog: AlertDialog? = null
    private var offlineSyncProgressDialog: AlertDialog? = null
    private var hardcorePendingExitDialog: AlertDialog? = null

    private enum class PresentationBackend {
        OPEN_GL,
        VULKAN,
    }

    private val rewindSaveStateAdapter = RewindSaveStateAdapter {
        viewModel.rewindToState(it)
        closeRewindWindow()
    }
    private val showAchievementList = mutableStateOf(false)
    private val showPendingSubmissionsDialog = mutableStateOf(false)
    private val showDualScreenPresets = mutableStateOf(false)

    private val activeOverlays = EmulatorOverlayTracker(
        onOverlaysCleared = {
            disableScreenTimeOut()
            presentation?.setPauseOverlayVisibility(false)
        },
        onOverlaysPresent = {
            enableScreenTimeOut()
            presentation?.setPauseOverlayVisibility(true)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(mainLooper)
        lifecycleOwnerProvider.setCurrentLifecycleOwner(this)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setupFullscreen()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.listRewind.setPadding(insets.left, 0, insets.right, insets.bottom)
            binding.textFps.updateLayoutParams<ConstraintLayout.LayoutParams> {
                setMargins(insets.left, insets.top, insets.right, insets.bottom)
            }

            val uiInsets = Insets(insets.left, insets.top, insets.right, insets.bottom)
            viewModel.setUiInsets(uiInsets)

            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(backPressedCallback)

        emulatorLaunchValidatorDelegate = EmulatorLaunchValidatorDelegate(this, object : EmulatorLaunchValidatorDelegate.Callback {
            override fun onRomValidated(rom: Rom) {
                viewModel.onRomLaunchValidated(rom)
            }

            override fun onFirmwareValidated(consoleType: ConsoleType) {
                viewModel.onFirmwareLaunchValidated(consoleType)
            }

            override fun onValidationAborted() {
                finish()
            }
        })
        emulatorRumbleManager = EmulatorRumbleManager(this, lifecycleScope, connectedControllerManager)
        currentPresentationBackend = viewModel.getConfiguredVideoRenderer().toPresentationBackend()
        frameRenderCoordinator = createFrameRenderCoordinator(currentPresentationBackend)
        choreographerFrameRenderer = ChoreographerFrameRendererFactory.createFrameRenderer(frameRenderCoordinator)
        melonTouchHandler = MelonTouchHandler()
        mainScreenRenderer = DSRenderer(this)
        binding.surfaceMain.apply {
            setRenderer(mainScreenRenderer)
        }

        binding.textFps.visibility = View.INVISIBLE
        binding.viewLayoutControls.setLayoutComponentViewBuilderFactory(RuntimeLayoutComponentViewBuilderFactory())
        binding.layoutRewind.setOnClickListener {
            closeRewindWindow()
        }
        binding.listRewind.apply {
            val listLayoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
            layoutManager = listLayoutManager
            addItemDecoration(EdgeSpacingDecorator())
            adapter = rewindSaveStateAdapter
        }
        binding.viewLayoutControls.apply {
            setFrontendInputHandler(frontendInputHandler)
            setSystemInputHandler(melonTouchHandler)
        }

        val layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldWith = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop

            val newWidth = right - left
            val newHeight = bottom - top

            if (newWidth != oldWith || newHeight != oldHeight) {
                updateRendererScreenAreas()
                viewModel.setUiSize(newWidth, newHeight)
            }
        }
        binding.viewLayoutControls.addOnLayoutChangeListener(layoutChangeListener)

        updateOrientation(resources.configuration)
        disableScreenTimeOut()

        binding.layoutAchievement.setContent {
            MelonTheme {
                val achievementsViewModel = viewModels<EmulatorRetroAchievementsViewModel>().value

                LaunchedEffect(Unit) {
                    viewModel.achievementsEvent.filterIsInstance<RAEventUi.Reset>().collect {
                        achievementsViewModel.onSessionReset()
                    }
                }

                AchievementUpdatesUi(viewModel)

                if (showAchievementList.value) {
                    AchievementListDialog(
                        viewModel = achievementsViewModel,
                        onDismiss = {
                            activeOverlays.removeActiveOverlay(EmulatorOverlay.ACHIEVEMENTS_DIALOG)
                            viewModel.resumeEmulator()
                            showAchievementList.value = false
                        }
                    )
                }

                if (showPendingSubmissionsDialog.value) {
                    PendingSubmissionsDialog(
                        pendingSubmissionsSummaryFlow = viewModel.pendingSubmissionsSummary,
                        onExit = {
                            activeOverlays.removeActiveOverlay(EmulatorOverlay.PENDING_SUBMISSION_CONFIRM_EXIT)
                            showPendingSubmissionsDialog.value = false
                            viewModel.exitEmulator(force = true)
                        },
                        onCancel = {
                            activeOverlays.removeActiveOverlay(EmulatorOverlay.PENDING_SUBMISSION_CONFIRM_EXIT)
                            viewModel.resumeEmulator()
                            showPendingSubmissionsDialog.value = false
                        },
                    )
                }

                if (showDualScreenPresets.value) {
                    val preset by viewModel.dualScreenPreset.collectAsState()
                    val keepAspectRatio by viewModel.externalDisplayKeepAspectRatioEnabled.collectAsState()
                    val integerScaleEnabled by viewModel.dualScreenIntegerScaleEnabled.collectAsState()
                    val internalFillHeight by viewModel.dualScreenInternalFillHeightEnabled.collectAsState()
                    val internalFillWidth by viewModel.dualScreenInternalFillWidthEnabled.collectAsState()
                    val externalFillHeight by viewModel.dualScreenExternalFillHeightEnabled.collectAsState()
                    val externalFillWidth by viewModel.dualScreenExternalFillWidthEnabled.collectAsState()
                    val internalAlignmentOverride by viewModel.dualScreenInternalVerticalAlignmentOverride.collectAsState()
                    val externalAlignmentOverride by viewModel.dualScreenExternalVerticalAlignmentOverride.collectAsState()

                    DualScreenPresetsDialog(
                        dualScreenPreset = preset,
                        onDualScreenPresetSelected = { selectedPreset ->
                            viewModel.setDualScreenPreset(selectedPreset)
                            handler.post {
                                applyDualScreenPresetSwapState(selectedPreset)
                                updateRendererScreenAreas()
                                presentation?.updateRendererScreenAreas()
                            }
                        },
                        keepAspectRatio = keepAspectRatio,
                        onKeepAspectRatioChanged = { enabled ->
                            viewModel.setExternalDisplayKeepAspectRatioEnabled(enabled)
                        },
                        isDualScreenIntegerScaleEnabled = integerScaleEnabled,
                        onDualScreenIntegerScaleChanged = { enabled ->
                            viewModel.setDualScreenIntegerScaleEnabled(enabled)
                        },
                        internalFillHeight = internalFillHeight,
                        onInternalFillHeightChanged = { enabled ->
                            viewModel.setDualScreenInternalFillHeightEnabled(enabled)
                        },
                        internalFillWidth = internalFillWidth,
                        onInternalFillWidthChanged = { enabled ->
                            viewModel.setDualScreenInternalFillWidthEnabled(enabled)
                        },
                        externalFillHeight = externalFillHeight,
                        onExternalFillHeightChanged = { enabled ->
                            viewModel.setDualScreenExternalFillHeightEnabled(enabled)
                        },
                        externalFillWidth = externalFillWidth,
                        onExternalFillWidthChanged = { enabled ->
                            viewModel.setDualScreenExternalFillWidthEnabled(enabled)
                        },
                        internalVerticalAlignmentOverride = internalAlignmentOverride,
                        onInternalVerticalAlignmentOverrideChanged = { alignment ->
                            viewModel.setDualScreenInternalVerticalAlignmentOverride(alignment)
                        },
                        externalVerticalAlignmentOverride = externalAlignmentOverride,
                        onExternalVerticalAlignmentOverrideChanged = { alignment ->
                            viewModel.setDualScreenExternalVerticalAlignmentOverride(alignment)
                        },
                        onDismiss = {
                            activeOverlays.removeActiveOverlay(EmulatorOverlay.PRESETS_DIALOG)
                            viewModel.resumeEmulator()
                            showDualScreenPresets.value = false
                        },
                    )
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionHandler.observePermissionRequests().collect {
                    permissionRequestLauncher.launch(arrayOf(it))
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.runtimeLayout.collectLatest {
                    setupSoftInput(it)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.controllerConfiguration.collect {
                    setupInputHandling(it)
                    connectedControllerManager.setCurrentControllerConfiguration(it)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectedControllerManager.controllersState.collect {
                    binding.viewLayoutControls.setConnectedControllersState(it)
                    presentation?.layoutView?.setConnectedControllersState(it)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mainScreenBackground.collectLatest {
                    currentMainScreenBackground = it
                    mainScreenRenderer.setBackground(it)
                    updateRendererScreenAreas()
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.secondaryScreenBackground.collectLatest {
                    presentation?.updateBackground(it)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.runtimeRendererConfiguration.collectLatest {
                    currentRuntimeRendererConfiguration = it
                    ensurePresentationBackend(it?.renderer ?: viewModel.getConfiguredVideoRenderer())
                    mainScreenRenderer.updateRendererConfiguration(it)
                    presentation?.updateRendererConfiguration(it)
                    updateRendererScreenAreas()
                    scheduleStartupPresentationRefreshes()
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentFps.collectLatest {
                    if (it == null) {
                        binding.textFps.text = null
                    } else {
                        binding.textFps.text = getString(R.string.info_fps, it)
                    }
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collectLatest {
                    val (message, duration) = when (it) {
                        ToastEvent.GbaLoadFailed -> getString(R.string.error_load_gba_rom) to Toast.LENGTH_SHORT
                        ToastEvent.QuickSaveSuccessful -> getString(R.string.saved) to Toast.LENGTH_SHORT
                        ToastEvent.QuickLoadSuccessful -> getString(R.string.loaded) to Toast.LENGTH_SHORT
                        ToastEvent.RewindNotEnabled -> getString(R.string.rewind_not_enabled) to Toast.LENGTH_SHORT
                        ToastEvent.RewindNotAvailableWhileRAHardcoreModeEnabled -> getString(R.string.rewind_unavailable_ra_hardcore_enabled) to Toast.LENGTH_LONG
                        ToastEvent.StateLoadFailed -> getString(R.string.failed_load_state) to Toast.LENGTH_SHORT
                        ToastEvent.StateSaveFailed -> getString(R.string.failed_save_state) to Toast.LENGTH_SHORT
                        ToastEvent.StateStateDoesNotExist -> getString(R.string.cant_load_empty_slot) to Toast.LENGTH_SHORT
                        ToastEvent.CannotLoadSaveStatesWhenRAHardcoreIsEnabled -> getString(R.string.load_states_unavailable_ra_hardcore_enabled) to Toast.LENGTH_LONG
                        ToastEvent.CannotUseCheatsWhenRAHardcoreIsEnabled -> getString(R.string.cheats_unavailable_ra_hardcore_enabled) to Toast.LENGTH_LONG
                        ToastEvent.CannotLoadStateWhenRunningFirmware,
                        ToastEvent.CannotSaveStateWhenRunningFirmware -> getString(R.string.save_states_not_supported) to Toast.LENGTH_LONG
                        ToastEvent.CannotSwitchRetroAchievementsMode -> getString(R.string.retro_achievements_relaunch_to_apply_settings) to Toast.LENGTH_LONG
                        ToastEvent.GbaModeNotSupported -> getString(R.string.emulator_stop_gba_mode_unsupported) to Toast.LENGTH_SHORT
                        ToastEvent.InternalError -> getString(R.string.emulator_stop_internal_error) to Toast.LENGTH_LONG
                        ToastEvent.OfflineAchievementsLedgerTampered -> getString(R.string.offline_ra_ledger_tampered_toast) to Toast.LENGTH_LONG
                        ToastEvent.OfflineAchievementsSyncFailed -> getString(R.string.offline_ra_sync_failed_toast) to Toast.LENGTH_LONG
                        is ToastEvent.HardcoreOfflineUnsyncedWarning -> {
                            getString(R.string.offline_ra_hardcore_unsynced_warning_toast, it.pendingHardcoreCount) to Toast.LENGTH_LONG
                        }
                        is ToastEvent.HardcoreQueueSyncResult -> {
                            val message = when {
                                it.remainingCount == 0 -> getString(R.string.offline_ra_hardcore_sync_result_all, it.submittedCount)
                                it.submittedCount == 0 -> getString(R.string.offline_ra_hardcore_sync_result_none, it.remainingCount)
                                else -> getString(R.string.offline_ra_hardcore_sync_result_partial, it.submittedCount, it.remainingCount)
                            }
                            message to Toast.LENGTH_LONG
                        }
                        is ToastEvent.RetroAchievementsMode -> {
                            val message = when (it.status) {
                                ToastEvent.RetroAchievementsModeStatus.SOFTCORE -> {
                                    getString(R.string.offline_ra_mode_softcore)
                                }
                                ToastEvent.RetroAchievementsModeStatus.HARDCORE -> {
                                    getString(R.string.offline_ra_mode_hardcore)
                                }
                                ToastEvent.RetroAchievementsModeStatus.SOFTCORE_OFFLINE -> {
                                    if (it.offlineNoInternetAtStart) {
                                        getString(R.string.offline_ra_mode_softcore_offline_no_internet_start)
                                    } else {
                                        getString(R.string.offline_ra_mode_softcore_offline)
                                    }
                                }
                            }
                            message to Toast.LENGTH_LONG
                        }
                        is ToastEvent.OfflineSoftcorePendingNotice -> {
                            getString(R.string.offline_ra_pending_softcore_notice, it.pendingSoftcoreCount) to Toast.LENGTH_LONG
                        }
                        is ToastEvent.OfflineAchievementNotSynced -> {
                            val messageRes = when (it.reason) {
                                ToastEvent.OfflineAchievementNotSyncedReason.MISSING_FROM_CURRENT_SET -> R.string.offline_ra_sync_skipped_missing_toast
                                ToastEvent.OfflineAchievementNotSyncedReason.DEFINITION_CHANGED -> R.string.offline_ra_sync_skipped_definition_changed_toast
                                ToastEvent.OfflineAchievementNotSyncedReason.NOT_IN_PREFETCH_CACHE -> R.string.offline_ra_sync_skipped_cache_mismatch_toast
                            }
                            getString(messageRes, it.title) to Toast.LENGTH_LONG
                        }
                        is ToastEvent.OfflineAchievementsNotSyncedSummary -> {
                            getString(R.string.offline_ra_sync_skipped_summary_toast, it.skippedCount) to Toast.LENGTH_LONG
                        }
                        is ToastEvent.RendererInitFailed -> {
                            val rendererLabel = when (it.renderer) {
                                VideoRenderer.SOFTWARE -> "Software"
                                VideoRenderer.OPENGL -> "OpenGL"
                                VideoRenderer.VULKAN -> "Vulkan"
                            }
                            getString(R.string.renderer_init_failed_message, rendererLabel) to Toast.LENGTH_LONG
                        }
                        is ToastEvent.RendererDebugCaptureLogged -> {
                            getString(R.string.renderer_debug_capture_logged, it.captureId) to Toast.LENGTH_LONG
                        }
                        ToastEvent.RendererDebugCaptureFailed -> {
                            getString(R.string.renderer_debug_capture_failed) to Toast.LENGTH_LONG
                        }
                    }

                    Toast.makeText(this@EmulatorActivity, message, duration).show()
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.uiEvent.collectLatest {
                    when (it) {
                        EmulatorUiEvent.CloseEmulator -> {
                            choreographerFrameRenderer.stopRendering()
                            presentation?.apply {
                                dismiss()
                            }
                            finish()
                        }
                        is EmulatorUiEvent.OpenScreen.CheatsScreen -> {
                            val intent = Intent(this@EmulatorActivity, CheatsActivity::class.java)
                            intent.putExtra(CheatsActivity.KEY_ROM_INFO, RomInfoParcelable.fromRomInfo(it.romInfo))
                            cheatsLauncher.launch(intent)
                        }
                        EmulatorUiEvent.OpenScreen.SettingsScreen -> {
                            val settingsIntent = Intent(this@EmulatorActivity, SettingsActivity::class.java)
                            settingsLauncher.launch(settingsIntent)
                        }
                        is EmulatorUiEvent.ShowPauseMenu -> showPauseMenu(it.pauseMenu)
                        is EmulatorUiEvent.ShowRewindWindow -> showRewindWindow(it.rewindWindow)
                        is EmulatorUiEvent.ShowRomSaveStates -> {
                            showSaveStateSlotsDialog(it.saveStates) { slot ->
                                if (it.reason == EmulatorUiEvent.ShowRomSaveStates.Reason.SAVING) {
                                    viewModel.saveStateToSlot(slot)
                                } else {
                                    viewModel.loadStateFromSlot(slot)
                                }
                            }
                        }
                        EmulatorUiEvent.ShowAchievementList -> {
                            activeOverlays.addActiveOverlay(EmulatorOverlay.ACHIEVEMENTS_DIALOG)
                            showAchievementList.value = true
                        }
                        EmulatorUiEvent.ShowPendingSubmissionsDialog -> {
                            activeOverlays.addActiveOverlay(EmulatorOverlay.PENDING_SUBMISSION_CONFIRM_EXIT)
                            showPendingSubmissionsDialog.value = true
                        }
                        EmulatorUiEvent.ShowDualScreenPresets -> {
                            activeOverlays.addActiveOverlay(EmulatorOverlay.PRESETS_DIALOG)
                            showDualScreenPresets.value = true
                        }
                        EmulatorUiEvent.ShowRendererDebugMenu -> showRendererDebugMenu()
                        EmulatorUiEvent.ShowRenderer2DDebugControls -> {
                            if (isDebuggableBuild()) {
                                showRenderer2DDebugControlsDialog()
                            }
                        }
                        is EmulatorUiEvent.ShowOfflineAchievementsSyncChoice -> {
                            showOfflineAchievementsSyncChoiceDialog(it.pendingUnlockCount)
                        }
                        is EmulatorUiEvent.ShowHardcorePendingExitWarning -> {
                            showHardcorePendingExitWarningDialog(it.pendingHardcoreCount)
                        }
                        is EmulatorUiEvent.ShowOfflineAchievementsSyncProgress -> {
                            showOfflineAchievementsSyncProgressDialog(it.totalUnlockCount)
                        }
                        EmulatorUiEvent.HideOfflineAchievementsSyncProgress -> {
                            offlineSyncProgressDialog?.dismiss()
                            offlineSyncProgressDialog = null
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rumbleEvent.collect {
                    when (it) {
                        is RumbleEvent.RumbleStart -> emulatorRumbleManager.startRumbling()
                        RumbleEvent.RumbleStop -> emulatorRumbleManager.stopRumbling()
                    }
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.emulatorState.collectLatest {
                    when (it) {
                        is EmulatorState.Uninitialized -> {
                            binding.viewLayoutControls.isInvisible = true
                            binding.textFps.isGone = true
                            binding.textLoading.isGone = true
                            binding.progressLoading.isGone = true
                            binding.textLoadingDetail.isGone = true
                        }
                        is EmulatorState.ValidatingFirmware -> {
                            showLoadingState()
                            emulatorLaunchValidatorDelegate.validateFirmware(it.consoleType)
                        }
                        is EmulatorState.ValidatingRom -> {
                            showLoadingState()
                            emulatorLaunchValidatorDelegate.validateRom(it.rom)
                        }
                        is EmulatorState.LoadingFirmware,
                        is EmulatorState.LoadingRom -> {
                            showLoadingState()
                            val compileProgress = when (it) {
                                is EmulatorState.LoadingRom -> it.vulkanCompileProgress
                                is EmulatorState.LoadingFirmware -> it.vulkanCompileProgress
                            }
                            val raLoadStage = (it as? EmulatorState.LoadingRom)?.retroAchievementsLoadStage
                            renderLoadingState(compileProgress, raLoadStage)
                        }
                        is EmulatorState.RunningRom,
                        is EmulatorState.RunningFirmware -> {
                            setupSustainedPerformanceMode()
                            setupFpsCounter()
                            binding.textLoading.isGone = true
                            binding.progressLoading.isGone = true
                            binding.textLoadingDetail.isGone = true
                            binding.viewLayoutControls.isVisible = true
                            backPressedCallback.isEnabled = true
                            scheduleStartupPresentationRefreshes()
                            if (!activeOverlays.hasActiveOverlays()) {
                                viewModel.resumeEmulator()
                            }
                        }
                        is EmulatorState.RomLoadError -> {
                            binding.viewLayoutControls.isInvisible = true
                            binding.textFps.isGone = true
                            binding.textLoading.isGone = true
                            binding.progressLoading.isGone = true
                            binding.textLoadingDetail.isGone = true
                            showRomLoadErrorDialog()
                        }
                        is EmulatorState.FirmwareLoadError -> {
                            binding.viewLayoutControls.isInvisible = true
                            binding.textFps.isGone = true
                            binding.textLoading.isGone = true
                            binding.progressLoading.isGone = true
                            binding.textLoadingDetail.isGone = true
                            showFirmwareLoadErrorDialog(it)
                        }
                        is EmulatorState.RomNotFoundError -> {
                            binding.viewLayoutControls.isInvisible = true
                            binding.textFps.isGone = true
                            binding.textLoading.isGone = true
                            binding.progressLoading.isGone = true
                            binding.textLoadingDetail.isGone = true
                            showRomNotFoundDialog(it.romPath)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@EmulatorActivity).windowLayoutInfo(this@EmulatorActivity).collect {
                    val folds = it.displayFeatures.mapNotNull {
                        if (it is FoldingFeature) {
                            ScreenFold(
                                orientation = if (it.orientation == FoldingFeature.Orientation.HORIZONTAL) Orientation.LANDSCAPE else Orientation.PORTRAIT,
                                type = if (it.isSeparating) ScreenFold.FoldType.SEAMLESS else ScreenFold.FoldType.GAP,
                                foldBounds = Rect(it.bounds.left, it.bounds.top, it.bounds.width(), it.bounds.height())
                            )
                        } else {
                            null
                        }
                    }
                    viewModel.setScreenFolds(folds)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                appForegroundStateObserver.onAppMovedToBackgroundEvent.collect {
                    presentation?.dismiss()
                    presentation = null
                    viewModel.onAppMovedToBackground()
                }
            }
        }
    }

    private fun showLoadingState() {
        binding.viewLayoutControls.isInvisible = true
        binding.textFps.isGone = true
        binding.textLoading.isVisible = true
    }

    private fun renderLoadingState(progress: VulkanCompileProgress?, raLoadStage: RetroAchievementsLoadStage? = null) {
        if (raLoadStage == RetroAchievementsLoadStage.FETCHING_LATEST_DATA) {
            binding.textLoading.setText(R.string.info_refreshing_retroachievements_title)
            binding.progressLoading.isVisible = true
            binding.progressLoading.isIndeterminate = true
            binding.textLoadingDetail.isVisible = true
            binding.textLoadingDetail.setText(R.string.info_refreshing_retroachievements_detail)
            return
        }

        if (progress == null || progress.total <= 0) {
            binding.textLoading.setText(R.string.info_loading)
            binding.progressLoading.isVisible = true
            binding.progressLoading.isIndeterminate = true
            binding.textLoadingDetail.isGone = true
            return
        }

        binding.textLoading.setText(
            if (progress.stageId == 5) {
                R.string.info_retroarch_compiling_title
            } else {
                R.string.info_vulkan_compiling_title
            },
        )
        binding.progressLoading.isVisible = true
        binding.progressLoading.isIndeterminate = true
        binding.textLoadingDetail.isVisible = true
        binding.textLoadingDetail.text = getVulkanCompileStageLabel(progress.stageId)
    }

    private fun getVulkanCompileStageLabel(stageId: Int): String {
        val labelRes = when (stageId) {
            1 -> R.string.info_vulkan_compiling_stage_init
            2 -> R.string.info_vulkan_compiling_stage_pipelines
            3 -> R.string.info_vulkan_compiling_stage_output
            4 -> R.string.info_vulkan_compiling_stage_warmup
            5 -> R.string.info_vulkan_compiling_stage_retroarch
            else -> R.string.info_vulkan_compiling_stage_init
        }
        return getString(labelRes)
    }

    private fun showOfflineAchievementsSyncChoiceDialog(pendingUnlockCount: Int) {
        offlineSyncChoiceDialog?.dismiss()
        offlineSyncChoiceDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.offline_ra_pending_title))
            .setMessage(getString(R.string.offline_ra_pending_message, pendingUnlockCount))
            .setCancelable(false)
            .setPositiveButton(R.string.offline_ra_sync_now) { _, _ ->
                viewModel.submitOfflineAchievementsSyncChoice(OfflineAchievementsSyncChoice.SYNC_NOW)
            }
            .setNegativeButton(R.string.offline_ra_continue_offline) { _, _ ->
                viewModel.submitOfflineAchievementsSyncChoice(OfflineAchievementsSyncChoice.CONTINUE_OFFLINE)
            }
            .show()
    }

    private fun showHardcorePendingExitWarningDialog(pendingHardcoreCount: Int) {
        hardcorePendingExitDialog?.dismiss()
        hardcorePendingExitDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.offline_ra_hardcore_pending_exit_title))
            .setMessage(getString(R.string.offline_ra_hardcore_pending_exit_message, pendingHardcoreCount))
            .setCancelable(false)
            .setPositiveButton(R.string.offline_ra_hardcore_try_sync_button) { _, _ ->
                viewModel.submitHardcorePendingExitChoice(HardcorePendingExitChoice.TRY_SYNC_NOW)
            }
            .setNegativeButton(R.string.offline_ra_hardcore_discard_exit_button) { _, _ ->
                viewModel.submitHardcorePendingExitChoice(HardcorePendingExitChoice.DISCARD_AND_EXIT)
            }
            .show()
    }

    private fun showOfflineAchievementsSyncProgressDialog(totalUnlockCount: Int) {
        offlineSyncProgressDialog?.dismiss()
        offlineSyncProgressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.offline_ra_syncing_title))
            .setMessage(getString(R.string.offline_ra_syncing_message, totalUnlockCount))
            .setCancelable(false)
            .create()

        offlineSyncProgressDialog?.show()
    }

    override fun onStart() {
        super.onStart()
        updateDisplays()
        getSystemService<DisplayManager>()?.registerDisplayListener(displayListener, null)
        getSystemService<InputManager>()?.registerInputDeviceListener(connectedControllerManager, null)
        connectedControllerManager.startTrackingControllers()
        frameRenderCoordinator.addSurface(binding.surfaceMain)
    }

    private fun updateDisplays() {
        val currentDisplay = ContextCompat.getDisplayOrDefault(this)
        val secondaryDisplay = secondaryDisplaySelector.getSecondaryDisplay(this)

        val displays = deviceLayoutDisplayMapper.mapDisplaysToLayoutDisplays(currentDisplay, secondaryDisplay)
        viewModel.setConnectedDisplays(displays)

        showExternalDisplay(secondaryDisplay)
    }

    private fun showExternalDisplay(secondaryDisplay: Display?) {
        if (presentation?.display?.displayId == secondaryDisplay?.displayId) {
            return
        }

        presentation?.dismiss()
        presentation = null

        if (secondaryDisplay != null) {
            presentation = ExternalPresentation(
                context = this,
                display = secondaryDisplay,
                frameRenderCoordinator = frameRenderCoordinator,
            ).apply {
                layoutView.apply {
                    setLayoutComponentViewBuilderFactory(RuntimeLayoutComponentViewBuilderFactory())
                    setFrontendInputHandler(frontendInputHandler)
                    setSystemInputHandler(melonTouchHandler)
                    viewModel.runtimeLayout.value?.let {
                        updateLayout(it)
                    }

                    setLayoutComponentToggleState(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE, frontendInputHandler.fastForwardEnabled)
                    setLayoutComponentToggleState(LayoutComponent.BUTTON_MICROPHONE_TOGGLE, frontendInputHandler.microphoneEnabled)
                    setConnectedControllersState(connectedControllerManager.controllersState.value)
                }

                updateRendererConfiguration(viewModel.runtimeRendererConfiguration.value)
                updateBackground(viewModel.secondaryScreenBackground.value)
                if (binding.viewLayoutControls.areScreensSwapped()) {
                    swapScreens()
                }
                if (activeOverlays.hasActiveOverlays()) {
                    setPauseOverlayVisibility(true)
                }

                show()
            }
            scheduleStartupPresentationRefreshes()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val launchArgs = LaunchArgs.fromIntent(intent)
        // Invalid arguments. Ignore completely
        if (launchArgs == null)
            return

        if (viewModel.emulatorState.value.isRunning()) {
            viewModel.pauseEmulator(false)

            activeOverlays.addActiveOverlay(EmulatorOverlay.SWITCH_NEW_ROM_DIALOG)
            AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_emulator_running))
                    .setMessage(getString(R.string.message_stop_emulation))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        setIntent(intent)
                        viewModel.relaunchWithNewArgs(launchArgs)
                    }
                    .setNegativeButton(R.string.no) { dialog, _ ->
                        dialog.cancel()
                    }
                    .setOnDismissListener {
                        activeOverlays.removeActiveOverlay(EmulatorOverlay.SWITCH_NEW_ROM_DIALOG)
                    }
                    .setOnCancelListener {
                        viewModel.resumeEmulator()
                    }
                    .show()
        }
    }

    override fun onResume() {
        super.onResume()
        choreographerFrameRenderer.startRendering()

        if (!activeOverlays.hasActiveOverlays()) {
            disableScreenTimeOut()
            viewModel.resumeEmulator()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setupFullscreen()
    }

    private fun setupFullscreen() {
        window.insetsControllerCompat?.let {
            it.hide(WindowInsetsCompat.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupSustainedPerformanceMode() {
        window.setSustainedPerformanceMode(viewModel.isSustainedPerformanceModeEnabled())
    }

    private fun setupFpsCounter() {
        val fpsCounterPosition = viewModel.getFpsCounterPosition()
        if (fpsCounterPosition == FpsCounterPosition.HIDDEN) {
            binding.textFps.isGone = true
        } else {
            binding.textFps.isVisible = true
            val newParams = binding.textFps.layoutParams as ConstraintLayout.LayoutParams
            when (fpsCounterPosition) {
                FpsCounterPosition.TOP_LEFT -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.TOP_CENTER -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.TOP_RIGHT -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_LEFT -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_CENTER -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_RIGHT -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.HIDDEN -> { /* Do nothing here */ }
            }
            binding.textFps.layoutParams = newParams
        }
    }

    private fun setupSoftInput(layoutConfiguration: RuntimeInputLayoutConfiguration?) {
        if (layoutConfiguration != null) {
            setLayoutOrientation(layoutConfiguration.layoutOrientation)
            with(binding.viewLayoutControls) {
                instantiateLayout(layoutConfiguration, LayoutTarget.MAIN_SCREEN)
                setLayoutComponentToggleState(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE, frontendInputHandler.fastForwardEnabled)
                setLayoutComponentToggleState(LayoutComponent.BUTTON_MICROPHONE_TOGGLE, frontendInputHandler.microphoneEnabled)
            }
            presentation?.apply {
                updateLayout(layoutConfiguration)
                layoutView.setLayoutComponentToggleState(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE, frontendInputHandler.fastForwardEnabled)
                layoutView.setLayoutComponentToggleState(LayoutComponent.BUTTON_MICROPHONE_TOGGLE, frontendInputHandler.microphoneEnabled)
            }

            handler.post {
                applyDualScreenPresetSwapState()
                updateRendererScreenAreas()
                presentation?.updateRendererScreenAreas()
                scheduleStartupPresentationRefreshes()
            }
        } else {
            binding.viewLayoutControls.destroyLayout()
            presentation?.layoutView?.destroyLayout()
        }
    }

    private fun applyDualScreenPresetSwapState(preset: DualScreenPreset = viewModel.dualScreenPreset.value) {
        if (preset == DualScreenPreset.OFF) {
            return
        }

        val desiredInternalScreen = when (preset) {
            DualScreenPreset.INTERNAL_TOP_EXTERNAL_BOTTOM -> LayoutComponent.TOP_SCREEN
            DualScreenPreset.INTERNAL_BOTTOM_EXTERNAL_TOP -> LayoutComponent.BOTTOM_SCREEN
            DualScreenPreset.OFF -> return
        }

        val baselineInternalScreen = getBaselineInternalScreenComponent() ?: return
        val shouldSwap = baselineInternalScreen != desiredInternalScreen

        if (binding.viewLayoutControls.areScreensSwapped() != shouldSwap) {
            binding.viewLayoutControls.swapScreens()
        }
        presentation?.layoutView?.let { layoutView ->
            if (layoutView.areScreensSwapped() != shouldSwap) {
                layoutView.swapScreens()
            }
        }
    }

    private fun getBaselineInternalScreenComponent(): LayoutComponent? {
        val hasTop = binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.TOP_SCREEN) != null
        val hasBottom = binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BOTTOM_SCREEN) != null
        return when {
            hasTop && !hasBottom -> LayoutComponent.TOP_SCREEN
            hasBottom && !hasTop -> LayoutComponent.BOTTOM_SCREEN
            hasBottom -> LayoutComponent.BOTTOM_SCREEN
            hasTop -> LayoutComponent.TOP_SCREEN
            else -> null
        }
    }

    private fun swapScreen() {
        binding.viewLayoutControls.swapScreens()
        presentation?.swapScreens()

        updateRendererScreenAreas()
        scheduleStartupPresentationRefreshes()
    }

    private fun updateRendererScreenAreas() {
        val (topScreen, bottomScreen) = if (binding.viewLayoutControls.areScreensSwapped()) {
            LayoutComponent.BOTTOM_SCREEN to LayoutComponent.TOP_SCREEN
        } else {
            LayoutComponent.TOP_SCREEN to LayoutComponent.BOTTOM_SCREEN
        }
        val topView = binding.viewLayoutControls.getLayoutComponentView(topScreen)
        val bottomView = binding.viewLayoutControls.getLayoutComponentView(bottomScreen)
        mainScreenRenderer.updateScreenAreas(
            topView?.getRect(),
            bottomView?.getRect(),
            topView?.baseAlpha ?: 1f,
            bottomView?.baseAlpha ?: 1f,
            topView?.onTop ?: false,
            bottomView?.onTop ?: false,
        )
        frameRenderCoordinator.updateSurfacePresentation(
            binding.surfaceMain,
            buildVulkanPresentationConfig(
                topScreenRect = topView?.getRect(),
                bottomScreenRect = bottomView?.getRect(),
                topAlpha = topView?.baseAlpha ?: 1f,
                bottomAlpha = bottomView?.baseAlpha ?: 1f,
                topOnTop = topView?.onTop ?: false,
                bottomOnTop = bottomView?.onTop ?: false,
            ),
            currentMainScreenBackground,
        )
    }

    private fun ensurePresentationBackend(renderer: VideoRenderer) {
        val targetBackend = renderer.toPresentationBackend()
        if (targetBackend == currentPresentationBackend) {
            return
        }

        val wasRendering = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        choreographerFrameRenderer.stopRendering()

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            frameRenderCoordinator.removeSurface(binding.surfaceMain)
        }
        presentation?.dismiss()
        presentation = null
        frameRenderCoordinator.stop()

        currentPresentationBackend = targetBackend
        frameRenderCoordinator = createFrameRenderCoordinator(targetBackend)
        choreographerFrameRenderer = ChoreographerFrameRendererFactory.createFrameRenderer(frameRenderCoordinator)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            frameRenderCoordinator.addSurface(binding.surfaceMain)
            updateDisplays()
        }

        updateRendererScreenAreas()
        scheduleStartupPresentationRefreshes()

        if (wasRendering) {
            choreographerFrameRenderer.startRendering()
        }
    }

    private fun scheduleStartupPresentationRefreshes() {
        cancelStartupPresentationRefreshes()
        if (currentPresentationBackend != PresentationBackend.VULKAN) {
            return
        }

        startupPresentationRefreshAttempts = 0
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (isDestroyed || currentPresentationBackend != PresentationBackend.VULKAN) {
                    startupPresentationRefreshRunnable = null
                    return
                }

                updateRendererScreenAreas()
                presentation?.updateRendererScreenAreas()

                startupPresentationRefreshAttempts += 1
                if (startupPresentationRefreshAttempts < STARTUP_PRESENTATION_REFRESH_ATTEMPTS) {
                    handler.postDelayed(this, STARTUP_PRESENTATION_REFRESH_INTERVAL_MS)
                } else {
                    startupPresentationRefreshRunnable = null
                }
            }
        }
        startupPresentationRefreshRunnable = refreshRunnable
        handler.post(refreshRunnable)
    }

    private fun cancelStartupPresentationRefreshes() {
        startupPresentationRefreshRunnable?.let { handler.removeCallbacks(it) }
        startupPresentationRefreshRunnable = null
        startupPresentationRefreshAttempts = 0
    }

    private fun buildVulkanPresentationConfig(
        topScreenRect: Rect?,
        bottomScreenRect: Rect?,
        topAlpha: Float,
        bottomAlpha: Float,
        topOnTop: Boolean,
        bottomOnTop: Boolean,
    ): VulkanPresentationConfig? {
        val rendererConfiguration = currentRuntimeRendererConfiguration ?: return null
        if (rendererConfiguration.renderer != VideoRenderer.VULKAN) {
            return null
        }

        val (surfaceWidth, surfaceHeight) = binding.surfaceMain.getCurrentSurfaceSize()
        val (resolvedTopScreenRect, resolvedBottomScreenRect) = resolveVulkanScreenRects(
            topScreenRect = topScreenRect,
            bottomScreenRect = bottomScreenRect,
            surfaceWidth = if (surfaceWidth > 0) surfaceWidth else binding.surfaceMain.width,
            surfaceHeight = if (surfaceHeight > 0) surfaceHeight else binding.surfaceMain.height,
        )

        return VulkanPresentationConfig(
            topScreenRect = resolvedTopScreenRect,
            bottomScreenRect = resolvedBottomScreenRect,
            topAlpha = topAlpha,
            bottomAlpha = bottomAlpha,
            topOnTop = topOnTop,
            bottomOnTop = bottomOnTop,
            backgroundMode = currentMainScreenBackground.mode,
            videoFiltering = rendererConfiguration.videoFiltering,
            retroShaderEnabled = rendererConfiguration.videoFiltering == VideoFiltering.RETROARCH,
            retroShaderPresetPath = rendererConfiguration.retroArchShader.presetPath,
            retroShaderSourceResolution = rendererConfiguration.retroArchShader.sourceResolution.name.lowercase(),
            retroShaderPassCount = rendererConfiguration.retroArchShader.passCount,
            retroShaderParameterOverrides = rendererConfiguration.retroArchShader.parameterOverrides,
            retroShaderClearHistory = rendererConfiguration.retroArchShader.clearHistory,
        )
    }

    private fun resolveVulkanScreenRects(
        topScreenRect: Rect?,
        bottomScreenRect: Rect?,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): Pair<Rect?, Rect?> {
        val sanitizedTopRect = topScreenRect?.takeIf { it.width > 0 && it.height > 0 }
        val sanitizedBottomRect = bottomScreenRect?.takeIf { it.width > 0 && it.height > 0 }

        if (sanitizedTopRect != null || sanitizedBottomRect != null) {
            return sanitizedTopRect to sanitizedBottomRect
        }

        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return null to null
        }

        val topHeight = max(1, surfaceHeight / 2)
        val bottomHeight = max(1, surfaceHeight - topHeight)
        return Rect(0, 0, surfaceWidth, topHeight) to Rect(0, topHeight, surfaceWidth, bottomHeight)
    }

    private fun createFrameRenderCoordinator(backend: PresentationBackend): FrameRenderCoordinator {
        return when (backend) {
            PresentationBackend.OPEN_GL -> OpenGlFrameRenderCoordinator()
            PresentationBackend.VULKAN -> VulkanFrameRenderCoordinator(this)
        }
    }

    private fun VideoRenderer.toPresentationBackend(): PresentationBackend {
        return if (this == VideoRenderer.VULKAN) {
            PresentationBackend.VULKAN
        } else {
            PresentationBackend.OPEN_GL
        }
    }

    private fun setupInputHandling(controllerConfiguration: ControllerConfiguration) {
        nativeInputListener = InputProcessor(controllerConfiguration, melonTouchHandler, frontendInputHandler)
    }

    private fun handleBackPressed() {
        if (isRewindWindowOpen()) {
            closeRewindWindow()
        } else {
            viewModel.pauseEmulator(true)
        }
    }

    private fun showPauseMenu(pauseMenu: PauseMenu) {
        val options = Array(pauseMenu.options.size) {
            getString(pauseMenu.options[it].textResource)
        }

        activeOverlays.addActiveOverlay(EmulatorOverlay.PAUSE_MENU)
        AlertDialog.Builder(this)
                .setTitle(R.string.pause)
                .setItems(options) { _, which ->
                    val selectedOption = pauseMenu.options[which]
                    viewModel.onPauseMenuOptionSelected(selectedOption)
                }
                .setOnDismissListener {
                    activeOverlays.removeActiveOverlay(EmulatorOverlay.PAUSE_MENU)
                }
                .setOnCancelListener {
                    viewModel.resumeEmulator()
                }
                .show()
    }

    private fun showRendererDebugMenu() {
        showRendererDebugListDialog(
            title = getString(R.string.renderer_debug_menu),
            entries = buildList {
                add(RendererDebugMenuEntry(getString(R.string.renderer_debug_capture)) { viewModel.dumpRendererDebugCapture() })
                if (isDebuggableBuild()) {
                    add(
                        RendererDebugMenuEntry(rendererDebugPauseLabel()) {
                            toggleRendererDebugPauseEmulation()
                            handler.post { showRendererDebugMenu() }
                        },
                    )
                    add(
                        RendererDebugMenuEntry(getString(R.string.renderer_2d_debug_controls)) {
                            syncRendererDebugEmulationMode()
                            handler.post { showRenderer2DDebugControlsDialog() }
                        },
                    )
                    add(
                        RendererDebugMenuEntry(getString(R.string.renderer_3d_debug_controls)) {
                            syncRendererDebugEmulationMode()
                            handler.post { showRenderer3DDebugControlsDialog() }
                        },
                    )
                }
            },
            backAction = null,
        )
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun showRenderer2DDebugControlsDialog() {
        if (!isDebuggableBuild()) {
            return
        }

        showRendererDebugListDialog(
            title = getString(R.string.renderer_2d_debug_controls),
            entries = buildList {
                add(RendererDebugMenuEntry("Background mode override") { handler.post { showRenderer2DModeOverrideDialog() } })
                add(RendererDebugMenuEntry("Packed compMode override") { handler.post { showRenderer2DCompModeOverrideDialog() } })
                add(RendererDebugMenuEntry("BG layers and priorities") { handler.post { showRenderer2DBgLayerDialog() } })
                add(RendererDebugMenuEntry("Background type enables") { handler.post { showRenderer2DBackgroundTypeDialog() } })
                add(RendererDebugMenuEntry("OBJ / Sprites layers") { handler.post { showRenderer2DObjectDialog() } })
                add(RendererDebugMenuEntry(getString(R.string.renderer_2d_debug_controls_reset)) { resetRenderer2DDebugControlState() })
            },
            backAction = { showRendererDebugMenu() },
        )
    }

    private fun rendererDebugPauseLabel(): String {
        return "Pause Emulation: ${if (rendererDebugPauseEmulation) getString(R.string.on) else getString(R.string.off)}"
    }

    private fun toggleRendererDebugPauseEmulation() {
        if (!isDebuggableBuild()) {
            return
        }

        rendererDebugPauseEmulation = !rendererDebugPauseEmulation
        syncRendererDebugEmulationMode()
    }

    private fun syncRendererDebugEmulationMode() {
        if (rendererDebugPauseEmulation) {
            viewModel.pauseEmulator(false)
        } else {
            viewModel.resumeEmulator()
        }
    }

    private fun onRendererDebugControlApplied() {
        if (!isDebuggableBuild()) {
            return
        }

        if (!rendererDebugPauseEmulation) {
            return
        }

        stepRendererDebugForwardFrame()
    }

    private fun stepRendererDebugForwardFrame() {
        if (!isDebuggableBuild()) {
            return
        }

        if (!rendererDebugPauseEmulation) {
            return
        }

        viewModel.debugStepFrame()
    }

    private fun showRendererDebugListDialog(
        title: String,
        entries: List<RendererDebugMenuEntry>,
        backAction: (() -> Unit)?,
    ) {
        val showRuntimeButtons = isDebuggableBuild() && backAction != null
        activeOverlays.addActiveOverlay(EmulatorOverlay.PAUSE_MENU)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(entries.map { it.title }.toTypedArray()) { _, which ->
                entries[which].action()
            }
            .apply {
                if (backAction != null) {
                    setNegativeButton(R.string.navigate_back, null)
                }
                if (showRuntimeButtons) {
                    setNeutralButton(rendererDebugPauseLabel(), null)
                    setPositiveButton("+1 Frame", null)
                }
            }
            .setOnDismissListener {
                activeOverlays.removeActiveOverlay(EmulatorOverlay.PAUSE_MENU)
            }
            .setOnCancelListener {
                syncRendererDebugEmulationMode()
            }
            .create()

        dialog.setOnShowListener {
            if (backAction != null) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    dialog.dismiss()
                    handler.post(backAction)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                toggleRendererDebugPauseEmulation()
                updateRendererDebugRuntimeButtons(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                stepRendererDebugForwardFrame()
            }
            updateRendererDebugRuntimeButtons(dialog)
        }
        dialog.show()
    }

    private fun showRendererDebugScrollDialog(
        title: String,
        scrollView: ScrollView,
        backAction: () -> Unit,
    ) {
        if (!isDebuggableBuild()) {
            return
        }

        activeOverlays.addActiveOverlay(EmulatorOverlay.PAUSE_MENU)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton(R.string.navigate_back, null)
            .apply {
                setNeutralButton(rendererDebugPauseLabel(), null)
                setPositiveButton("+1 Frame", null)
            }
            .setOnDismissListener {
                activeOverlays.removeActiveOverlay(EmulatorOverlay.PAUSE_MENU)
            }
            .setOnCancelListener {
                syncRendererDebugEmulationMode()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dialog.dismiss()
                handler.post(backAction)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                toggleRendererDebugPauseEmulation()
                updateRendererDebugRuntimeButtons(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                stepRendererDebugForwardFrame()
            }
            updateRendererDebugRuntimeButtons(dialog)
        }
        dialog.show()
    }

    private fun updateRendererDebugRuntimeButtons(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = rendererDebugPauseLabel()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = rendererDebugPauseEmulation
    }

    private fun showRenderer2DModeOverrideDialog() {
        showRendererDebugListDialog(
            title = "Background mode override",
            entries = listOf(
                RendererDebugMenuEntry("Engine A (Main) BG mode") {
                    handler.post { showRenderer2DModeEngineDialog(mainEngine = true) }
                },
                RendererDebugMenuEntry("Engine B (Sub) BG mode") {
                    handler.post { showRenderer2DModeEngineDialog(mainEngine = false) }
                },
            ),
            backAction = { showRenderer2DDebugControlsDialog() },
        )
    }

    private fun showRenderer2DModeEngineDialog(mainEngine: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (mainEngine) "Engine A (Main) BG mode" else "Engine B (Sub) BG mode"
        addRenderer2DSection(content, title)
        addRenderer2DDescription(
            content,
            "Forces the Nintendo DS BG mode used by `DrawScanline_BGOBJ`. Native keeps `CurUnit->DispCnt & 0x7`.",
        )
        addRenderer2DModeGroup(
            parent = content,
            title = title,
            selectedMode = if (mainEngine) state.mainForcedMode else state.subForcedMode,
            includeMode6 = mainEngine,
        ) {
            if (mainEngine) {
                state.mainForcedMode = it
            } else {
                state.subForcedMode = it
            }
            applyRenderer2DDebugControlState(state)
        }

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DModeOverrideDialog()
        }
    }

    private fun showRenderer2DCompModeOverrideDialog() {
        showRendererDebugListDialog(
            title = "Packed compMode override",
            entries = listOf(
                RendererDebugMenuEntry("Top screen compMode") {
                    handler.post { showRenderer2DCompModeScreenDialog(topScreen = true) }
                },
                RendererDebugMenuEntry("Bottom screen compMode") {
                    handler.post { showRenderer2DCompModeScreenDialog(topScreen = false) }
                },
            ),
            backAction = { showRenderer2DDebugControlsDialog() },
        )
    }

    private fun showRenderer2DCompModeScreenDialog(topScreen: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (topScreen) "Top screen compMode" else "Bottom screen compMode"
        addRenderer2DSection(content, title)
        addRenderer2DDescription(
            content,
            "Forces the compositor mode stored in the packed control plane. Native keeps the value produced by `DrawScanline_BGOBJ`; the override is applied independently to top and bottom snapshots before Vulkan consumes them.",
        )
        addRenderer2DCompModeGroup(
            parent = content,
            title = title,
            selectedMode = if (topScreen) state.topForcedCompMode else state.bottomForcedCompMode,
        ) {
            if (topScreen) {
                state.topForcedCompMode = it
            } else {
                state.bottomForcedCompMode = it
            }
            applyRenderer2DDebugControlState(state)
        }

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DCompModeOverrideDialog()
        }
    }

    private fun showRenderer2DBgLayerDialog() {
        showRendererDebugListDialog(
            title = "BG layers and priorities",
            entries = listOf(
                RendererDebugMenuEntry("Engine A (Main) BG layers") {
                    handler.post { showRenderer2DBgLayerEngineDialog(mainEngine = true) }
                },
                RendererDebugMenuEntry("Engine B (Sub) BG layers") {
                    handler.post { showRenderer2DBgLayerEngineDialog(mainEngine = false) }
                },
                RendererDebugMenuEntry("Engine A (Main) BG priorities") {
                    handler.post { showRenderer2DBgPriorityEngineDialog(mainEngine = true) }
                },
                RendererDebugMenuEntry("Engine B (Sub) BG priorities") {
                    handler.post { showRenderer2DBgPriorityEngineDialog(mainEngine = false) }
                },
            ),
            backAction = { showRenderer2DDebugControlsDialog() },
        )
    }

    private fun showRenderer2DBgLayerEngineDialog(mainEngine: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (mainEngine) "Engine A (Main) BG layers" else "Engine B (Sub) BG layers"
        addRenderer2DSection(content, title)
        addRenderer2DDescription(
            content,
            "Disables individual BG0-BG3 draw gates before `DrawBG_*` or `DrawBG_3D`; this is independent from the game's DISPCNT enable bits.",
        )
        addRenderer2DBgLayerSwitches(
            parent = content,
            title = title,
            disabledMask = { if (mainEngine) state.disabledMainBgMask else state.disabledSubBgMask },
            updateDisabledMask = {
                if (mainEngine) {
                    state.disabledMainBgMask = it
                } else {
                    state.disabledSubBgMask = it
                }
                applyRenderer2DDebugControlState(state)
            },
        )

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DBgLayerDialog()
        }
    }

    private fun showRenderer2DBgPriorityEngineDialog(mainEngine: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (mainEngine) "Engine A (Main) BG priorities" else "Engine B (Sub) BG priorities"
        addRenderer2DSection(content, title)
        addRenderer2DDescription(
            content,
            "Disables BG layers by Nintendo DS BGCNT priority bits 0-1. Priority 0 is closest to the viewer; priority 3 is furthest back.",
        )
        addRenderer2DPrioritySwitches(
            parent = content,
            title = title,
            disabledMask = { if (mainEngine) state.disabledMainBgPriorityMask else state.disabledSubBgPriorityMask },
            updateDisabledMask = {
                if (mainEngine) {
                    state.disabledMainBgPriorityMask = it
                } else {
                    state.disabledSubBgPriorityMask = it
                }
                applyRenderer2DDebugControlState(state)
            },
            descriptionPrefix = "BGCNT priority",
            codeDescription = "Code gate: `bgCnt[n] & 0x3` inside `DrawScanlineBGMode`.",
        )

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DBgLayerDialog()
        }
    }

    private fun showRenderer2DBackgroundTypeDialog() {
        showRendererDebugListDialog(
            title = "Background type enables",
            entries = listOf(
                RendererDebugMenuEntry("Tile background types") {
                    handler.post { showRenderer2DBackgroundTileTypesDialog() }
                },
                RendererDebugMenuEntry("Bitmap background types") {
                    handler.post { showRenderer2DBackgroundBitmapTypesDialog() }
                },
                RendererDebugMenuEntry("Special background types") {
                    handler.post { showRenderer2DBackgroundSpecialTypesDialog() }
                },
            ),
            backAction = { showRenderer2DDebugControlsDialog() },
        )
    }

    private fun showRenderer2DBackgroundTileTypesDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Tile background types")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_STATIC_BACKGROUND,
            title = "Static background",
            description = "Nintendo DS static BG. Code: `DrawBG_Text`; used by BG0/BG1 and by BG2/BG3 when the active mode selects text/static layers.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_AFFINE_BACKGROUND,
            title = "Affine background",
            description = "Nintendo DS affine BG. Code: `DrawBG_Affine`; used for BG2/BG3 in modes that select affine transform layers.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_AFFINE_EXTENDED_TILED_BACKGROUND,
            title = "Affine Extended background - tiled",
            description = "Nintendo DS affine extended tiled BG. Code: `DrawBG_Extended` with BGCNT bitmap bit clear; keeps the tile path with H/V flip support.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("Tile background types", scrollView) {
            showRenderer2DBackgroundTypeDialog()
        }
    }

    private fun showRenderer2DBackgroundBitmapTypesDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Bitmap background types")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_AFFINE_EXTENDED_BITMAP_256_BACKGROUND,
            title = "Affine Extended background - 256 colors bitmap",
            description = "Nintendo DS affine extended 256-color bitmap BG. Code: `DrawBG_Extended` bitmap path without direct-color bit; VRAM is treated as a paletted framebuffer.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_AFFINE_EXTENDED_DIRECT_COLOR_BACKGROUND,
            title = "Affine Extended background - direct color bitmap",
            description = "Nintendo DS affine extended direct-color bitmap BG. Code: `DrawBG_Extended` bitmap path with BGCNT direct-color bit; VRAM is treated as 15-bit direct color.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_LARGE_SCREEN_BACKGROUND,
            title = "Large screen background",
            description = "Nintendo DS large screen BG. Code: `DrawBG_Large`; mode 6 BG2 large framebuffer path, available only on Engine A (Main).",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("Bitmap background types", scrollView) {
            showRenderer2DBackgroundTypeDialog()
        }
    }

    private fun showRenderer2DBackgroundSpecialTypesDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Special background types")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_3D_BACKGROUND,
            title = "3D background",
            description = "Nintendo DS 3D background layer. Code: `DrawBG_3D`; Engine A BG0 placeholder/output used to composite GPU3D with the 2D BG/OBJ stack.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("Special background types", scrollView) {
            showRenderer2DBackgroundTypeDialog()
        }
    }

    private fun showRenderer2DObjectDialog() {
        showRendererDebugListDialog(
            title = "OBJ / Sprites",
            entries = listOf(
                RendererDebugMenuEntry("OBJ master") { handler.post { showRenderer2DObjectMasterDialog() } },
                RendererDebugMenuEntry("OBJ priority enables") { handler.post { showRenderer2DObjectPriorityDialog() } },
                RendererDebugMenuEntry("OBJ OAM order / Z buckets") { handler.post { showRenderer2DObjectOrderDialog() } },
                RendererDebugMenuEntry("OBJ vertical bands") { handler.post { showRenderer2DObjectBandDialog() } },
                RendererDebugMenuEntry("OBJ transform and storage type") { handler.post { showRenderer2DObjectTypeDialog() } },
                RendererDebugMenuEntry("OBJ effects and masks") { handler.post { showRenderer2DObjectEffectsDialog() } },
            ),
            backAction = { showRenderer2DDebugControlsDialog() },
        )
    }

    private fun showRenderer2DObjectMasterDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "OBJ master")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_OBJECTS,
            title = "OBJ (Objects / Sprites)",
            description = "Nintendo DS sprites from OAM. Code: `DrawSprites`, `DrawSprite_Normal`, `DrawSprite_Rotscale` and `InterleaveSprites`; covers tiled and bitmap OBJ pixels.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("OBJ master", scrollView) {
            showRenderer2DObjectDialog()
        }
    }

    private fun showRenderer2DObjectPriorityDialog() {
        showRendererDebugListDialog(
            title = "OBJ priority enables",
            entries = listOf(
                RendererDebugMenuEntry("Engine A (Main) OBJ priorities") {
                    handler.post { showRenderer2DObjectPriorityEngineDialog(mainEngine = true) }
                },
                RendererDebugMenuEntry("Engine B (Sub) OBJ priorities") {
                    handler.post { showRenderer2DObjectPriorityEngineDialog(mainEngine = false) }
                },
            ),
            backAction = { showRenderer2DObjectDialog() },
        )
    }

    private fun showRenderer2DObjectPriorityEngineDialog(mainEngine: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (mainEngine) "Engine A (Main) OBJ priorities" else "Engine B (Sub) OBJ priorities"
        addRenderer2DSection(content, "OBJ priority enables")
        addRenderer2DDescription(
            content,
            "Disables sprite layers by OAM Attribute 2 priority bits 10-11. Priority 0 is closest to the viewer; priority 3 is furthest back.",
        )
        addRenderer2DPrioritySwitches(
            parent = content,
            title = title,
            disabledMask = { if (mainEngine) state.disabledMainObjPriorityMask else state.disabledSubObjPriorityMask },
            updateDisabledMask = {
                if (mainEngine) {
                    state.disabledMainObjPriorityMask = it
                } else {
                    state.disabledSubObjPriorityMask = it
                }
                applyRenderer2DDebugControlState(state)
            },
            descriptionPrefix = "OBJ priority",
            codeDescription = "Code gate: `attrib[2] & 0x0C00`, then `InterleaveSprites(0x40000 | priority << 16)`.",
        )

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DObjectPriorityDialog()
        }
    }

    private fun showRenderer2DObjectOrderDialog() {
        showRendererDebugListDialog(
            title = "OBJ OAM order / Z buckets",
            entries = listOf(
                RendererDebugMenuEntry("Engine A (Main) OBJ OAM order") {
                    handler.post { showRenderer2DObjectOrderEngineDialog(mainEngine = true) }
                },
                RendererDebugMenuEntry("Engine B (Sub) OBJ OAM order") {
                    handler.post { showRenderer2DObjectOrderEngineDialog(mainEngine = false) }
                },
            ),
            backAction = { showRenderer2DObjectDialog() },
        )
    }

    private fun showRenderer2DObjectOrderEngineDialog(mainEngine: Boolean) {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        val title = if (mainEngine) "Engine A (Main) OBJ OAM order" else "Engine B (Sub) OBJ OAM order"
        addRenderer2DSection(content, "OBJ OAM order / Z buckets")
        addRenderer2DDescription(
            content,
            "Filters sprites by OAM index order. For equal OBJ priority, lower OAM indices are drawn later by `DrawSprites` and appear closer to the viewer; this gives practical Z-position control for composite sprites.",
        )
        addRenderer2DObjectOrderSwitches(
            parent = content,
            title = title,
            disabledMask = { if (mainEngine) state.disabledMainObjOrderMask else state.disabledSubObjOrderMask },
            updateDisabledMask = {
                if (mainEngine) {
                    state.disabledMainObjOrderMask = it
                } else {
                    state.disabledSubObjOrderMask = it
                }
                applyRenderer2DDebugControlState(state)
            },
        )

        showRendererDebugScrollDialog(title, scrollView) {
            showRenderer2DObjectOrderDialog()
        }
    }

    private fun showRenderer2DObjectBandDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "OBJ vertical bands")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_OBJECT_UPPER_BAND,
            title = "OBJ upper band - Y 0..63",
            description = "Nintendo DS OBJ pixels and OBJ Window mask for the upper third of the current LCD. Code gate: `DrawSprites(line)` returns after clearing `OBJLine`/`OBJWindow` when `line < 64`.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_OBJECT_MIDDLE_BAND,
            title = "OBJ middle band - Y 64..127",
            description = "Nintendo DS OBJ pixels and OBJ Window mask for the middle third of the current LCD. Code gate: `DrawSprites(line)` line range 64..127.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_OBJECT_LOWER_BAND,
            title = "OBJ lower band - Y 128..191",
            description = "Nintendo DS OBJ pixels and OBJ Window mask for the lower third of the current LCD. Code gate: `DrawSprites(line)` line range 128..191.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("OBJ vertical bands", scrollView) {
            showRenderer2DObjectDialog()
        }
    }

    private fun showRenderer2DObjectTypeDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "OBJ transform and storage type")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_REGULAR_OBJECT,
            title = "OBJ regular transform",
            description = "Nintendo DS non-affine OBJ. Code: `DrawSprite_Normal`; OAM Attribute 0 affine flag bit 8 is clear.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_AFFINE_OBJECT,
            title = "OBJ affine / rotscale transform",
            description = "Nintendo DS affine OBJ. Code: `DrawSprite_Rotscale`; OAM Attribute 0 affine flag bit 8 is set.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_TILED_4BPP_OBJECT,
            title = "OBJ tiled - 16 colors",
            description = "Nintendo DS tiled OBJ using 4bpp/16-color data. Code path in `DrawSprite_*` when Attribute 0 color mode bit 13 is clear.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_TILED_8BPP_OBJECT,
            title = "OBJ tiled - 256 colors",
            description = "Nintendo DS tiled OBJ using 8bpp/256-color data. Code path in `DrawSprite_*` when Attribute 0 color mode bit 13 is set.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_BITMAP_OBJECT,
            title = "OBJ bitmap",
            description = "Nintendo DS bitmap OBJ. Code path in `DrawSprite_*` when OAM Attribute 0 object mode bits 10-11 equal 3.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("OBJ transform and storage type", scrollView) {
            showRenderer2DObjectDialog()
        }
    }

    private fun showRenderer2DObjectEffectsDialog() {
        val state = readRenderer2DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "OBJ effects and masks")
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_BLENDED_OBJECT,
            title = "OBJ semi-transparent",
            description = "Nintendo DS semi-transparent OBJ. Code path in `DrawSprite_*` when OAM Attribute 0 object mode bits 10-11 equal 1.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_WINDOW_OBJECT,
            title = "OBJ Window",
            description = "Nintendo DS OBJ Window mask. Code: `DrawSprite_*<true>` fills `OBJWindow`; affects window clipping rather than visible color directly.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )
        addRenderer2DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_MOSAIC_OBJECT,
            title = "OBJ mosaic",
            description = "Nintendo DS OBJ using mosaic. Code: Attribute 0 mosaic bit 12 and `ApplySpriteMosaicX`.",
            applyState = { applyRenderer2DDebugControlState(state) },
        )

        showRendererDebugScrollDialog("OBJ effects and masks", scrollView) {
            showRenderer2DObjectDialog()
        }
    }

    private fun showRenderer3DDebugControlsDialog() {
        if (!isDebuggableBuild()) {
            return
        }

        showRendererDebugListDialog(
            title = getString(R.string.renderer_3d_debug_controls),
            entries = buildList {
                add(RendererDebugMenuEntry("Renderer output and primitive buckets") { handler.post { showRenderer3DPrimitiveDialog() } })
                add(RendererDebugMenuEntry("Polygon material and effects") { handler.post { showRenderer3DMaterialDialog() } })
                add(RendererDebugMenuEntry("Depth, fog and screen bands") { handler.post { showRenderer3DDepthAndBandDialog() } })
                add(RendererDebugMenuEntry(getString(R.string.renderer_3d_debug_controls_reset)) { resetRenderer3DDebugControlState() })
            },
            backAction = { showRendererDebugMenu() },
        )
    }

    private fun showRenderer3DPrimitiveDialog() {
        showRendererDebugListDialog(
            title = "Renderer output and primitives",
            entries = listOf(
                RendererDebugMenuEntry("3D renderer output") { handler.post { showRenderer3DOutputDialog() } },
                RendererDebugMenuEntry("Primitive buckets") { handler.post { showRenderer3DPrimitiveBucketDialog() } },
                RendererDebugMenuEntry("Blend buckets") { handler.post { showRenderer3DBlendBucketDialog() } },
            ),
            backAction = { showRenderer3DDebugControlsDialog() },
        )
    }

    private fun showRenderer3DOutputDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "3D renderer output")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_RENDERER_OUTPUT,
            title = "3D renderer output",
            description = "Master GPU3D output gate. Code: Vulkan `buildGraphicsTriangleList` / `buildTriangleList`; disables all 3D polygons before raster queues are populated.",
        )

        showRendererDebugScrollDialog("3D renderer output", scrollView) {
            showRenderer3DPrimitiveDialog()
        }
    }

    private fun showRenderer3DPrimitiveBucketDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Primitive buckets")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_TRIANGLE_POLYGONS,
            title = "Triangle polygons",
            description = "Nintendo DS 3D polygon primitives. Code gate: `AcceleratedPrimitiveType::Triangles` or `polygon->Type != 1`.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_LINE_POLYGONS,
            title = "Line polygons",
            description = "Nintendo DS 3D line primitives expanded into quads for Vulkan. Code gate: `AcceleratedPrimitiveType::Lines` or `polygon->Type == 1`.",
        )

        showRendererDebugScrollDialog("Primitive buckets", scrollView) {
            showRenderer3DPrimitiveDialog()
        }
    }

    private fun showRenderer3DBlendBucketDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Blend buckets")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_OPAQUE_POLYGONS,
            title = "Opaque polygons",
            description = "Opaque GPU3D polygons. Code bucket: `GraphicsOpaqueDrawIndices`; alpha is 31 and the accelerated translucent flag is clear.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_TRANSLUCENT_POLYGONS,
            title = "Translucent polygons",
            description = "Translucent GPU3D polygons. Code bucket: `GraphicsAlphaDrawIndices`; includes accelerated translucent pass or polygon alpha below 31.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_SHADOW_MASK_POLYGONS,
            title = "Shadow mask polygons",
            description = "Nintendo DS shadow mask polygons. Code bucket: `GraphicsShadowMaskDrawIndices` and `AcceleratedPolygonFlagShadowMask`.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_SHADOW_POLYGONS,
            title = "Shadow polygons",
            description = "Nintendo DS shadow blend polygons. Code bucket: `GraphicsShadowDrawIndices` and `AcceleratedPolygonFlagShadow`.",
        )

        showRendererDebugScrollDialog("Blend buckets", scrollView) {
            showRenderer3DPrimitiveDialog()
        }
    }

    private fun showRenderer3DMaterialDialog() {
        showRendererDebugListDialog(
            title = "Polygon material and effects",
            entries = listOf(
                RendererDebugMenuEntry("Texture state") { handler.post { showRenderer3DTextureStateDialog() } },
                RendererDebugMenuEntry("Polygon mode") { handler.post { showRenderer3DPolygonModeDialog() } },
            ),
            backAction = { showRenderer3DDebugControlsDialog() },
        )
    }

    private fun showRenderer3DTextureStateDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Texture state")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_TEXTURED_POLYGONS,
            title = "Textured polygons",
            description = "GPU3D polygons with texture mapping enabled and non-zero texture format. Code gate: `RenderDispCnt` texture bit plus `TexParam >> 26`.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_UNTEXTURED_POLYGONS,
            title = "Untextured polygons",
            description = "GPU3D polygons without active texture sampling. Code path uses fallback/untextured material data in Vulkan raster shaders.",
        )

        showRendererDebugScrollDialog("Texture state", scrollView) {
            showRenderer3DMaterialDialog()
        }
    }

    private fun showRenderer3DPolygonModeDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Polygon mode")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_MODULATE_POLYGONS,
            title = "Modulation polygons",
            description = "Nintendo DS polygon mode 0 or untextured fallback. Code gate: `PolyAttr` blend mode not decal/toon-highlight.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_DECAL_POLYGONS,
            title = "Decal polygons",
            description = "Nintendo DS decal-style textured polygons. Code gate: textured polygon with `PolyAttr` blend mode bit 0 set.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_TOON_HIGHLIGHT_POLYGONS,
            title = "Toon / highlight polygons",
            description = "Nintendo DS toon/highlight polygon mode. Code gate: `PolyAttr` blend mode 2; Vulkan chooses toon or highlight from `RenderDispCnt`.",
        )

        showRendererDebugScrollDialog("Polygon mode", scrollView) {
            showRenderer3DMaterialDialog()
        }
    }

    private fun showRenderer3DDepthAndBandDialog() {
        showRendererDebugListDialog(
            title = "Depth, fog and screen bands",
            entries = listOf(
                RendererDebugMenuEntry("Depth and fog mode") { handler.post { showRenderer3DDepthModeDialog() } },
                RendererDebugMenuEntry("Screen bands") { handler.post { showRenderer3DScreenBandDialog() } },
            ),
            backAction = { showRenderer3DDebugControlsDialog() },
        )
    }

    private fun showRenderer3DDepthModeDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Depth mode")
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_W_BUFFER_POLYGONS,
            title = "W-buffer polygons",
            description = "Nintendo DS W-buffer polygons. Code gate: `AcceleratedPolygonFlagWBuffer`; Vulkan uses perspective W depth interpolation.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_Z_BUFFER_POLYGONS,
            title = "Z-buffer polygons",
            description = "Nintendo DS Z-buffer polygons. Code gate: absence of `AcceleratedPolygonFlagWBuffer`; Vulkan uses screen-space linear Z depth.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_DEPTH_WRITE_POLYGONS,
            title = "Depth write polygons",
            description = "GPU3D polygons that update depth. Code gate: `PolyAttr` bit 11; disabling this removes depth-writing polygons from the draw queues.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_FOG_WRITE_POLYGONS,
            title = "Fog write polygons",
            description = "GPU3D polygons that write fog attributes. Code gate: `AcceleratedPolygonFlagFogWrite`; final fog pass consumes the attribute target.",
        )

        showRendererDebugScrollDialog("Depth and fog mode", scrollView) {
            showRenderer3DDepthAndBandDialog()
        }
    }

    private fun showRenderer3DScreenBandDialog() {
        val state = readRenderer3DDebugControlState()
        val (content, scrollView) = createRenderer2DScrollContent()

        addRenderer2DSection(content, "Screen bands")
        addRenderer2DDescription(
            content,
            "Filters whole 3D polygons by their Y range in the active render target. This is a coarse isolation tool: polygons spanning an enabled band remain whole.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_UPPER_BAND,
            title = "3D upper band - Y 0..63",
            description = "GPU3D polygons touching the upper third of the LCD. Code gate uses packed polygon Y bounds scaled to the current internal resolution.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_MIDDLE_BAND,
            title = "3D middle band - Y 64..127",
            description = "GPU3D polygons touching the middle third of the LCD. Code gate uses packed polygon Y bounds scaled to the current internal resolution.",
        )
        addRenderer3DFeatureSwitch(
            parent = content,
            state = state,
            flag = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_LOWER_BAND,
            title = "3D lower band - Y 128..191",
            description = "GPU3D polygons touching the lower third of the LCD. Code gate uses packed polygon Y bounds scaled to the current internal resolution.",
        )

        showRendererDebugScrollDialog("Screen bands", scrollView) {
            showRenderer3DDepthAndBandDialog()
        }
    }

    private fun createRenderer2DScrollContent(): Pair<LinearLayout, ScrollView> {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val scrollView = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return content to scrollView
    }

    private fun applyRenderer2DDebugControlState(state: Renderer2DDebugControlState) {
        if (!isDebuggableBuild()) {
            return
        }

        RendererDebugBridge.setRenderer2DDebugControls(
            state.mainForcedMode,
            state.subForcedMode,
            state.topForcedCompMode,
            state.bottomForcedCompMode,
            state.disabledMainBgMask,
            state.disabledSubBgMask,
            state.disabledMainBgPriorityMask,
            state.disabledSubBgPriorityMask,
            state.disabledMainObjPriorityMask,
            state.disabledSubObjPriorityMask,
            state.disabledMainObjOrderMask,
            state.disabledSubObjOrderMask,
            state.featureMask,
        )
        onRendererDebugControlApplied()
    }

    private fun resetRenderer2DDebugControlState() {
        applyRenderer2DDebugControlState(Renderer2DDebugControlState())
    }

    private fun applyRenderer3DDebugControlState(state: Renderer3DDebugControlState) {
        if (!isDebuggableBuild()) {
            return
        }

        RendererDebugBridge.setRenderer3DDebugControls(state.featureMask)
        onRendererDebugControlApplied()
    }

    private fun resetRenderer3DDebugControlState() {
        applyRenderer3DDebugControlState(Renderer3DDebugControlState())
    }

    private fun readRenderer2DDebugControlState(): Renderer2DDebugControlState {
        if (!isDebuggableBuild()) {
            return Renderer2DDebugControlState()
        }

        val state = RendererDebugBridge.getRenderer2DDebugControls()
        return if (state != null && state.size >= RENDERER_2D_STATE_SIZE) {
            Renderer2DDebugControlState(
                mainForcedMode = state[0],
                subForcedMode = state[1],
                topForcedCompMode = state[2],
                bottomForcedCompMode = state[3],
                disabledMainBgMask = state[4],
                disabledSubBgMask = state[5],
                disabledMainBgPriorityMask = state[6],
                disabledSubBgPriorityMask = state[7],
                disabledMainObjPriorityMask = state[8],
                disabledSubObjPriorityMask = state[9],
                disabledMainObjOrderMask = state[10],
                disabledSubObjOrderMask = state[11],
                featureMask = state[12],
            )
        } else {
            Renderer2DDebugControlState()
        }
    }

    private fun readRenderer3DDebugControlState(): Renderer3DDebugControlState {
        if (!isDebuggableBuild()) {
            return Renderer3DDebugControlState()
        }

        val state = RendererDebugBridge.getRenderer3DDebugControls()
        return if (state != null && state.size >= RENDERER_3D_STATE_SIZE) {
            Renderer3DDebugControlState(featureMask = state[0])
        } else {
            Renderer3DDebugControlState()
        }
    }

    private fun addRenderer2DModeGroup(
        parent: LinearLayout,
        title: String,
        selectedMode: Int,
        includeMode6: Boolean,
        onModeChanged: (Int) -> Unit,
    ) {
        addRenderer2DSubsection(parent, title)
        val modes = renderer2DDebugModeItems(includeMode6)
        val radioIds = mutableMapOf<Int, Int>()
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        modes.forEach { modeItem ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = modeItem.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(false)
                ellipsize = null
                maxLines = 4
            }
            radioIds[radioButton.id] = modeItem.mode
            radioGroup.addView(
                radioButton,
                RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        radioGroup.check(
            radioIds.entries.firstOrNull { it.value == selectedMode }?.key
                ?: radioIds.entries.first { it.value == RENDERER_2D_NATIVE_MODE }.key,
        )
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            radioIds[checkedId]?.let(onModeChanged)
        }
        parent.addView(radioGroup)
    }

    private fun addRenderer2DCompModeGroup(
        parent: LinearLayout,
        title: String,
        selectedMode: Int,
        onModeChanged: (Int) -> Unit,
    ) {
        addRenderer2DSubsection(parent, title)
        val modes = renderer2DDebugCompModeItems
        val radioIds = mutableMapOf<Int, Int>()
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        modes.forEach { modeItem ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = modeItem.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine(false)
                ellipsize = null
                maxLines = 4
            }
            radioIds[radioButton.id] = modeItem.mode
            radioGroup.addView(
                radioButton,
                RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        radioGroup.check(
            radioIds.entries.firstOrNull { it.value == selectedMode }?.key
                ?: radioIds.entries.first { it.value == RENDERER_2D_NATIVE_MODE }.key,
        )
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            radioIds[checkedId]?.let(onModeChanged)
        }
        parent.addView(radioGroup)
    }

    private fun addRenderer2DBgLayerSwitches(
        parent: LinearLayout,
        title: String,
        disabledMask: () -> Int,
        updateDisabledMask: (Int) -> Unit,
    ) {
        addRenderer2DSubsection(parent, title)
        renderer2DDebugBgLayerItems.forEach { item ->
            addRenderer2DSwitch(
                parent = parent,
                title = item.title,
                description = item.description,
                checked = (disabledMask() and (1 shl item.bgIndex)) == 0,
            ) { checked ->
                val bit = 1 shl item.bgIndex
                val nextMask = if (checked) {
                    disabledMask() and bit.inv()
                } else {
                    disabledMask() or bit
                }
                updateDisabledMask(nextMask)
            }
        }
    }

    private fun addRenderer2DPrioritySwitches(
        parent: LinearLayout,
        title: String,
        disabledMask: () -> Int,
        updateDisabledMask: (Int) -> Unit,
        descriptionPrefix: String,
        codeDescription: String,
    ) {
        addRenderer2DSubsection(parent, title)
        renderer2DDebugPriorityItems.forEach { item ->
            addRenderer2DSwitch(
                parent = parent,
                title = item.title,
                description = "$descriptionPrefix ${item.priority}. ${item.description} $codeDescription",
                checked = (disabledMask() and (1 shl item.priority)) == 0,
            ) { checked ->
                val bit = 1 shl item.priority
                val nextMask = if (checked) {
                    disabledMask() and bit.inv()
                } else {
                    disabledMask() or bit
                }
                updateDisabledMask(nextMask)
            }
        }
    }

    private fun addRenderer2DObjectOrderSwitches(
        parent: LinearLayout,
        title: String,
        disabledMask: () -> Int,
        updateDisabledMask: (Int) -> Unit,
    ) {
        addRenderer2DSubsection(parent, title)
        renderer2DDebugObjectOrderItems.forEach { item ->
            addRenderer2DSwitch(
                parent = parent,
                title = item.title,
                description = item.description,
                checked = (disabledMask() and (1 shl item.bucket)) == 0,
            ) { checked ->
                val bit = 1 shl item.bucket
                val nextMask = if (checked) {
                    disabledMask() and bit.inv()
                } else {
                    disabledMask() or bit
                }
                updateDisabledMask(nextMask)
            }
        }
    }

    private fun addRenderer2DFeatureSwitch(
        parent: LinearLayout,
        state: Renderer2DDebugControlState,
        flag: Int,
        title: String,
        description: String,
        applyState: () -> Unit,
    ) {
        addRenderer2DSwitch(
            parent = parent,
            title = title,
            description = description,
            checked = (state.featureMask and flag) != 0,
        ) { checked ->
            state.featureMask = if (checked) {
                state.featureMask or flag
            } else {
                state.featureMask and flag.inv()
            }
            applyState()
        }
    }

    private fun addRenderer3DFeatureSwitch(
        parent: LinearLayout,
        state: Renderer3DDebugControlState,
        flag: Int,
        title: String,
        description: String,
    ) {
        addRenderer2DSwitch(
            parent = parent,
            title = title,
            description = description,
            checked = (state.featureMask and flag) != 0,
        ) { checked ->
            state.featureMask = if (checked) {
                state.featureMask or flag
            } else {
                state.featureMask and flag.inv()
            }
            applyRenderer3DDebugControlState(state)
        }
    }

    private fun addRenderer2DSection(parent: LinearLayout, title: String) {
        parent.addView(
            TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12), 0, dp(4))
            },
        )
    }

    private fun addRenderer2DSubsection(parent: LinearLayout, title: String) {
        parent.addView(
            TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(8), 0, dp(2))
            },
        )
    }

    private fun addRenderer2DDescription(parent: LinearLayout, description: String) {
        parent.addView(
            TextView(this).apply {
                text = description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(4))
            },
        )
    }

    private fun addRenderer2DSwitch(
        parent: LinearLayout,
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChanged: (Boolean) -> Unit,
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val switch = SwitchCompat(this).apply {
            text = title
            isChecked = checked
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(false)
            ellipsize = null
            setOnCheckedChangeListener { _, isChecked ->
                onCheckedChanged(isChecked)
            }
        }
        item.addView(
            switch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        item.addView(
            TextView(this).apply {
                text = description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), 0, 0, 0)
            },
        )
        parent.addView(item)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun disableScreenTimeOut() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun enableScreenTimeOut() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!activeOverlays.hasActiveOverlays() && nativeInputListener.onKeyEvent(event))
            return true

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (activeOverlays.hasActiveOverlays()) {
            nativeInputListener.onMotionEventSlot2(event)
            return super.dispatchGenericMotionEvent(event)
        }

        if (nativeInputListener.onMotionEvent(event))
            return true

        return super.dispatchGenericMotionEvent(event)
    }

    private fun isRewindWindowOpen(): Boolean {
        return binding.root.currentState == R.id.rewind_visible
    }

    private fun showSaveStateSlotsDialog(slots: List<SaveStateSlot>, onSlotPicked: (SaveStateSlot) -> Unit) {
        val dateFormatter = SimpleDateFormat("EEE, dd MMM yyyy", ConfigurationCompat.getLocales(resources.configuration)[0])
        val timeFormatter = SimpleDateFormat("kk:mm:ss", ConfigurationCompat.getLocales(resources.configuration)[0])
        var dialog: AlertDialog? = null
        var adapter: SaveStateAdapter? = null

        adapter = SaveStateAdapter(
            slots = slots,
            picasso = picasso,
            dateFormat = dateFormatter,
            timeFormat = timeFormatter,
            onSlotSelected = {
                dialog?.dismiss()
                onSlotPicked(it)
            },
            onDeletedSlot = {
                viewModel.deleteSaveStateSlot(it)?.let { newSlots ->
                    adapter?.updateSaveStateSlots(newSlots)
                }
            },
        )

        val recyclerView = RecyclerView(this).apply {
            val layoutManager = LinearLayoutManager(this@EmulatorActivity)
            this.layoutManager = layoutManager
            addItemDecoration(DividerItemDecoration(context, layoutManager.orientation))
            this.adapter = adapter
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        activeOverlays.addActiveOverlay(EmulatorOverlay.SAVE_STATES_DIALOG)

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_slot))
            .setView(recyclerView)
            .setNegativeButton(R.string.cancel) { _dialog, _ ->
                _dialog.cancel()
            }
            .setOnDismissListener {
                activeOverlays.removeActiveOverlay(EmulatorOverlay.SAVE_STATES_DIALOG)
            }
            .setOnCancelListener {
                viewModel.resumeEmulator()
            }
            .show()
    }

    private fun showRomLoadErrorDialog() {
        activeOverlays.addActiveOverlay(EmulatorOverlay.ROM_LOAD_ERROR_DIALOG)
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.error_load_rom)
            .setMessage(R.string.error_load_rom_message)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .show()
    }

    private fun showRomNotFoundDialog(romPath: String) {
        activeOverlays.addActiveOverlay(EmulatorOverlay.ROM_NOT_FOUND_DIALOG)
        AlertDialog.Builder(this)
            .setTitle(R.string.error_rom_not_found)
            .setMessage(getString(R.string.error_rom_not_found_info, romPath))
            .setPositiveButton(R.string.ok) { _, _ ->
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private fun showFirmwareLoadErrorDialog(error: EmulatorState.FirmwareLoadError) {
        activeOverlays.addActiveOverlay(EmulatorOverlay.FIRMWARE_LOAD_ERROR_DIALOG)
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.error_load_firmware)
            .setMessage(resources.getString(R.string.error_load_firmware_message, error.reason.toString()))
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .show()
    }

    private fun showRewindWindow(rewindWindow: RewindWindow) {
        activeOverlays.addActiveOverlay(EmulatorOverlay.REWIND_WINDOW)
        binding.root.transitionToState(R.id.rewind_visible)
        rewindSaveStateAdapter.setRewindWindow(rewindWindow)
    }

    private fun closeRewindWindow() {
        activeOverlays.removeActiveOverlay(EmulatorOverlay.REWIND_WINDOW)
        binding.root.transitionToState(R.id.rewind_hidden)
        viewModel.resumeEmulator()
    }

    private fun updateOrientation(configuration: Configuration) {
        val orientation = if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Orientation.PORTRAIT
        } else {
            Orientation.LANDSCAPE
        }
        viewModel.setSystemOrientation(orientation)
    }

    override fun onPause() {
        super.onPause()
        cancelStartupPresentationRefreshes()
        enableScreenTimeOut()
        choreographerFrameRenderer.stopRendering()
        viewModel.pauseEmulator(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientation(newConfig)
        // There is an issue in which, after moving the app to a different display, the app reports that it is still running on the previous display. Adding a frame of delay
        // seems to fix the problem.
        handler.post {
            updateDisplays()
        }
    }

    override fun onStop() {
        super.onStop()
        cancelStartupPresentationRefreshes()
        getSystemService<DisplayManager>()?.unregisterDisplayListener(displayListener)
        getSystemService<InputManager>()?.unregisterInputDeviceListener(connectedControllerManager)
        connectedControllerManager.stopTrackingControllers()
        frameRenderCoordinator.removeSurface(binding.surfaceMain)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelStartupPresentationRefreshes()
        offlineSyncChoiceDialog?.dismiss()
        offlineSyncProgressDialog?.dismiss()
        hardcorePendingExitDialog?.dismiss()
        frameRenderCoordinator.stop()
        presentation?.dismiss()
    }
}

private const val RENDERER_2D_NATIVE_MODE = -1
private const val RENDERER_2D_STATE_SIZE = 13
private const val RENDERER_3D_STATE_SIZE = 1

private data class RendererDebugMenuEntry(
    val title: String,
    val action: () -> Unit,
)

private data class Renderer2DDebugControlState(
    var mainForcedMode: Int = RENDERER_2D_NATIVE_MODE,
    var subForcedMode: Int = RENDERER_2D_NATIVE_MODE,
    var topForcedCompMode: Int = RENDERER_2D_NATIVE_MODE,
    var bottomForcedCompMode: Int = RENDERER_2D_NATIVE_MODE,
    var disabledMainBgMask: Int = 0,
    var disabledSubBgMask: Int = 0,
    var disabledMainBgPriorityMask: Int = 0,
    var disabledSubBgPriorityMask: Int = 0,
    var disabledMainObjPriorityMask: Int = 0,
    var disabledSubObjPriorityMask: Int = 0,
    var disabledMainObjOrderMask: Int = 0,
    var disabledSubObjOrderMask: Int = 0,
    var featureMask: Int = RendererDebugBridge.RENDERER_2D_DEBUG_FEATURE_ALL,
)

private data class Renderer3DDebugControlState(
    var featureMask: Int = RendererDebugBridge.RENDERER_3D_DEBUG_FEATURE_ALL,
)

private data class Renderer2DModeItem(
    val mode: Int,
    val label: String,
)

private data class Renderer2DCompModeItem(
    val mode: Int,
    val label: String,
)

private data class Renderer2DBgLayerItem(
    val bgIndex: Int,
    val title: String,
    val description: String,
)

private data class Renderer2DPriorityItem(
    val priority: Int,
    val title: String,
    val description: String,
)

private data class Renderer2DObjectOrderItem(
    val bucket: Int,
    val title: String,
    val description: String,
)

private fun renderer2DDebugModeItems(includeMode6: Boolean): List<Renderer2DModeItem> {
    return buildList {
        add(Renderer2DModeItem(RENDERER_2D_NATIVE_MODE, "Native DISPCNT mode - use CurUnit->DispCnt & 0x7"))
        add(Renderer2DModeItem(0, "Mode 0 - 4 Static layers. Code: DrawScanlineBGMode<0>(); BG0-BG3 use DrawBG_Text."))
        add(Renderer2DModeItem(1, "Mode 1 - 3 Static layers + 1 Affine layer. Code: BG0-BG2 DrawBG_Text, BG3 DrawBG_Affine."))
        add(Renderer2DModeItem(2, "Mode 2 - 2 Static layers + 2 Affine layers. Code: BG0/BG1 DrawBG_Text, BG2/BG3 DrawBG_Affine."))
        add(Renderer2DModeItem(3, "Mode 3 - 3 Static layers + 1 Affine Extended layer. Code: BG3 DrawBG_Extended."))
        add(Renderer2DModeItem(4, "Mode 4 - 2 Static layers + 1 Affine layer + 1 Affine Extended layer. Code: BG2 DrawBG_Affine, BG3 DrawBG_Extended."))
        add(Renderer2DModeItem(5, "Mode 5 - 2 Static layers + 2 Affine Extended layers. Code: BG2/BG3 DrawBG_Extended."))
        if (includeMode6) {
            add(Renderer2DModeItem(6, "Mode 6 - 1 3D background layer + 1 Large screen. Code: BG0 DrawBG_3D, BG2 DrawBG_Large. Main only."))
        }
    }
}

private val renderer2DDebugCompModeItems = listOf(
    Renderer2DCompModeItem(RENDERER_2D_NATIVE_MODE, "Native compMode - keep packed control plane"),
    Renderer2DCompModeItem(0, "compMode 0 - sample 3D path, normal composite branch"),
    Renderer2DCompModeItem(1, "compMode 1 - 3D-aware branch with direct 2D/3D selection"),
    Renderer2DCompModeItem(2, "compMode 2 - 3D-aware blend branch"),
    Renderer2DCompModeItem(3, "compMode 3 - 3D-aware alternate blend branch"),
    Renderer2DCompModeItem(4, "compMode 4 - capture-backed 3D placeholder branch"),
    Renderer2DCompModeItem(5, "compMode 5 - reserved/debug passthrough branch"),
    Renderer2DCompModeItem(6, "compMode 6 - reserved/debug passthrough branch"),
    Renderer2DCompModeItem(7, "compMode 7 - no live 3D sample unless temporal fallback is marked"),
)

private val renderer2DDebugBgLayerItems = listOf(
    Renderer2DBgLayerItem(
        bgIndex = 0,
        title = "BG0 - first static or 3D background layer",
        description = "Nintendo DS BG0. Code gate: DISPCNT bit 8; DrawBG_Text, or DrawBG_3D on Engine A when DISPCNT bit 3 selects the 3D background.",
    ),
    Renderer2DBgLayerItem(
        bgIndex = 1,
        title = "BG1 - static background layer",
        description = "Nintendo DS BG1. Code gate: DISPCNT bit 9; currently routes through DrawBG_Text in the software 2D compositor.",
    ),
    Renderer2DBgLayerItem(
        bgIndex = 2,
        title = "BG2 - static, affine, affine extended or large screen background",
        description = "Nintendo DS BG2. Code gate: DISPCNT bit 10; routes through DrawBG_Text, DrawBG_Affine, DrawBG_Extended or DrawBG_Large depending on BG mode and BGCNT.",
    ),
    Renderer2DBgLayerItem(
        bgIndex = 3,
        title = "BG3 - static, affine or affine extended background",
        description = "Nintendo DS BG3. Code gate: DISPCNT bit 11; routes through DrawBG_Text, DrawBG_Affine or DrawBG_Extended depending on BG mode and BGCNT.",
    ),
)

private val renderer2DDebugPriorityItems = listOf(
    Renderer2DPriorityItem(
        priority = 0,
        title = "Priority 0 - frontmost",
        description = "Highest Nintendo DS priority; this layer is drawn closest to the viewer.",
    ),
    Renderer2DPriorityItem(
        priority = 1,
        title = "Priority 1",
        description = "Second-highest Nintendo DS priority.",
    ),
    Renderer2DPriorityItem(
        priority = 2,
        title = "Priority 2",
        description = "Second-lowest Nintendo DS priority.",
    ),
    Renderer2DPriorityItem(
        priority = 3,
        title = "Priority 3 - backmost",
        description = "Lowest Nintendo DS priority; this layer is drawn furthest back.",
    ),
)

private val renderer2DDebugObjectOrderItems = listOf(
    Renderer2DObjectOrderItem(
        bucket = 0,
        title = "OBJ OAM index 0..31 - frontmost order bucket",
        description = "OAM entries 0-31. Code gate: `sprnum / 32` in `DrawSprites`; lower OBJ indices are drawn later for equal priority and usually appear in front.",
    ),
    Renderer2DObjectOrderItem(
        bucket = 1,
        title = "OBJ OAM index 32..63",
        description = "OAM entries 32-63. Code gate: `sprnum / 32` in `DrawSprites`; useful for separating grouped composite sprites with the same OBJ priority.",
    ),
    Renderer2DObjectOrderItem(
        bucket = 2,
        title = "OBJ OAM index 64..95",
        description = "OAM entries 64-95. Code gate: `sprnum / 32` in `DrawSprites`; later than 96-127 but behind lower OAM index buckets at equal priority.",
    ),
    Renderer2DObjectOrderItem(
        bucket = 3,
        title = "OBJ OAM index 96..127 - backmost order bucket",
        description = "OAM entries 96-127. Code gate: `sprnum / 32` in `DrawSprites`; highest OBJ indices are drawn first for equal priority and usually sit furthest back.",
    ),
)
