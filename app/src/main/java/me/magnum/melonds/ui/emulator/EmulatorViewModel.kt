package me.magnum.melonds.ui.emulator

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.common.romprocessors.RomFileProcessorFactory
import me.magnum.melonds.common.runtime.ScreenshotFrameBufferProvider
import me.magnum.melonds.database.daos.RetroAchievementsDao
import me.magnum.melonds.database.entities.retroachievements.RAUserAchievementEntity
import me.magnum.melonds.domain.model.Cheat
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.DualScreenPreset
import me.magnum.melonds.domain.model.FpsCounterPosition
import me.magnum.melonds.domain.model.RomInfo
import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.SCREEN_HEIGHT
import me.magnum.melonds.domain.model.SCREEN_WIDTH
import me.magnum.melonds.domain.model.ScreenAlignment
import me.magnum.melonds.domain.model.defaultExternalAlignment
import me.magnum.melonds.domain.model.defaultInternalAlignment
import me.magnum.melonds.domain.model.emulator.EmulatorEvent
import me.magnum.melonds.domain.model.emulator.EmulatorSessionUpdateAction
import me.magnum.melonds.domain.model.emulator.FirmwareLaunchResult
import me.magnum.melonds.domain.model.emulator.RomLaunchResult
import me.magnum.melonds.domain.model.layout.BackgroundMode
import me.magnum.melonds.domain.model.layout.LayoutConfiguration
import me.magnum.melonds.domain.model.layout.LayoutDisplayPair
import me.magnum.melonds.domain.model.layout.PositionedLayoutComponent
import me.magnum.melonds.domain.model.layout.ScreenFold
import me.magnum.melonds.domain.model.layout.ScreenLayout
import me.magnum.melonds.domain.model.layout.UILayout
import me.magnum.melonds.domain.model.layout.UILayoutVariant
import me.magnum.melonds.domain.model.retroachievements.GameAchievementData
import me.magnum.melonds.domain.model.retroachievements.RAEvent
import me.magnum.melonds.domain.model.retroachievements.RAEvent.RuntimeFallbackReason
import me.magnum.melonds.domain.model.retroachievements.RARuntimeBridgeConfig
import me.magnum.melonds.domain.model.retroachievements.RASimpleAchievement
import me.magnum.melonds.domain.model.input.SoftInputBehaviour
import me.magnum.melonds.domain.model.retroachievements.RASimpleLeaderboard
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.ui.Orientation
import me.magnum.melonds.domain.repositories.BackgroundRepository
import me.magnum.melonds.domain.repositories.CheatsRepository
import me.magnum.melonds.domain.repositories.LayoutsRepository
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SaveStatesRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.domain.services.EmulatorManager
import me.magnum.melonds.impl.emulator.EmulatorSession
import me.magnum.melonds.impl.retroachievements.offline.OfflineLedgerIntegrity
import me.magnum.melonds.impl.retroachievements.offline.OfflineLedgerRepository
import me.magnum.melonds.impl.retroachievements.offline.OfflinePrefetchCacheAchievement
import me.magnum.melonds.impl.retroachievements.offline.OfflinePrefetchCacheFile
import me.magnum.melonds.impl.retroachievements.offline.OfflinePrefetchCacheLeaderboard
import me.magnum.melonds.impl.retroachievements.offline.OfflinePrefetchCacheRepository
import me.magnum.melonds.impl.retroachievements.offline.OfflineUnlockMode
import me.magnum.melonds.impl.retroachievements.offline.OfflineUnlockType
import me.magnum.melonds.impl.retroachievements.offline.HardcoreOfflineLossTracker
import me.magnum.melonds.impl.retroachievements.offline.RetroAchievementsImageCacheWarmer
import me.magnum.melonds.impl.retroachievements.offline.SmartSyncSkipReason
import me.magnum.melonds.impl.retroachievements.offline.SmartSyncEngine
import me.magnum.melonds.impl.layout.UILayoutProvider
import me.magnum.melonds.impl.system.NetworkStatusProvider
import me.magnum.melonds.ui.emulator.firmware.FirmwarePauseMenuOption
import me.magnum.melonds.ui.emulator.model.RumbleEvent
import me.magnum.melonds.ui.emulator.model.EmulatorState
import me.magnum.melonds.ui.emulator.model.EmulatorUiEvent
import me.magnum.melonds.ui.emulator.model.HardcorePendingExitChoice
import me.magnum.melonds.ui.emulator.model.OfflineAchievementsSyncChoice
import me.magnum.melonds.ui.emulator.model.LaunchArgs
import me.magnum.melonds.ui.emulator.model.PauseMenu
import me.magnum.melonds.ui.emulator.model.RAEventUi
import me.magnum.melonds.ui.emulator.model.RAIntegrationEvent
import me.magnum.melonds.ui.emulator.model.RuntimeInputLayoutConfiguration
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import me.magnum.melonds.ui.emulator.model.ToastEvent
import me.magnum.melonds.ui.emulator.rewind.model.RewindSaveState
import me.magnum.melonds.ui.emulator.rom.RomPauseMenuOption
import me.magnum.melonds.utils.EventSharedFlow
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RAAchievementSet
import me.magnum.rcheevosapi.model.RASetId
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val RA_TRACE_TAG = "RATrace"
private const val RA_IDENTITY_TAG = "RAIdentity"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EmulatorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val romsRepository: RomsRepository,
    private val cheatsRepository: CheatsRepository,
    private val retroAchievementsRepository: RetroAchievementsRepository,
    private val retroAchievementsDao: RetroAchievementsDao,
    private val offlineLedgerRepository: OfflineLedgerRepository,
    private val offlinePrefetchCacheRepository: OfflinePrefetchCacheRepository,
    private val retroAchievementsImageCacheWarmer: RetroAchievementsImageCacheWarmer,
    private val smartSyncEngine: SmartSyncEngine,
    private val hardcoreOfflineLossTracker: HardcoreOfflineLossTracker,
    private val networkStatusProvider: NetworkStatusProvider,
    private val romFileProcessorFactory: RomFileProcessorFactory,
    private val layoutsRepository: LayoutsRepository,
    private val backgroundsRepository: BackgroundRepository,
    private val saveStatesRepository: SaveStatesRepository,
    private val screenshotFrameBufferProvider: ScreenshotFrameBufferProvider,
    private val uiLayoutProvider: UILayoutProvider,
    private val emulatorManager: EmulatorManager,
    private val emulatorSession: EmulatorSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionCoroutineScope = EmulatorSessionCoroutineScope()
    private var raSessionJob: Job? = null

    private enum class RetroAchievementsNetworkMode {
        ONLINE_LIVE,
        OFFLINE_ACCUMULATING,
    }

    private enum class RetroAchievementsSessionMode {
        SOFTCORE,
        HARDCORE,
    }

    private enum class RetroAchievementsRuntimePath(val traceValue: String) {
        DISABLED("disabled"),
        RC_CLIENT("rc_client"),
        LEGACY("legacy"),
        LEGACY_OFFLINE("legacy_offline"),
    }

    private data class RetroAchievementsLaunchDecision(
        val networkMode: RetroAchievementsNetworkMode,
        val sessionMode: RetroAchievementsSessionMode,
        val initialOfflineType: OfflineUnlockType?,
        val isHardcoreEligibleAfterOnlineStart: Boolean,
        val offlineDueToNoInternetAtStart: Boolean,
    )

    private data class OfflineRetroAchievementsSession(
        val userId: String,
        val contentId: String,
        val gameId: Long,
        val unlockMode: OfflineUnlockMode,
        val offlineType: OfflineUnlockType,
        val sessionId: String,
        val startedAtEpochMs: Long,
        var nextOrderIndex: Long,
    )

    private enum class OnlineRetroAchievementsBootstrapSource {
        CACHE,
        NETWORK,
    }

    private data class OnlineRetroAchievementsBootstrap(
        val achievementData: GameAchievementData,
        val source: OnlineRetroAchievementsBootstrapSource,
    )

    private var retroAchievementsNetworkMode: RetroAchievementsNetworkMode = RetroAchievementsNetworkMode.ONLINE_LIVE
    private var retroAchievementsSessionMode: RetroAchievementsSessionMode = RetroAchievementsSessionMode.SOFTCORE
    private var isHardcoreEligibleAfterOnlineStart = false
    private var startedSessionOnlineLive = false
    private var isRetroAchievementsOnlineSessionStarted = false
    private var currentRetroAchievementsGameId: Long? = null
    private var offlineRetroAchievementsSession: OfflineRetroAchievementsSession? = null
    private var activeRuntimeBridgeConfig: RARuntimeBridgeConfig? = null
    private var activeRuntimePath: RetroAchievementsRuntimePath = RetroAchievementsRuntimePath.DISABLED
    private var didShowRuntimeFallbackEvent = false
    private val announcedMasteryKeys = mutableSetOf<Pair<Long, Boolean>>()
    private val pendingRuntimeAchievementTriggers = mutableMapOf<Long, Long>()
    private val pendingRuntimeLeaderboardCompletions = mutableMapOf<Long, Long>()

    private var offlineSyncChoiceDeferred: CompletableDeferred<OfflineAchievementsSyncChoice>? = null
    private var hardcoreExitChoiceDeferred: CompletableDeferred<HardcorePendingExitChoice>? = null

    private val _emulatorState = MutableStateFlow<EmulatorState>(EmulatorState.Uninitialized)
    val emulatorState = _emulatorState.asStateFlow()

    private val _layout = MutableStateFlow<LayoutConfiguration?>(null)

    private val _runtimeLayout = MutableStateFlow<RuntimeInputLayoutConfiguration?>(null)
    val runtimeLayout = _runtimeLayout.asStateFlow()

    val controllerConfiguration = settingsRepository.observeControllerConfiguration()

    private val _runtimeRendererConfiguration = MutableStateFlow<RuntimeRendererConfiguration?>(null)
    val runtimeRendererConfiguration = _runtimeRendererConfiguration.asStateFlow()

    private val _mainScreenBackground = MutableStateFlow(RuntimeBackground.None)
    val mainScreenBackground = _mainScreenBackground.asStateFlow()

    private val _secondaryScreenBackground = MutableStateFlow(RuntimeBackground.None)
    val secondaryScreenBackground = _secondaryScreenBackground.asStateFlow()

    private val _rumbleEvent = MutableSharedFlow<RumbleEvent>(extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val rumbleEvent = _rumbleEvent.asSharedFlow()

    private val _achievementsEvent = MutableSharedFlow<RAEventUi>(extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.SUSPEND)
    val achievementsEvent = _achievementsEvent.asSharedFlow()

    private val _currentFps = MutableStateFlow<Int?>(null)
    val currentFps = _currentFps.asStateFlow()

    private val _toastEvent = EventSharedFlow<ToastEvent>()
    val toastEvent = _toastEvent.asSharedFlow()

    private val retroAchievementsVersionName: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
    }

    private val retroAchievementsUserAgent: String by lazy {
        "melonDualDS-android/$retroAchievementsVersionName"
    }

    private val _raIntegrationEvent = EventSharedFlow<RAIntegrationEvent>()
    val integrationEvent = _raIntegrationEvent.asSharedFlow()

    private val _uiEvent = EventSharedFlow<EmulatorUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    private val _externalDisplayKeepAspectRatioEnabled = MutableStateFlow(settingsRepository.isExternalDisplayKeepAspectRationEnabled())
    val externalDisplayKeepAspectRatioEnabled = _externalDisplayKeepAspectRatioEnabled.asStateFlow()

    private val _dualScreenPreset = MutableStateFlow(settingsRepository.getDualScreenPreset())
    val dualScreenPreset = _dualScreenPreset.asStateFlow()

    private val _dualScreenIntegerScaleEnabled = MutableStateFlow(settingsRepository.isDualScreenIntegerScaleEnabled())
    val dualScreenIntegerScaleEnabled = _dualScreenIntegerScaleEnabled.asStateFlow()

    private val _dualScreenInternalFillHeightEnabled = MutableStateFlow(settingsRepository.isDualScreenInternalFillHeightEnabled())
    val dualScreenInternalFillHeightEnabled = _dualScreenInternalFillHeightEnabled.asStateFlow()

    private val _dualScreenInternalFillWidthEnabled = MutableStateFlow(settingsRepository.isDualScreenInternalFillWidthEnabled())
    val dualScreenInternalFillWidthEnabled = _dualScreenInternalFillWidthEnabled.asStateFlow()

    private val _dualScreenExternalFillHeightEnabled = MutableStateFlow(settingsRepository.isDualScreenExternalFillHeightEnabled())
    val dualScreenExternalFillHeightEnabled = _dualScreenExternalFillHeightEnabled.asStateFlow()

    private val _dualScreenExternalFillWidthEnabled = MutableStateFlow(settingsRepository.isDualScreenExternalFillWidthEnabled())
    val dualScreenExternalFillWidthEnabled = _dualScreenExternalFillWidthEnabled.asStateFlow()

    private val _dualScreenInternalVerticalAlignmentOverride = MutableStateFlow(settingsRepository.getDualScreenInternalVerticalAlignmentOverride())
    val dualScreenInternalVerticalAlignmentOverride = _dualScreenInternalVerticalAlignmentOverride.asStateFlow()

    private val _dualScreenExternalVerticalAlignmentOverride = MutableStateFlow(settingsRepository.getDualScreenExternalVerticalAlignmentOverride())
    val dualScreenExternalVerticalAlignmentOverride = _dualScreenExternalVerticalAlignmentOverride.asStateFlow()

    private var currentRom: Rom? = null
    init {
        viewModelScope.launch {
            _layout.filterNotNull().collect {
                uiLayoutProvider.setCurrentLayoutConfiguration(it)
            }
        }

        viewModelScope.launch {
            settingsRepository.observeExternalDisplayKeepAspectRationEnabled().collectLatest {
                _externalDisplayKeepAspectRatioEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenPreset().collectLatest {
                _dualScreenPreset.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenIntegerScaleEnabled().collectLatest {
                _dualScreenIntegerScaleEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenInternalFillHeightEnabled().collectLatest {
                _dualScreenInternalFillHeightEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenInternalFillWidthEnabled().collectLatest {
                _dualScreenInternalFillWidthEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenExternalFillHeightEnabled().collectLatest {
                _dualScreenExternalFillHeightEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenExternalFillWidthEnabled().collectLatest {
                _dualScreenExternalFillWidthEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenInternalVerticalAlignmentOverride().collectLatest {
                _dualScreenInternalVerticalAlignmentOverride.value = it
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDualScreenExternalVerticalAlignmentOverride().collectLatest {
                _dualScreenExternalVerticalAlignmentOverride.value = it
            }
        }

        val launchArgs = LaunchArgs.fromSavedStateHandle(savedStateHandle)
        if (launchArgs != null) {
            launchEmulator(launchArgs)
        } else {
            _uiEvent.tryEmit(EmulatorUiEvent.CloseEmulator)
        }
    }

    fun relaunchWithNewArgs(args: LaunchArgs) {
        if (!_emulatorState.value.isRunning()) {
            launchEmulator(args)
            return
        }

        sessionCoroutineScope.launch {
            val runningRom = _emulatorState.value as? EmulatorState.RunningRom
            if (runningRom != null) {
                val userAuth = retroAchievementsRepository.getUserAuthentication()
                if (userAuth != null) {
                    val userId = userAuth.username
                    val contentId = runningRom.rom.retroAchievementsHash
                    val ledgerStatus = withContext(Dispatchers.IO) {
                        offlineLedgerRepository.getStatus(userId, contentId)
                    }

                    if (ledgerStatus.integrity == OfflineLedgerIntegrity.OK) {
                        if (ledgerStatus.hasPendingHardcoreUnlocks) {
                            val shouldExit = handleHardcorePendingBeforeExit(userId, contentId)
                            if (!shouldExit) {
                                emulatorManager.resumeEmulator()
                                return@launch
                            }
                        } else {
                            hardcoreOfflineLossTracker.clearPendingUnlocks(userId, contentId)
                            if (ledgerStatus.pendingSoftcoreUnlockCount > 0) {
                                _toastEvent.tryEmit(
                                    ToastEvent.OfflineSoftcorePendingNotice(ledgerStatus.pendingSoftcoreUnlockCount)
                                )
                            }
                        }
                    }
                }
            }

            stopEmulator()
            launchEmulator(args)
        }
    }

    private fun launchEmulator(args: LaunchArgs) {
        when (args) {
            is LaunchArgs.RomObject -> loadRom(args.rom)
            is LaunchArgs.RomUri -> loadRom(args.uri)
            is LaunchArgs.RomPath -> loadRom(args.path)
            is LaunchArgs.Firmware -> loadFirmware(args.consoleType)
        }
    }

    private fun loadRom(rom: Rom) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                launchRom(rom)
            }
        }
    }

    private fun loadRom(romUri: Uri) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                val rom = romsRepository.getRomAtUri(romUri)
                if (rom != null) {
                    launchRom(rom)
                } else {
                    _emulatorState.value = EmulatorState.RomNotFoundError(romUri.toString())
                }
            }
        }
    }

    private fun loadRom(romPath: String) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingRom)
            sessionCoroutineScope.launch {
                val rom = romsRepository.getRomAtPath(romPath)
                if (rom != null) {
                    launchRom(rom)
                } else {
                    _emulatorState.value = EmulatorState.RomNotFoundError(romPath)
                }
            }
        }
    }

    private suspend fun launchRom(rom: Rom) = coroutineScope {
        currentRom = rom
        val launchDecision = decideRetroAchievementsLaunchDecision(rom)

        retroAchievementsNetworkMode = launchDecision.networkMode
        retroAchievementsSessionMode = launchDecision.sessionMode
        isHardcoreEligibleAfterOnlineStart = launchDecision.isHardcoreEligibleAfterOnlineStart
        startedSessionOnlineLive = launchDecision.networkMode == RetroAchievementsNetworkMode.ONLINE_LIVE

        startEmulatorSession(
            sessionType = EmulatorSession.SessionType.RomSession(rom),
            isRetroAchievementsHardcoreModeEnabled = launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE,
        )
        startObservingMainScreenBackground()
        startObservingSecondaryScreenBackground()
        startObservingRuntimeInputLayoutConfiguration()
        startObservingRendererConfiguration()
        startObservingEmulatorEvents()
        startObservingAchievementEvents()
        startObservingLayoutForRom(rom)
        startRetroAchievementsSession(rom, launchDecision)

        val cheats = getRomInfo(rom)?.let { getRomEnabledCheats(it) } ?: emptyList()
        val result = emulatorManager.loadRom(rom, cheats)
        when (result) {
            is RomLaunchResult.LaunchFailedRomNotFound,
            is RomLaunchResult.LaunchFailedSramProblem,
            is RomLaunchResult.LaunchFailed -> {
                _emulatorState.value = EmulatorState.RomLoadError
            }
            is RomLaunchResult.LaunchSuccessful -> {
                if (!result.isGbaLoadSuccessful) {
                    _toastEvent.tryEmit(ToastEvent.GbaLoadFailed)
                }
                _emulatorState.value = EmulatorState.RunningRom(rom)
                startTrackingFps()
                startTrackingPlayTime(rom)
            }
        }
    }

    fun submitOfflineAchievementsSyncChoice(choice: OfflineAchievementsSyncChoice) {
        offlineSyncChoiceDeferred?.complete(choice)
    }

    fun submitHardcorePendingExitChoice(choice: HardcorePendingExitChoice) {
        hardcoreExitChoiceDeferred?.complete(choice)
    }

    private suspend fun decideRetroAchievementsLaunchDecision(rom: Rom): RetroAchievementsLaunchDecision {
        val startedOnline = networkStatusProvider.isOnline()
        val hardcoreSettingEnabled = settingsRepository.isRetroAchievementsHardcoreEnabled()
        val userAuth = retroAchievementsRepository.getUserAuthentication()

        if (userAuth == null) {
            return RetroAchievementsLaunchDecision(
                networkMode = RetroAchievementsNetworkMode.ONLINE_LIVE,
                sessionMode = RetroAchievementsSessionMode.SOFTCORE,
                initialOfflineType = null,
                isHardcoreEligibleAfterOnlineStart = false,
                offlineDueToNoInternetAtStart = false,
            )
        }

        val userId = userAuth.username
        val contentId = rom.retroAchievementsHash

        var ledgerStatus = withContext(Dispatchers.IO) {
            offlineLedgerRepository.getStatus(userId, contentId)
        }
        var ignoreLedgerForThisLaunch = false

        if (ledgerStatus.integrity != OfflineLedgerIntegrity.OK && ledgerStatus.integrity != OfflineLedgerIntegrity.EMPTY) {
            _toastEvent.tryEmit(ToastEvent.OfflineAchievementsLedgerTampered)

            val resetResult = withContext(Dispatchers.IO) {
                offlineLedgerRepository.resetLedger(userId, contentId)
            }
            if (resetResult.isSuccess) {
                hardcoreOfflineLossTracker.clearPendingUnlocks(userId, contentId)
                ledgerStatus = withContext(Dispatchers.IO) {
                    offlineLedgerRepository.getStatus(userId, contentId)
                }
            } else {
                ignoreLedgerForThisLaunch = true
            }
        }

        if (ledgerStatus.integrity == OfflineLedgerIntegrity.OK && ledgerStatus.hasPendingHardcoreUnlocks) {
            hardcoreOfflineLossTracker.markPendingUnlocks(
                userId = userId,
                contentId = contentId,
                gameTitle = rom.name,
            )
        }

        if (!startedOnline) {
            return RetroAchievementsLaunchDecision(
                networkMode = RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING,
                sessionMode = RetroAchievementsSessionMode.SOFTCORE,
                initialOfflineType = OfflineUnlockType.OFFLINE_FROM_START,
                isHardcoreEligibleAfterOnlineStart = false,
                offlineDueToNoInternetAtStart = true,
            )
        }

        if (ignoreLedgerForThisLaunch || ledgerStatus.integrity != OfflineLedgerIntegrity.OK || ledgerStatus.pendingSoftcoreUnlockCount <= 0) {
            return RetroAchievementsLaunchDecision(
                networkMode = RetroAchievementsNetworkMode.ONLINE_LIVE,
                sessionMode = if (hardcoreSettingEnabled) RetroAchievementsSessionMode.HARDCORE else RetroAchievementsSessionMode.SOFTCORE,
                initialOfflineType = null,
                isHardcoreEligibleAfterOnlineStart = hardcoreSettingEnabled,
                offlineDueToNoInternetAtStart = false,
            )
        }

        val choice = awaitOfflineAchievementsSyncChoice(ledgerStatus.pendingSoftcoreUnlockCount)
        return when (choice) {
            OfflineAchievementsSyncChoice.CONTINUE_OFFLINE -> RetroAchievementsLaunchDecision(
                networkMode = RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING,
                sessionMode = RetroAchievementsSessionMode.SOFTCORE,
                initialOfflineType = OfflineUnlockType.OFFLINE_FROM_START,
                isHardcoreEligibleAfterOnlineStart = false,
                offlineDueToNoInternetAtStart = false,
            )
            OfflineAchievementsSyncChoice.SYNC_NOW -> {
                if (syncPendingOfflineAchievements(userId, contentId, ledgerStatus.pendingSoftcoreUnlockCount)) {
                    RetroAchievementsLaunchDecision(
                        networkMode = RetroAchievementsNetworkMode.ONLINE_LIVE,
                        sessionMode = if (hardcoreSettingEnabled) RetroAchievementsSessionMode.HARDCORE else RetroAchievementsSessionMode.SOFTCORE,
                        initialOfflineType = null,
                        isHardcoreEligibleAfterOnlineStart = hardcoreSettingEnabled,
                        offlineDueToNoInternetAtStart = false,
                    )
                } else {
                    RetroAchievementsLaunchDecision(
                        networkMode = RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING,
                        sessionMode = RetroAchievementsSessionMode.SOFTCORE,
                        initialOfflineType = OfflineUnlockType.OFFLINE_FROM_START,
                        isHardcoreEligibleAfterOnlineStart = false,
                        offlineDueToNoInternetAtStart = false,
                    )
                }
            }
        }
    }

    private suspend fun syncPendingOfflineAchievements(
        userId: String,
        contentId: String,
        pendingUnlockCount: Int,
    ): Boolean {
        if (!networkStatusProvider.isOnline()) {
            return false
        }
        _uiEvent.emit(EmulatorUiEvent.ShowOfflineAchievementsSyncProgress(pendingUnlockCount))
        val syncResult = smartSyncEngine.syncSoftcoreNow(userId, contentId)
        _uiEvent.emit(EmulatorUiEvent.HideOfflineAchievementsSyncProgress)
        if (syncResult.isSuccess) {
            val skipped = syncResult.getOrNull()?.skipped.orEmpty()
            emitOfflineAchievementsNotSyncedToasts(skipped)
            return true
        }
        return false
    }

    private suspend fun awaitOfflineAchievementsSyncChoice(
        pendingUnlockCount: Int,
    ): OfflineAchievementsSyncChoice {
        offlineSyncChoiceDeferred?.cancel()
        val deferred = CompletableDeferred<OfflineAchievementsSyncChoice>()
        offlineSyncChoiceDeferred = deferred
        _uiEvent.emit(
            EmulatorUiEvent.ShowOfflineAchievementsSyncChoice(
                pendingUnlockCount = pendingUnlockCount,
            )
        )
        return deferred.await().also {
            if (offlineSyncChoiceDeferred === deferred) {
                offlineSyncChoiceDeferred = null
            }
        }
    }

    private suspend fun awaitHardcorePendingExitChoice(
        pendingHardcoreCount: Int,
    ): HardcorePendingExitChoice {
        hardcoreExitChoiceDeferred?.cancel()
        val deferred = CompletableDeferred<HardcorePendingExitChoice>()
        hardcoreExitChoiceDeferred = deferred
        _uiEvent.emit(EmulatorUiEvent.ShowHardcorePendingExitWarning(pendingHardcoreCount))
        return deferred.await().also {
            if (hardcoreExitChoiceDeferred === deferred) {
                hardcoreExitChoiceDeferred = null
            }
        }
    }

    private suspend fun emitOfflineAchievementsNotSyncedToasts(skipped: List<me.magnum.melonds.impl.retroachievements.offline.SmartSyncSkippedAchievement>) {
        if (skipped.isEmpty()) return

        val maxIndividualToasts = 3
        val individual = skipped.take(maxIndividualToasts)

        individual.forEach { skip ->
            val title = retroAchievementsRepository.getAchievement(skip.achievementId).getOrNull()?.getCleanTitle()
                ?: "#${skip.achievementId}"

            val reason = when (skip.reason) {
                SmartSyncSkipReason.MISSING_FROM_CURRENT_SET -> ToastEvent.OfflineAchievementNotSyncedReason.MISSING_FROM_CURRENT_SET
                SmartSyncSkipReason.DEFINITION_CHANGED -> ToastEvent.OfflineAchievementNotSyncedReason.DEFINITION_CHANGED
                SmartSyncSkipReason.NOT_IN_PREFETCH_CACHE -> ToastEvent.OfflineAchievementNotSyncedReason.NOT_IN_PREFETCH_CACHE
            }

            _toastEvent.tryEmit(ToastEvent.OfflineAchievementNotSynced(title = title, reason = reason))
        }

        val remaining = skipped.size - individual.size
        if (remaining > 0) {
            _toastEvent.tryEmit(ToastEvent.OfflineAchievementsNotSyncedSummary(skippedCount = remaining))
        }
    }

    private fun loadFirmware(consoleType: ConsoleType) {
        viewModelScope.launch {
            resetEmulatorState(EmulatorState.LoadingFirmware)
            startEmulatorSession(EmulatorSession.SessionType.FirmwareSession(consoleType))
            sessionCoroutineScope.launch {
                startObservingMainScreenBackground()
                startObservingSecondaryScreenBackground()
                startObservingRuntimeInputLayoutConfiguration()
                startObservingRendererConfiguration()
                startObservingLayoutForFirmware()
                startObservingEmulatorEvents()

                val result = emulatorManager.loadFirmware(consoleType)
                when (result) {
                    is FirmwareLaunchResult.LaunchFailed -> {
                        _emulatorState.value = EmulatorState.FirmwareLoadError(result.reason)
                    }
                    FirmwareLaunchResult.LaunchSuccessful -> {
                        _emulatorState.value = EmulatorState.RunningFirmware(consoleType)
                        startTrackingFps()
                    }
                }
            }
        }
    }

    fun setSystemOrientation(orientation: Orientation) {
        uiLayoutProvider.updateCurrentOrientation(orientation)
    }

    fun setUiSize(width: Int, height: Int) {
        uiLayoutProvider.updateUiSize(width, height)
    }

    fun setScreenFolds(folds: List<ScreenFold>) {
        uiLayoutProvider.updateFolds(folds)
    }

    fun setConnectedDisplays(displays: LayoutDisplayPair) {
        uiLayoutProvider.updateDisplays(displays)
    }

    fun setExternalDisplayKeepAspectRatioEnabled(enabled: Boolean) {
        _externalDisplayKeepAspectRatioEnabled.value = enabled
        settingsRepository.setExternalDisplayKeepAspectRatioEnabled(enabled)
    }

    fun setDualScreenPreset(preset: DualScreenPreset) {
        _dualScreenPreset.value = preset
        settingsRepository.setDualScreenPreset(preset)
    }

    fun setDualScreenIntegerScaleEnabled(enabled: Boolean) {
        _dualScreenIntegerScaleEnabled.value = enabled
        settingsRepository.setDualScreenIntegerScaleEnabled(enabled)
    }

    fun setDualScreenInternalFillHeightEnabled(enabled: Boolean) {
        _dualScreenInternalFillHeightEnabled.value = enabled
        settingsRepository.setDualScreenInternalFillHeightEnabled(enabled)
    }

    fun setDualScreenInternalFillWidthEnabled(enabled: Boolean) {
        _dualScreenInternalFillWidthEnabled.value = enabled
        settingsRepository.setDualScreenInternalFillWidthEnabled(enabled)
    }

    fun setDualScreenExternalFillHeightEnabled(enabled: Boolean) {
        _dualScreenExternalFillHeightEnabled.value = enabled
        settingsRepository.setDualScreenExternalFillHeightEnabled(enabled)
    }

    fun setDualScreenExternalFillWidthEnabled(enabled: Boolean) {
        _dualScreenExternalFillWidthEnabled.value = enabled
        settingsRepository.setDualScreenExternalFillWidthEnabled(enabled)
    }

    fun setDualScreenInternalVerticalAlignmentOverride(alignment: ScreenAlignment?) {
        _dualScreenInternalVerticalAlignmentOverride.value = alignment
        settingsRepository.setDualScreenInternalVerticalAlignmentOverride(alignment)
    }

    fun setDualScreenExternalVerticalAlignmentOverride(alignment: ScreenAlignment?) {
        _dualScreenExternalVerticalAlignmentOverride.value = alignment
        settingsRepository.setDualScreenExternalVerticalAlignmentOverride(alignment)
    }

    fun onAppMovedToBackground() {
        sessionCoroutineScope.launch {
            val runningRom = _emulatorState.value as? EmulatorState.RunningRom ?: return@launch
            val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return@launch
            val ledgerStatus = withContext(Dispatchers.IO) {
                offlineLedgerRepository.getStatus(userAuth.username, runningRom.rom.retroAchievementsHash)
            }
            if (ledgerStatus.integrity == OfflineLedgerIntegrity.OK && ledgerStatus.hasPendingHardcoreUnlocks) {
                _toastEvent.tryEmit(ToastEvent.HardcoreOfflineUnsyncedWarning(ledgerStatus.pendingHardcoreUnlockCount))
            }
        }
    }

    fun onSettingsChanged() {
        val currentState = _emulatorState.value
        sessionCoroutineScope.launch {
            val sessionUpdateActions = emulatorSession.updateRetroAchievementsSettings(
                retroAchievementsRepository.isUserAuthenticated(),
                settingsRepository.isRetroAchievementsHardcoreEnabled(),
            )

            when (currentState) {
                is EmulatorState.RunningRom -> emulatorManager.updateRomEmulatorConfiguration(currentState.rom)
                is EmulatorState.RunningFirmware -> emulatorManager.updateFirmwareEmulatorConfiguration(currentState.console)
                else -> {
                    // Do nothing
                }
            }

            dispatchSessionUpdateActions(sessionUpdateActions)
        }
    }

    fun onCheatsChanged() {
        val rom = (_emulatorState.value as? EmulatorState.RunningRom)?.rom ?: return

        getRomInfo(rom)?.let {
            sessionCoroutineScope.launch {
                val cheats = getRomEnabledCheats(it)
                emulatorManager.updateCheats(cheats)
            }
        }
    }

    fun pauseEmulator(showPauseMenu: Boolean) {
        sessionCoroutineScope.launch {
            emulatorManager.pauseEmulator()
            if (showPauseMenu) {
                val pauseOptions = when (_emulatorState.value) {
                    is EmulatorState.RunningRom -> {
                        RomPauseMenuOption.entries.filter {
                            filterRomPauseMenuOption(it)
                        }
                    }
                    is EmulatorState.RunningFirmware -> {
                        FirmwarePauseMenuOption.entries
                    }
                    else -> null
                }

                if (pauseOptions != null) {
                    _uiEvent.emit(EmulatorUiEvent.ShowPauseMenu(PauseMenu(pauseOptions)))
                }
            }
        }
    }

    fun resumeEmulator() {
        sessionCoroutineScope.launch {
            emulatorManager.resumeEmulator()
        }
    }

    fun resetEmulator() {
        if (_emulatorState.value.isRunning()) {
            sessionCoroutineScope.launch {
                emulatorManager.resetEmulator()
                _achievementsEvent.emit(RAEventUi.Reset)
            }
        }
    }

    fun stopEmulator() {
        viewModelScope.launch {
            _achievementsEvent.emit(RAEventUi.Reset)
        }
        finalizeOfflineRetroAchievementsSessionIfNeeded()
        emulatorManager.stopEmulator()
        screenshotFrameBufferProvider.clearBuffer()
    }

    private fun finalizeOfflineRetroAchievementsSessionIfNeeded() {
        val offlineSession = offlineRetroAchievementsSession ?: return
        offlineRetroAchievementsSession = null

        val endedAtEpochMs = System.currentTimeMillis()
        val estimatedPlayDurationMs = (endedAtEpochMs - offlineSession.startedAtEpochMs).coerceAtLeast(0L)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                offlineLedgerRepository.appendSessionEnd(
                    userId = offlineSession.userId,
                    contentId = offlineSession.contentId,
                    gameId = offlineSession.gameId,
                    sessionId = offlineSession.sessionId,
                    endedAtEpochMs = endedAtEpochMs,
                    estimatedPlayDurationMs = estimatedPlayDurationMs,
                    isHardcore = offlineSession.unlockMode == OfflineUnlockMode.HARDCORE,
                    unlockMode = offlineSession.unlockMode,
                    offlineType = offlineSession.offlineType,
                )
            }
        }
    }

    private fun requestExitRom() {
        sessionCoroutineScope.launch {
            val runningRom = _emulatorState.value as? EmulatorState.RunningRom
            if (runningRom == null) {
                stopEmulator()
                _uiEvent.emit(EmulatorUiEvent.CloseEmulator)
                return@launch
            }

            val userAuth = retroAchievementsRepository.getUserAuthentication()
            if (userAuth == null) {
                stopEmulator()
                _uiEvent.emit(EmulatorUiEvent.CloseEmulator)
                return@launch
            }

            val userId = userAuth.username
            val contentId = runningRom.rom.retroAchievementsHash
            val ledgerStatus = withContext(Dispatchers.IO) {
                offlineLedgerRepository.getStatus(userId, contentId)
            }

            if (ledgerStatus.integrity == OfflineLedgerIntegrity.OK) {
                if (ledgerStatus.hasPendingHardcoreUnlocks) {
                    val shouldExit = handleHardcorePendingBeforeExit(userId, contentId)
                    if (!shouldExit) {
                        emulatorManager.resumeEmulator()
                        return@launch
                    }
                } else {
                    hardcoreOfflineLossTracker.clearPendingUnlocks(userId, contentId)
                    if (ledgerStatus.pendingSoftcoreUnlockCount > 0) {
                        _toastEvent.tryEmit(
                            ToastEvent.OfflineSoftcorePendingNotice(ledgerStatus.pendingSoftcoreUnlockCount)
                        )
                    }
                }
            }

            stopEmulator()
            _uiEvent.emit(EmulatorUiEvent.CloseEmulator)
        }
    }

    private suspend fun handleHardcorePendingBeforeExit(
        userId: String,
        contentId: String,
    ): Boolean {
        while (true) {
            val ledgerStatus = withContext(Dispatchers.IO) {
                offlineLedgerRepository.getStatus(userId, contentId)
            }
            if (ledgerStatus.integrity != OfflineLedgerIntegrity.OK || !ledgerStatus.hasPendingHardcoreUnlocks) {
                return true
            }

            val choice = awaitHardcorePendingExitChoice(ledgerStatus.pendingHardcoreUnlockCount)
            when (choice) {
                HardcorePendingExitChoice.CONTINUE_PLAYING -> return false
                HardcorePendingExitChoice.EXIT_ANYWAY -> {
                    val discardResult = withContext(Dispatchers.IO) {
                        offlineLedgerRepository.discardPendingHardcoreUnlocks(userId, contentId)
                    }
                    if (discardResult.isFailure) {
                        _toastEvent.tryEmit(ToastEvent.OfflineAchievementsSyncFailed)
                    } else {
                        hardcoreOfflineLossTracker.clearPendingUnlocks(userId, contentId)
                        return true
                    }
                }
            }
        }
    }

    private fun stopEmulatorAndExit() {
        stopEmulator()
        _uiEvent.tryEmit(EmulatorUiEvent.CloseEmulator)
    }

    private fun startTrackingPlayTime(rom: Rom) {
        sessionCoroutineScope.launch {
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                romsRepository.addRomPlayTime(rom, (now - lastTime).milliseconds)
                lastTime = now
            }
        }
    }

    fun onPauseMenuOptionSelected(option: PauseMenuOption) {
        when (option) {
            is RomPauseMenuOption -> {
                when (option) {
                    RomPauseMenuOption.SETTINGS -> _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.SettingsScreen)
                    RomPauseMenuOption.SAVE_STATE -> {
                        if (emulatorSession.areSaveStatesAllowed()) {
                            (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                                val saveStateSlots = getRomSaveStateSlots(it.rom)
                                _uiEvent.tryEmit(EmulatorUiEvent.ShowRomSaveStates(saveStateSlots, EmulatorUiEvent.ShowRomSaveStates.Reason.SAVING))
                            }
                        }
                    }
                    RomPauseMenuOption.LOAD_STATE -> {
                        if (emulatorSession.areSaveStateLoadsAllowed()) {
                            (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                                val saveStateSlots = getRomSaveStateSlots(it.rom)
                                _uiEvent.tryEmit(EmulatorUiEvent.ShowRomSaveStates(saveStateSlots, EmulatorUiEvent.ShowRomSaveStates.Reason.LOADING))
                            }
                        } else {
                            _toastEvent.tryEmit(ToastEvent.CannotLoadSaveStatesWhenRAHardcoreIsEnabled)
                        }
                    }
                    RomPauseMenuOption.REWIND -> {
                        if (!settingsRepository.isRewindEnabled()) {
                            _toastEvent.tryEmit(ToastEvent.RewindNotEnabled)
                        } else if (!emulatorSession.areSaveStateLoadsAllowed()) {
                            _toastEvent.tryEmit(ToastEvent.RewindNotAvailableWhileRAHardcoreModeEnabled)
                        } else {
                            sessionCoroutineScope.launch {
                                val rewindWindow = emulatorManager.getRewindWindow()
                                _uiEvent.emit(EmulatorUiEvent.ShowRewindWindow(rewindWindow))
                            }
                        }
                    }
                    RomPauseMenuOption.CHEATS -> {
                        if (emulatorSession.areCheatsEnabled()) {
                            (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                                getRomInfo(it.rom)?.let { romInfo ->
                                    _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.CheatsScreen(romInfo))
                                }
                            }
                        } else {
                            _toastEvent.tryEmit(ToastEvent.CannotUseCheatsWhenRAHardcoreIsEnabled)
                        }
                    }
                    RomPauseMenuOption.VIEW_ACHIEVEMENTS -> _uiEvent.tryEmit(EmulatorUiEvent.ShowAchievementList)
                    RomPauseMenuOption.PRESETS -> _uiEvent.tryEmit(EmulatorUiEvent.ShowDualScreenPresets)
                    RomPauseMenuOption.RESET -> resetEmulator()
                    RomPauseMenuOption.EXIT -> {
                        requestExitRom()
                    }
                }
            }
            is FirmwarePauseMenuOption -> {
                when (option) {
                    FirmwarePauseMenuOption.SETTINGS -> _uiEvent.tryEmit(EmulatorUiEvent.OpenScreen.SettingsScreen)
                    FirmwarePauseMenuOption.RESET -> resetEmulator()
                    FirmwarePauseMenuOption.EXIT -> {
                        stopEmulator()
                        _uiEvent.tryEmit(EmulatorUiEvent.CloseEmulator)
                    }
                }
            }
        }
    }

    fun onOpenRewind() {
        if (!settingsRepository.isRewindEnabled()) {
            _toastEvent.tryEmit(ToastEvent.RewindNotEnabled)
            return
        }

        if (!emulatorSession.areSaveStateLoadsAllowed()) {
            _toastEvent.tryEmit(ToastEvent.RewindNotAvailableWhileRAHardcoreModeEnabled)
            return
        }

        sessionCoroutineScope.launch {
            emulatorManager.pauseEmulator()
            val rewindWindow = emulatorManager.getRewindWindow()
            _uiEvent.emit(EmulatorUiEvent.ShowRewindWindow(rewindWindow))
        }
    }

    fun onFastForwardToggleRequested(): Boolean {
        return true
    }

    fun rewindToState(rewindSaveState: RewindSaveState) {
        if (!emulatorSession.areSaveStateLoadsAllowed()) {
            _toastEvent.tryEmit(ToastEvent.RewindNotAvailableWhileRAHardcoreModeEnabled)
            return
        }

        sessionCoroutineScope.launch {
            emulatorManager.loadRewindState(rewindSaveState)
        }
    }

    fun saveStateToSlot(slot: SaveStateSlot) {
        sessionCoroutineScope.launch(Dispatchers.IO) {
            (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                if (!saveRomState(it.rom, slot)) {
                    _toastEvent.emit(ToastEvent.StateSaveFailed)
                }
                emulatorManager.resumeEmulator()
            }
        }
    }

    fun loadStateFromSlot(slot: SaveStateSlot) {
        if (!emulatorSession.areSaveStateLoadsAllowed()) {
            _toastEvent.tryEmit(ToastEvent.CannotLoadSaveStatesWhenRAHardcoreIsEnabled)
            return
        }

        if (!slot.exists) {
            _toastEvent.tryEmit(ToastEvent.StateStateDoesNotExist)
        } else {
            sessionCoroutineScope.launch {
                (_emulatorState.value as? EmulatorState.RunningRom)?.let {
                    if (!loadRomState(it.rom, slot)) {
                        _toastEvent.emit(ToastEvent.StateLoadFailed)
                    }
                    emulatorManager.resumeEmulator()
                }
            }
        }
    }

    fun doQuickSave() {
        val currentState = _emulatorState.value
        when (currentState) {
            is EmulatorState.RunningRom -> {
                sessionCoroutineScope.launch {
                    emulatorManager.pauseEmulator()
                    val quickSlot = saveStatesRepository.getRomQuickSaveStateSlot(currentState.rom)
                    if (saveRomState(currentState.rom, quickSlot)) {
                        _toastEvent.emit(ToastEvent.QuickSaveSuccessful)
                    }
                    emulatorManager.resumeEmulator()
                }
            }
            is EmulatorState.RunningFirmware -> {
                _toastEvent.tryEmit(ToastEvent.CannotSaveStateWhenRunningFirmware)
            }
            else -> {
                // Do nothing
            }
        }
    }

    fun doQuickLoad() {
        val currentState = _emulatorState.value
        when (currentState) {
            is EmulatorState.RunningRom -> {
                if (emulatorSession.areSaveStateLoadsAllowed()) {
                    sessionCoroutineScope.launch {
                        emulatorManager.pauseEmulator()
                        val quickSlot = saveStatesRepository.getRomQuickSaveStateSlot(currentState.rom)
                        if (loadRomState(currentState.rom, quickSlot)) {
                            _toastEvent.emit(ToastEvent.QuickLoadSuccessful)
                        }
                        emulatorManager.resumeEmulator()
                    }
                } else {
                    _toastEvent.tryEmit(ToastEvent.CannotLoadSaveStatesWhenRAHardcoreIsEnabled)
                }
            }
            is EmulatorState.RunningFirmware -> {
                _toastEvent.tryEmit(ToastEvent.CannotLoadStateWhenRunningFirmware)
            }
            else -> {
                // Do nothing
            }
        }
    }

    fun deleteSaveStateSlot(slot: SaveStateSlot): List<SaveStateSlot>? {
        return (_emulatorState.value as? EmulatorState.RunningRom)?.let {
            saveStatesRepository.deleteRomSaveState(it.rom, slot)
            getRomSaveStateSlots(it.rom)
        }
    }

    private suspend fun saveRomState(rom: Rom, slot: SaveStateSlot): Boolean {
        val slotUri = saveStatesRepository.getRomSaveStateUri(rom, slot)
        return if (emulatorManager.saveState(slotUri)) {
            val screenshot = screenshotFrameBufferProvider.getScreenshot()
            saveStatesRepository.setRomSaveStateScreenshot(rom, slot, screenshot)
            true
        } else {
            false
        }
    }

    private suspend fun loadRomState(rom: Rom, slot: SaveStateSlot): Boolean {
        if (!slot.exists) {
            return false
        }

        val slotUri = saveStatesRepository.getRomSaveStateUri(rom, slot)
        val success = emulatorManager.loadState(slotUri)
        if (success) {
            _achievementsEvent.emit(RAEventUi.Reset)
        }

        return success
    }

    private fun startObservingRuntimeInputLayoutConfiguration() {
        sessionCoroutineScope.launch {
            val dualScreenPresetConfiguration = combine(
                _dualScreenPreset,
                _dualScreenIntegerScaleEnabled,
                _externalDisplayKeepAspectRatioEnabled,
                _dualScreenInternalFillHeightEnabled,
                _dualScreenInternalFillWidthEnabled,
                _dualScreenExternalFillHeightEnabled,
                _dualScreenExternalFillWidthEnabled,
                _dualScreenInternalVerticalAlignmentOverride,
                _dualScreenExternalVerticalAlignmentOverride,
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                DualScreenPresetConfiguration(
                    preset = values[0] as DualScreenPreset,
                    integerScale = values[1] as Boolean,
                    keepAspectRatio = values[2] as Boolean,
                    internalFillHeight = values[3] as Boolean,
                    internalFillWidth = values[4] as Boolean,
                    externalFillHeight = values[5] as Boolean,
                    externalFillWidth = values[6] as Boolean,
                    internalAlignmentOverride = values[7] as ScreenAlignment?,
                    externalAlignmentOverride = values[8] as ScreenAlignment?,
                )
            }

            val layoutConfiguration = combine(
                _layout,
                uiLayoutProvider.currentLayout,
                settingsRepository.getSoftInputBehaviour(),
                settingsRepository.isTouchHapticFeedbackEnabled(),
                settingsRepository.getSoftInputOpacity(),
            ) { globalLayoutConfiguration, variant, softInputBehaviour, isHapticFeedbackEnabled, inputOpacity ->
                RuntimeLayoutConfiguration(
                    layoutConfiguration = globalLayoutConfiguration,
                    layoutVariant = variant,
                    softInputBehaviour = softInputBehaviour,
                    isHapticFeedbackEnabled = isHapticFeedbackEnabled,
                    inputOpacity = inputOpacity,
                )
            }

            combine(layoutConfiguration, dualScreenPresetConfiguration) { config, dualScreenConfig ->
                val currentLayoutConfiguration = config.layoutConfiguration
                val currentLayoutVariant = config.layoutVariant
                val currentVariant = currentLayoutVariant?.first
                val currentLayout = currentLayoutVariant?.second
                if (currentLayoutConfiguration == null || currentLayout == null || currentVariant == null) {
                    null
                } else {
                    val opacity = if (currentLayoutConfiguration.useCustomOpacity) {
                        currentLayoutConfiguration.opacity
                    } else {
                        config.inputOpacity
                    }

                    val adjustedLayout = applyDualScreenPresetLayoutOverrides(currentLayout, currentVariant, dualScreenConfig)
                    RuntimeInputLayoutConfiguration(
                        softInputBehaviour = config.softInputBehaviour,
                        softInputOpacity = opacity,
                        isHapticFeedbackEnabled = config.isHapticFeedbackEnabled,
                        layoutOrientation = currentLayoutConfiguration.orientation,
                        layout = adjustedLayout,
                    )
                }
            }.collect(_runtimeLayout)
        }
    }

    private data class RuntimeLayoutConfiguration(
        val layoutConfiguration: LayoutConfiguration?,
        val layoutVariant: Pair<UILayoutVariant, UILayout>?,
        val softInputBehaviour: SoftInputBehaviour,
        val isHapticFeedbackEnabled: Boolean,
        val inputOpacity: Int,
    )

    private data class DualScreenPresetConfiguration(
        val preset: DualScreenPreset,
        val integerScale: Boolean,
        val keepAspectRatio: Boolean,
        val internalFillHeight: Boolean,
        val internalFillWidth: Boolean,
        val externalFillHeight: Boolean,
        val externalFillWidth: Boolean,
        val internalAlignmentOverride: ScreenAlignment?,
        val externalAlignmentOverride: ScreenAlignment?,
    )

    private fun applyDualScreenPresetLayoutOverrides(layout: UILayout, variant: UILayoutVariant, config: DualScreenPresetConfiguration): UILayout {
        if (config.preset == DualScreenPreset.OFF || variant.displays.secondaryScreenDisplay == null) {
            return layout
        }

        val canFill = config.integerScale || config.keepAspectRatio
        val internalAlignment = config.internalAlignmentOverride ?: config.preset.defaultInternalAlignment()
        val externalAlignment = config.externalAlignmentOverride ?: config.preset.defaultExternalAlignment()

        val adjustedInternalLayout = applyScreenScaleToLayout(
            screenLayout = layout.mainScreenLayout,
            availableWidth = variant.uiSize.x,
            availableHeight = variant.uiSize.y,
            integerScale = config.integerScale,
            keepAspectRatio = config.keepAspectRatio,
            fillHeight = config.internalFillHeight && canFill,
            fillWidth = config.internalFillWidth && canFill,
            alignment = internalAlignment,
        )

        val secondaryDisplay = variant.displays.secondaryScreenDisplay
        val adjustedSecondaryLayout = applyScreenScaleToLayout(
            screenLayout = layout.secondaryScreenLayout,
            availableWidth = secondaryDisplay.width,
            availableHeight = secondaryDisplay.height,
            integerScale = config.integerScale,
            keepAspectRatio = config.keepAspectRatio,
            fillHeight = config.externalFillHeight && canFill,
            fillWidth = config.externalFillWidth && canFill,
            alignment = externalAlignment,
        )

        return layout.copy(
            mainScreenLayout = adjustedInternalLayout,
            secondaryScreenLayout = adjustedSecondaryLayout,
        )
    }

    private fun applyScreenScaleToLayout(
        screenLayout: ScreenLayout,
        availableWidth: Int,
        availableHeight: Int,
        integerScale: Boolean,
        keepAspectRatio: Boolean,
        fillHeight: Boolean,
        fillWidth: Boolean,
        alignment: ScreenAlignment,
    ): ScreenLayout {
        val currentComponents = screenLayout.components ?: return screenLayout
        val screenComponents = currentComponents.filter { it.isScreen() }
        if (screenComponents.size != 1) {
            return screenLayout
        }
        if (availableWidth <= 0 || availableHeight <= 0) {
            return screenLayout
        }

        val screenComponent = screenComponents.single()
        val scaledRect = computeScaledScreenRect(
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            integerScale = integerScale,
            keepAspectRatio = keepAspectRatio,
            fillHeight = fillHeight,
            fillWidth = fillWidth,
            alignment = alignment,
        )

        val updatedComponents = currentComponents.map {
            if (it == screenComponent) {
                it.copy(rect = scaledRect)
            } else {
                it
            }
        }
        return screenLayout.copy(components = updatedComponents)
    }

    private fun computeScaledScreenRect(
        availableWidth: Int,
        availableHeight: Int,
        integerScale: Boolean,
        keepAspectRatio: Boolean,
        fillHeight: Boolean,
        fillWidth: Boolean,
        alignment: ScreenAlignment,
    ): Rect {
        val (baseWidth, baseHeight) = when {
            integerScale -> computeIntegerScaleDimensions(availableWidth, availableHeight)
            keepAspectRatio -> computeAspectRatioDimensions(availableWidth, availableHeight)
            else -> availableWidth to availableHeight
        }

        val scaledWidth = if (fillWidth) availableWidth else baseWidth
        val scaledHeight = if (fillHeight) availableHeight else baseHeight

        val left = ((availableWidth - scaledWidth) / 2f).roundToInt().coerceAtLeast(0)
        val top = when (alignment) {
            ScreenAlignment.TOP -> 0
            ScreenAlignment.CENTER -> ((availableHeight - scaledHeight) / 2f).roundToInt().coerceAtLeast(0)
            ScreenAlignment.BOTTOM -> (availableHeight - scaledHeight).coerceAtLeast(0)
        }

        return Rect(left, top, scaledWidth.coerceAtLeast(1), scaledHeight.coerceAtLeast(1))
    }

    private fun computeIntegerScaleDimensions(availableWidth: Int, availableHeight: Int): Pair<Int, Int> {
        val widthScale = availableWidth / SCREEN_WIDTH
        val heightScale = availableHeight / SCREEN_HEIGHT
        val maxIntegerScale = min(widthScale, heightScale)
        val scale = if (maxIntegerScale <= 0) {
            min(
                availableWidth.toFloat() / SCREEN_WIDTH,
                availableHeight.toFloat() / SCREEN_HEIGHT,
            )
        } else {
            maxIntegerScale.toFloat()
        }
        val width = (SCREEN_WIDTH * scale).roundToInt().coerceAtLeast(1).coerceAtMost(availableWidth)
        val height = (SCREEN_HEIGHT * scale).roundToInt().coerceAtLeast(1).coerceAtMost(availableHeight)
        return width to height
    }

    private fun computeAspectRatioDimensions(availableWidth: Int, availableHeight: Int): Pair<Int, Int> {
        val widthRatio = availableWidth.toFloat() / SCREEN_WIDTH
        val heightRatio = availableHeight.toFloat() / SCREEN_HEIGHT
        val scale = min(widthRatio, heightRatio)
        val width = (SCREEN_WIDTH * scale).roundToInt().coerceAtLeast(1).coerceAtMost(availableWidth)
        val height = (SCREEN_HEIGHT * scale).roundToInt().coerceAtLeast(1).coerceAtMost(availableHeight)
        return width to height
    }

    private fun resetEmulatorState(newState: EmulatorState) {
        finalizeOfflineRetroAchievementsSessionIfNeeded()
        sessionCoroutineScope.notifyNewSessionStarted()
        emulatorSession.reset()
        raSessionJob = null
        _currentFps.value = null
        _emulatorState.value = newState
        _mainScreenBackground.value = RuntimeBackground.None
        _secondaryScreenBackground.value = RuntimeBackground.None
        _layout.value = null
        currentRom = null
        currentRetroAchievementsGameId = null
        offlineSyncChoiceDeferred?.cancel()
        offlineSyncChoiceDeferred = null
        hardcoreExitChoiceDeferred?.cancel()
        hardcoreExitChoiceDeferred = null
        retroAchievementsNetworkMode = RetroAchievementsNetworkMode.ONLINE_LIVE
        retroAchievementsSessionMode = RetroAchievementsSessionMode.SOFTCORE
        isHardcoreEligibleAfterOnlineStart = false
        startedSessionOnlineLive = false
        isRetroAchievementsOnlineSessionStarted = false
        activeRuntimeBridgeConfig = null
        activeRuntimePath = RetroAchievementsRuntimePath.DISABLED
        didShowRuntimeFallbackEvent = false
        announcedMasteryKeys.clear()
        pendingRuntimeAchievementTriggers.clear()
        pendingRuntimeLeaderboardCompletions.clear()
    }

    private fun startObservingEmulatorEvents() {
        sessionCoroutineScope.launch {
            emulatorManager.emulatorEvents.collect {
                when (it) {
                    is EmulatorEvent.RumbleStart -> _rumbleEvent.tryEmit(RumbleEvent.RumbleStart(it.duration))
                    EmulatorEvent.RumbleStop -> _rumbleEvent.tryEmit(RumbleEvent.RumbleStop)
                    is EmulatorEvent.Stop -> {
                        when (it.reason) {
                            EmulatorEvent.Stop.Reason.GBAModeNotSupported -> _toastEvent.tryEmit(ToastEvent.GbaModeNotSupported)
                            EmulatorEvent.Stop.Reason.BadExceptionRegion -> _toastEvent.tryEmit(ToastEvent.InternalError)
                            EmulatorEvent.Stop.Reason.PowerOff -> { /* no-op */ }
                        }
                        stopEmulatorAndExit()
                    }
                }
            }
        }
    }

    private fun startObservingAchievementEvents() {
        sessionCoroutineScope.launch {
            emulatorManager.observeRetroAchievementEvents().collect {
                logRaRuntimeEvent(it)
                when (it) {
                    is RAEvent.OnAchievementPrimed -> onAchievementPrimed(it.achievementId)
                    is RAEvent.OnAchievementUnPrimed -> onAchievementUnPrimed(it.achievementId)
                    is RAEvent.OnAchievementTriggered -> onAchievementTriggered(it.achievementId)
                    is RAEvent.OnAchievementProgressUpdated -> onAchievementProgressUpdated(it)
                    is RAEvent.OnGameCompleted -> onSetCompleted(it.subsetId)
                    is RAEvent.OnSubsetCompleted -> onSetCompleted(it.subsetId)
                    is RAEvent.OnServerError -> onRuntimeServerError(it)
                    RAEvent.OnDisconnected -> onRuntimeDisconnected()
                    RAEvent.OnReconnected -> onRuntimeReconnected()
                    is RAEvent.OnRuntimeFallback -> onRuntimeFallback(it.reason)
                    is RAEvent.OnLeaderboardAttemptStarted -> onLeaderboardAttemptStarted(it)
                    is RAEvent.OnLeaderboardAttemptUpdated -> onLeaderboardAttemptUpdated(it)
                    is RAEvent.OnLeaderboardAttemptCompleted -> onLeaderboardAttemptCompleted(it)
                    is RAEvent.OnLeaderboardAttemptCancelled -> onLeaderboardAttemptCancelled(it)
                }
            }
        }
    }

    private fun startObservingMainScreenBackground() {
        sessionCoroutineScope.launch {
            combine(uiLayoutProvider.currentLayout, ensureEmulatorIsRunning()) { variant, _ ->
                val layout = variant?.second
                if (layout == null) {
                    RuntimeBackground.None
                } else {
                    loadBackground(layout.mainScreenLayout.backgroundId, layout.mainScreenLayout.backgroundMode)
                }
            }.collect(_mainScreenBackground)
        }
    }

    private fun startObservingSecondaryScreenBackground() {
        sessionCoroutineScope.launch {
            combine(uiLayoutProvider.currentLayout, ensureEmulatorIsRunning()) { variant, _ ->
                val layout = variant?.second
                if (layout == null) {
                    RuntimeBackground.None
                } else {
                    loadBackground(layout.secondaryScreenLayout.backgroundId, layout.secondaryScreenLayout.backgroundMode)
                }
            }.collect(_secondaryScreenBackground)
        }
    }

    private fun startObservingLayoutForRom(rom: Rom) {
        val romLayoutId = rom.config.layoutId
        val layoutFlow = if (romLayoutId == null) {
            getGlobalLayoutFlow()
        } else {
            // Load and observe ROM layout but switch to global layout if the ROM layout stops existing
            layoutsRepository.observeLayout(romLayoutId)
                .onCompletion {
                    emitAll(getGlobalLayoutFlow())
                }
        }

        sessionCoroutineScope.launch {
            combine(layoutFlow, ensureEmulatorIsRunning()) { layout, _ ->
                layout
            }.collect(_layout)
        }
    }

    private fun startObservingRendererConfiguration() {
        sessionCoroutineScope.launch {
            settingsRepository.observeRenderConfiguration().collectLatest {
                _runtimeRendererConfiguration.value = RuntimeRendererConfiguration(it.videoFiltering, it.resolutionScaling, it.customShader)
            }
        }
    }

    private fun startObservingLayoutForFirmware() {
        _layout.value = null

        sessionCoroutineScope.launch {
            combine(getGlobalLayoutFlow(), ensureEmulatorIsRunning()) { layout, _ ->
                layout
            }.collect(_layout)
        }
    }

    private suspend fun loadBackground(backgroundId: UUID?, mode: BackgroundMode): RuntimeBackground {
        return if (backgroundId == null) {
            RuntimeBackground(null, mode)
        } else {
            val background = backgroundsRepository.getBackground(backgroundId)
            RuntimeBackground(background, mode)
        }
    }

    private fun getGlobalLayoutFlow(): Flow<LayoutConfiguration> {
        return settingsRepository.observeSelectedLayoutId()
            .flatMapLatest {
                layoutsRepository.observeLayout(it)
                    .onCompletion {
                        emitAll(layoutsRepository.observeLayout(LayoutConfiguration.DEFAULT_ID))
                    }
            }
    }

    private fun getRomInfo(rom: Rom): RomInfo? {
        val fileRomProcessor = romFileProcessorFactory.getFileRomProcessorForDocument(rom.uri)
        return fileRomProcessor?.getRomInfo(rom)
    }

    private fun getRomSaveStateSlots(rom: Rom): List<SaveStateSlot> {
        return saveStatesRepository.getRomSaveStates(rom)
    }

    fun isSustainedPerformanceModeEnabled(): Boolean {
        return settingsRepository.isSustainedPerformanceModeEnabled()
    }

    fun getFpsCounterPosition(): FpsCounterPosition {
        return settingsRepository.getFpsCounterPosition()
    }

    private suspend fun getRomEnabledCheats(romInfo: RomInfo): List<Cheat> {
        if (!settingsRepository.areCheatsEnabled() || !emulatorSession.areCheatsEnabled()) {
            return emptyList()
        }

        return cheatsRepository.getRomEnabledCheats(romInfo)
    }

    private suspend fun getRomAchievementData(rom: Rom): OnlineRetroAchievementsBootstrap {
        val userAuth = retroAchievementsRepository.getUserAuthentication()
        if (userAuth == null) {
            return OnlineRetroAchievementsBootstrap(
                achievementData = GameAchievementData.withDisabledRetroAchievementsIntegration(GameAchievementData.IntegrationStatus.DISABLED_NOT_LOGGED_IN),
                source = OnlineRetroAchievementsBootstrapSource.NETWORK,
            )
        }

        val forHardcoreMode = emulatorSession.isRetroAchievementsHardcoreModeEnabled
        val cachedResult = withContext(Dispatchers.IO) {
            retroAchievementsRepository.getCachedUserGameData(rom.retroAchievementsHash, forHardcoreMode)
        }
        val cachedGameData = cachedResult.getOrNull()
        if (cachedGameData != null) {
            logRaTrace(
                "ra_bootstrap_cache_hit",
                "content_id" to rom.retroAchievementsHash,
                "game_id" to cachedGameData.id.id,
            )
            currentRetroAchievementsGameId = cachedGameData.id.id
            return OnlineRetroAchievementsBootstrap(
                achievementData = buildAchievementDataFromUserGameData(cachedGameData),
                source = OnlineRetroAchievementsBootstrapSource.CACHE,
            )
        }

        logRaTrace(
            "ra_bootstrap_cache_miss",
            "content_id" to rom.retroAchievementsHash,
            "cache_error" to (cachedResult.exceptionOrNull()?.javaClass?.simpleName ?: "none"),
        )
        return OnlineRetroAchievementsBootstrap(
            achievementData = getRomAchievementDataFromNetwork(
                rom = rom,
                userId = userAuth.username,
                forHardcoreMode = forHardcoreMode,
            ),
            source = OnlineRetroAchievementsBootstrapSource.NETWORK,
        )
    }

    private suspend fun getRomAchievementDataFromNetwork(
        rom: Rom,
        userId: String,
        forHardcoreMode: Boolean,
    ): GameAchievementData {
        return withContext(Dispatchers.IO) {
            retroAchievementsRepository.getUserGameData(rom.retroAchievementsHash, forHardcoreMode)
        }.fold(
            onSuccess = { userGameData ->
                val gameSummary = withContext(Dispatchers.IO) {
                    retroAchievementsRepository.getGameSummary(rom.retroAchievementsHash)
                }

                if (userGameData != null) {
                    currentRetroAchievementsGameId = userGameData.id.id
                    maybeWritePrefetchCache(
                        userId = userId,
                        contentId = rom.retroAchievementsHash,
                        userGameData = userGameData,
                    )
                    buildAchievementDataFromUserGameData(userGameData)
                } else {
                    currentRetroAchievementsGameId = null
                    GameAchievementData.withDisabledRetroAchievementsIntegration(
                        status = GameAchievementData.IntegrationStatus.DISABLED_GAME_NOT_FOUND,
                        icon = gameSummary?.icon,
                    )
                }
            },
            onFailure = {
                currentRetroAchievementsGameId = null
                // Maybe we have the game summary cached. Could allow the icon to be displayed, which looks better
                val gameSummary = withContext(Dispatchers.IO) {
                    retroAchievementsRepository.getGameSummary(rom.retroAchievementsHash)
                }
                GameAchievementData.withDisabledRetroAchievementsIntegration(GameAchievementData.IntegrationStatus.DISABLED_LOAD_ERROR, gameSummary?.icon)
            }
        )
    }

    private fun buildAchievementDataFromUserGameData(
        userGameData: me.magnum.melonds.domain.model.retroachievements.RAUserGameData,
    ): GameAchievementData {
        val achievements = userGameData.sets.flatMap { it.achievements }
        val leaderboards = userGameData.sets.flatMap { it.leaderboards }
        val hasLeaderboards = leaderboards.isNotEmpty() && emulatorSession.areLeaderboardsEnabled()

        return if (achievements.isEmpty() && !hasLeaderboards) {
            GameAchievementData.withLimitedRetroAchievementsIntegration(
                richPresencePatch = userGameData.richPresencePatch,
                icon = userGameData.icon,
            )
        } else {
            val lockedAchievements = achievements
                .filter { !it.isUnlocked }
                .map { RASimpleAchievement(it.achievement.id, it.achievement.memoryAddress) }
            val runtimeLeaderboards = if (hasLeaderboards) {
                leaderboards.map { RASimpleLeaderboard(it.id, it.mem, it.format) }
            } else {
                emptyList()
            }

            GameAchievementData.withFullRetroAchievementsIntegration(
                lockedAchievements = lockedAchievements,
                leaderboards = runtimeLeaderboards,
                totalAchievementCount = achievements.size,
                richPresencePatch = userGameData.richPresencePatch,
                icon = userGameData.icon,
            )
        }
    }

    private suspend fun buildOnlineRuntimeConfig(
        rom: Rom,
        launchDecision: RetroAchievementsLaunchDecision,
    ): RARuntimeBridgeConfig? {
        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return null
        Log.i(
            RA_IDENTITY_TAG,
                "source=runtime_config runtime=rc_client user_agent=$retroAchievementsUserAgent " +
                "package=${context.packageName} version=$retroAchievementsVersionName " +
                "game_id=${currentRetroAchievementsGameId ?: "none"} " +
                "game_hash=${rom.retroAchievementsHash} " +
                "hardcore=${launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE} " +
                "unofficial=${settingsRepository.areRetroAchievementsUnofficialAchievementsEnabled()} " +
                "encore=${settingsRepository.isRetroAchievementsEncoreModeEnabled()}",
        )
        return RARuntimeBridgeConfig(
            useRcClientRuntime = true,
            userAgent = retroAchievementsUserAgent,
            username = userAuth.username,
            apiToken = userAuth.token,
            gameHash = rom.retroAchievementsHash,
            gameId = currentRetroAchievementsGameId,
            hardcoreEnabled = launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE,
            unofficialEnabled = settingsRepository.areRetroAchievementsUnofficialAchievementsEnabled(),
            encoreEnabled = settingsRepository.isRetroAchievementsEncoreModeEnabled(),
        )
    }

    private fun buildAchievementDataSignature(achievementData: GameAchievementData): String {
        return buildString {
            append(achievementData.retroAchievementsIntegrationStatus.name)
            append('|')
            append(achievementData.totalAchievementCount)
            append('|')
            append(achievementData.richPresencePatch ?: "")

            achievementData.lockedAchievements
                .sortedWith(compareBy({ it.id }, { it.memoryAddress }))
                .forEach {
                    append("|A:")
                    append(it.id)
                    append(':')
                    append(it.memoryAddress)
                }

            achievementData.leaderboards
                .sortedWith(compareBy({ it.id }, { it.memoryAddress }, { it.format }))
                .forEach {
                    append("|L:")
                    append(it.id)
                    append(':')
                    append(it.memoryAddress)
                    append(':')
                    append(it.format)
                }
        }
    }

    private suspend fun refreshAndPromoteRetroAchievementsDataIfNeeded(
        rom: Rom,
        launchDecision: RetroAchievementsLaunchDecision,
        cachedAchievementData: GameAchievementData,
    ) {
        if (!networkStatusProvider.isOnline()) {
            logRaTrace("ra_refresh_skipped_offline", "content_id" to rom.retroAchievementsHash)
            return
        }

        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return
        val forHardcoreMode = launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE
        val cachedSignature = buildAchievementDataSignature(cachedAchievementData)

        withContext(Dispatchers.IO) {
            retroAchievementsRepository.refreshUserGameData(rom.retroAchievementsHash, forHardcoreMode)
        }.fold(
            onSuccess = { refreshedGameData ->
                if (refreshedGameData == null) {
                    logRaTrace("ra_refresh_result_game_not_found", "content_id" to rom.retroAchievementsHash)
                    return
                }

                currentRetroAchievementsGameId = refreshedGameData.id.id
                maybeWritePrefetchCache(
                    userId = userAuth.username,
                    contentId = rom.retroAchievementsHash,
                    userGameData = refreshedGameData,
                )

                if (retroAchievementsNetworkMode != RetroAchievementsNetworkMode.ONLINE_LIVE) {
                    logRaTrace("ra_refresh_skipped_not_online_live", "content_id" to rom.retroAchievementsHash)
                    return
                }

                val runningRom = (_emulatorState.value as? EmulatorState.RunningRom)?.rom
                if (runningRom?.uri != rom.uri) {
                    logRaTrace("ra_refresh_skipped_rom_changed", "content_id" to rom.retroAchievementsHash)
                    return
                }

                val refreshedAchievementData = buildAchievementDataFromUserGameData(refreshedGameData)
                val refreshedSignature = buildAchievementDataSignature(refreshedAchievementData)
                if (refreshedSignature == cachedSignature) {
                    logRaTrace("ra_refresh_keep_cache", "content_id" to rom.retroAchievementsHash)
                    return
                }

                val runtimeConfig = buildOnlineRuntimeConfig(rom, launchDecision)
                _achievementsEvent.emit(RAEventUi.Reset)
                emulatorManager.unloadRetroAchievementsData()
                emulatorManager.setupRetroAchievements(refreshedAchievementData, runtimeConfig)
                logRaTrace("ra_refresh_promoted_new_set", "content_id" to rom.retroAchievementsHash, "game_id" to refreshedGameData.id.id)
            },
            onFailure = { error ->
                logRaTrace(
                    "ra_refresh_failed_keep_cache",
                    "content_id" to rom.retroAchievementsHash,
                    "error" to (error::class.simpleName ?: "Unknown"),
                )
            },
        )
    }

    private suspend fun maybeWritePrefetchCache(
        userId: String,
        contentId: String,
        userGameData: me.magnum.melonds.domain.model.retroachievements.RAUserGameData,
    ) {
        if (!networkStatusProvider.isOnline()) return

        val achievements = userGameData.sets
            .asSequence()
            .flatMap { it.achievements.asSequence() }
            .map { OfflinePrefetchCacheAchievement(it.achievement.id, it.achievement.memoryAddress) }
            .distinctBy { it.id }
            .toList()

        val leaderboards = userGameData.sets
            .asSequence()
            .flatMap { it.leaderboards.asSequence() }
            .map { OfflinePrefetchCacheLeaderboard(it.id, it.mem, it.format) }
            .distinctBy { it.id }
            .toList()

        val cacheFile = OfflinePrefetchCacheFile(
            romHash = contentId,
            gameId = userGameData.id.id,
            achievements = achievements,
            leaderboards = leaderboards,
            richPresencePatch = userGameData.richPresencePatch,
            iconUrl = userGameData.icon.toString(),
            fetchedAtEpochMs = System.currentTimeMillis(),
        )

        try {
            withContext(Dispatchers.IO) {
                offlinePrefetchCacheRepository.write(userId, contentId, cacheFile)
            }
        } catch (_: Exception) {
            // Best-effort cache write. Achievements should still work online even if caching fails.
        }

        // Best-effort: warm icon/badge images so offline popups and lists can render without network.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val urls = buildList {
                    add(userGameData.icon.toString())
                    userGameData.sets.forEach { set ->
                        add(set.iconUrl.toString())
                        set.achievements.forEach { userAchievement ->
                            add(userAchievement.achievement.badgeUrlLocked.toString())
                            add(userAchievement.achievement.badgeUrlUnlocked.toString())
                        }
                    }
                }
                retroAchievementsImageCacheWarmer.warm(urls)
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }

    private fun onAchievementTriggered(achievementId: Long) {
        sessionCoroutineScope.launch {
            logRaTrace(
                "achievement_trigger_received",
                "achievement_id" to achievementId,
                "network_mode" to retroAchievementsNetworkMode.name,
                "session_mode" to retroAchievementsSessionMode.name,
                "online" to networkStatusProvider.isOnline(),
            )
            val achievement = retroAchievementsRepository.getAchievement(achievementId).getOrNull()
            if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.ONLINE_LIVE && !networkStatusProvider.isOnline()) {
                transitionToOfflineAccumulationIfNeeded()
            }

            if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
                logRaTrace(
                    "achievement_trigger_offline_queued",
                    "achievement_id" to achievementId,
                    "session_mode" to retroAchievementsSessionMode.name,
                )
                completeAchievementSubmissionTrace(achievementId, "offline_queued")
                handleOfflineAchievementTriggered(achievementId, achievement)
                return@launch
            }

            if (achievement != null) {
                if (!ensureAchievementSubmitContext(achievement)) {
                    completeAchievementSubmissionTrace(achievementId, "context_mismatch")
                    return@launch
                }

                _achievementsEvent.emit(RAEventUi.AchievementTriggered(achievement))
                logRaTrace(
                    "achievement_popup_emitted",
                    "achievement_id" to achievementId,
                    "source" to "runtime_trigger",
                    "encore_enabled" to settingsRepository.isRetroAchievementsEncoreModeEnabled(),
                )

                val isHardcoreModeEnabled = emulatorSession.isRetroAchievementsHardcoreModeEnabled
                if (isHardcoreModeEnabled) {
                    attemptSilentHardcoreReplayBeforeOnlineSubmission()
                }
                logRaTrace(
                    "achievement_submit_attempt",
                    "achievement_id" to achievementId,
                    "hardcore" to isHardcoreModeEnabled,
                    "game_id" to currentRetroAchievementsGameId,
                )
                retroAchievementsRepository.awardAchievement(achievement, isHardcoreModeEnabled).onSuccess {
                    logRaTrace(
                        "achievement_submit_success",
                        "achievement_id" to achievementId,
                        "hardcore" to isHardcoreModeEnabled,
                        "awarded" to it.achievementAwarded,
                    )
                    if (it.achievementAwarded) {
                        if (it.isSetMastered()) {
                            showSetMastery(achievement.setId, isHardcoreModeEnabled)
                        }
                    }
                    completeAchievementSubmissionTrace(
                        achievementId,
                        if (it.achievementAwarded) "submit_success" else "already_unlocked",
                    )
                }.onFailure { error ->
                    logRaTrace(
                        "achievement_submit_failed",
                        "achievement_id" to achievementId,
                        "hardcore" to isHardcoreModeEnabled,
                        "error" to (error::class.simpleName ?: "Unknown"),
                    )
                    completeAchievementSubmissionTrace(achievementId, "submit_failed")
                }
            } else {
                completeAchievementSubmissionTrace(achievementId, "achievement_missing")
            }
        }
    }

    private suspend fun transitionToOfflineAccumulationIfNeeded() {
        if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
            return
        }

        retroAchievementsNetworkMode = RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING
        isRetroAchievementsOnlineSessionStarted = false
        val unlockMode = if (isHardcoreEligibleAfterOnlineStart) {
            OfflineUnlockMode.HARDCORE
        } else {
            OfflineUnlockMode.SOFTCORE
        }
        logRaTrace(
            "network_transition_offline",
            "unlock_mode" to unlockMode.name,
            "started_online" to startedSessionOnlineLive,
            "game_id" to currentRetroAchievementsGameId,
            "content_id" to currentRom?.retroAchievementsHash,
        )

        val offlineSession = ensureOfflineAccumulationSession(
            unlockMode = unlockMode,
            offlineType = OfflineUnlockType.OFFLINE_AFTER_START,
        )
        if (offlineSession != null && unlockMode == OfflineUnlockMode.HARDCORE) {
            hardcoreOfflineLossTracker.markPendingUnlocks(
                userId = offlineSession.userId,
                contentId = offlineSession.contentId,
                gameTitle = currentRom?.name ?: offlineSession.contentId,
            )
            _toastEvent.tryEmit(ToastEvent.HardcoreOfflineUnsyncedWarning(1))
        }
    }

    private suspend fun ensureOfflineAccumulationSession(
        unlockMode: OfflineUnlockMode,
        offlineType: OfflineUnlockType,
    ): OfflineRetroAchievementsSession? {
        val existing = offlineRetroAchievementsSession
        if (existing != null) {
            return existing
        }

        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return null
        val rom = currentRom ?: return null
        val gameId = currentRetroAchievementsGameId ?: run {
            val offlineContext = buildOfflineRetroAchievementsContext(rom)
            if (offlineContext?.missingCache == true) {
                return null
            }
            offlineContext?.cache?.gameId
        } ?: return null

        val startedAtEpochMs = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val created = OfflineRetroAchievementsSession(
            userId = userAuth.username,
            contentId = rom.retroAchievementsHash,
            gameId = gameId,
            unlockMode = unlockMode,
            offlineType = offlineType,
            sessionId = sessionId,
            startedAtEpochMs = startedAtEpochMs,
            nextOrderIndex = 0L,
        )

        val appendResult = withContext(Dispatchers.IO) {
            offlineLedgerRepository.appendSessionStart(
                userId = created.userId,
                contentId = created.contentId,
                gameId = created.gameId,
                sessionId = created.sessionId,
                startedAtEpochMs = startedAtEpochMs,
                isHardcore = unlockMode == OfflineUnlockMode.HARDCORE,
                unlockMode = unlockMode,
                offlineType = offlineType,
            )
        }

        if (appendResult.isFailure) {
            return null
        }

        offlineRetroAchievementsSession = created
        return created
    }

    private suspend fun handleOfflineAchievementTriggered(achievementId: Long, achievement: me.magnum.rcheevosapi.model.RAAchievement?) {
        val offlineSession = offlineRetroAchievementsSession ?: run {
            val offlineType = if (startedSessionOnlineLive) {
                OfflineUnlockType.OFFLINE_AFTER_START
            } else {
                OfflineUnlockType.OFFLINE_FROM_START
            }
            val unlockMode = if (offlineType == OfflineUnlockType.OFFLINE_AFTER_START && isHardcoreEligibleAfterOnlineStart) {
                OfflineUnlockMode.HARDCORE
            } else {
                OfflineUnlockMode.SOFTCORE
            }
            ensureOfflineAccumulationSession(unlockMode = unlockMode, offlineType = offlineType)
        }
        if (offlineSession != null) {
            val now = System.currentTimeMillis()
            val offsetMs = (now - offlineSession.startedAtEpochMs).coerceAtLeast(0L)
            val orderIndex = offlineSession.nextOrderIndex
            offlineSession.nextOrderIndex = orderIndex + 1L
            logRaTrace(
                "offline_ledger_append_attempt",
                "achievement_id" to achievementId,
                "unlock_mode" to offlineSession.unlockMode.name,
                "offline_type" to offlineSession.offlineType.name,
                "order_index" to orderIndex,
                "offset_ms" to offsetMs,
                "game_id" to offlineSession.gameId,
                "content_id" to offlineSession.contentId,
            )

            withContext(Dispatchers.IO) {
                retroAchievementsDao.addUserAchievement(
                    RAUserAchievementEntity(
                        gameId = offlineSession.gameId,
                        achievementId = achievementId,
                        isUnlocked = true,
                        isHardcore = offlineSession.unlockMode == OfflineUnlockMode.HARDCORE,
                    )
                )

                offlineLedgerRepository.appendAchievementUnlock(
                    userId = offlineSession.userId,
                    contentId = offlineSession.contentId,
                    gameId = offlineSession.gameId,
                    achievementId = achievementId,
                    isHardcore = offlineSession.unlockMode == OfflineUnlockMode.HARDCORE,
                    sessionId = offlineSession.sessionId,
                    localTimestampEpochMs = now,
                    offsetFromSessionStartMs = offsetMs,
                    orderIndex = orderIndex,
                    unlockMode = offlineSession.unlockMode,
                    offlineType = offlineSession.offlineType,
                )
            }
            logRaTrace(
                "offline_ledger_append_success",
                "achievement_id" to achievementId,
                "unlock_mode" to offlineSession.unlockMode.name,
                "offline_type" to offlineSession.offlineType.name,
                "order_index" to orderIndex,
            )

            if (offlineSession.unlockMode == OfflineUnlockMode.HARDCORE) {
                hardcoreOfflineLossTracker.markPendingUnlocks(
                    userId = offlineSession.userId,
                    contentId = offlineSession.contentId,
                    gameTitle = currentRom?.name ?: offlineSession.contentId,
                )
            }
        }

        if (achievement != null) {
            _achievementsEvent.emit(RAEventUi.AchievementTriggered(achievement))
        }
    }

    private fun onAchievementPrimed(achievementId: Long) {
        if (settingsRepository.areRetroAchievementsActiveChallengeIndicatorsEnabled()) {
            sessionCoroutineScope.launch {
                retroAchievementsRepository.getAchievement(achievementId).onSuccess { achievement ->
                    if (achievement != null) {
                        _achievementsEvent.emit(RAEventUi.AchievementPrimed(achievement))
                    }
                }
            }
        }
    }

    private fun onAchievementUnPrimed(achievementId: Long) {
        sessionCoroutineScope.launch {
            retroAchievementsRepository.getAchievement(achievementId).onSuccess { achievement ->
                if (achievement != null) {
                    _achievementsEvent.emit(RAEventUi.AchievementUnPrimed(achievement))
                }
            }
        }
    }

    private fun onAchievementProgressUpdated(progressEvent: RAEvent.OnAchievementProgressUpdated) {
        if (settingsRepository.areRetroAchievementsProgressIndicatorsEnabled()) {
            sessionCoroutineScope.launch {
                retroAchievementsRepository.getAchievement(progressEvent.achievementId).onSuccess { achievement ->
                    if (achievement != null) {
                        _achievementsEvent.emit(RAEventUi.AchievementProgressUpdated(achievement, progressEvent.current, progressEvent.target, progressEvent.progress))
                    }
                }
            }
        }
    }

    private fun onSetCompleted(subsetId: Long) {
        sessionCoroutineScope.launch {
            showSetMastery(RASetId(subsetId), emulatorSession.isRetroAchievementsHardcoreModeEnabled)
        }
    }

    private fun onRuntimeServerError(event: RAEvent.OnServerError) {
        logRaTrace(
            "runtime_server_error",
            "api" to event.api,
            "related_id" to event.relatedId,
            "result_code" to event.resultCode,
            "message" to event.message,
        )
    }

    private fun onRuntimeDisconnected() {
        logRaTrace("runtime_disconnected")
    }

    private fun onRuntimeReconnected() {
        logRaTrace("runtime_reconnected")
    }

    private fun onRuntimeFallback(reason: RuntimeFallbackReason) {
        activeRuntimePath = if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
            RetroAchievementsRuntimePath.LEGACY_OFFLINE
        } else {
            RetroAchievementsRuntimePath.LEGACY
        }
        logRaTrace(
            "runtime_fallback",
            "reason" to reason.name,
        )

        if (didShowRuntimeFallbackEvent) {
            return
        }

        didShowRuntimeFallbackEvent = true
        _raIntegrationEvent.tryEmit(
            RAIntegrationEvent.RuntimeFallback(
                icon = null,
                reason = getRuntimeFallbackReasonMessage(reason),
            )
        )
    }

    private suspend fun ensureAchievementSubmitContext(achievement: RAAchievement): Boolean {
        if (retroAchievementsNetworkMode != RetroAchievementsNetworkMode.ONLINE_LIVE) {
            logContextMismatch("achievement", achievement.id, "network_mode_offline", "achievement_game_id" to achievement.gameId.id)
            return false
        }

        if (!awaitOnlineSessionStart()) {
            logContextMismatch("achievement", achievement.id, "session_not_started", "achievement_game_id" to achievement.gameId.id)
            return false
        }

        val runtimeConfig = activeRuntimeBridgeConfig
        if (runtimeConfig == null) {
            logContextMismatch("achievement", achievement.id, "missing_runtime_config", "achievement_game_id" to achievement.gameId.id)
            return false
        }

        val currentHash = currentRom?.retroAchievementsHash
        if (currentHash.isNullOrBlank() || runtimeConfig.gameHash.isNullOrBlank() || currentHash != runtimeConfig.gameHash) {
            logContextMismatch(
                "achievement",
                achievement.id,
                "game_hash_mismatch",
                "achievement_game_id" to achievement.gameId.id,
                "runtime_game_hash" to runtimeConfig.gameHash,
                "current_game_hash" to currentHash,
            )
            return false
        }

        val expectedGameId = currentRetroAchievementsGameId ?: runtimeConfig.gameId
        if (expectedGameId == null || expectedGameId != achievement.gameId.id || (runtimeConfig.gameId != null && runtimeConfig.gameId != achievement.gameId.id)) {
            logContextMismatch(
                "achievement",
                achievement.id,
                "game_id_mismatch",
                "achievement_game_id" to achievement.gameId.id,
                "runtime_game_id" to runtimeConfig.gameId,
                "current_game_id" to currentRetroAchievementsGameId,
            )
            return false
        }

        val isHardcoreModeEnabled = emulatorSession.isRetroAchievementsHardcoreModeEnabled
        if (runtimeConfig.hardcoreEnabled != isHardcoreModeEnabled) {
            logContextMismatch(
                "achievement",
                achievement.id,
                "hardcore_mismatch",
                "achievement_game_id" to achievement.gameId.id,
                "runtime_hardcore" to runtimeConfig.hardcoreEnabled,
                "session_hardcore" to isHardcoreModeEnabled,
            )
            return false
        }

        if (!runtimeConfig.unofficialEnabled && achievement.type == RAAchievement.Type.UNOFFICIAL) {
            logContextMismatch(
                "achievement",
                achievement.id,
                "unofficial_disabled",
                "achievement_game_id" to achievement.gameId.id,
                "encore_enabled" to runtimeConfig.encoreEnabled,
            )
            return false
        }

        return true
    }

    private suspend fun ensureLeaderboardSubmitContext(leaderboardId: Long): Boolean {
        if (retroAchievementsNetworkMode != RetroAchievementsNetworkMode.ONLINE_LIVE) {
            logContextMismatch("leaderboard", leaderboardId, "network_mode_offline")
            return false
        }

        if (!awaitOnlineSessionStart()) {
            logContextMismatch("leaderboard", leaderboardId, "session_not_started")
            return false
        }

        val runtimeConfig = activeRuntimeBridgeConfig
        if (runtimeConfig == null) {
            logContextMismatch("leaderboard", leaderboardId, "missing_runtime_config")
            return false
        }

        val leaderboard = retroAchievementsRepository.getLeaderboard(leaderboardId)
        if (leaderboard == null) {
            logContextMismatch("leaderboard", leaderboardId, "missing_leaderboard")
            return false
        }

        val currentHash = currentRom?.retroAchievementsHash
        if (currentHash.isNullOrBlank() || runtimeConfig.gameHash.isNullOrBlank() || currentHash != runtimeConfig.gameHash) {
            logContextMismatch(
                "leaderboard",
                leaderboardId,
                "game_hash_mismatch",
                "runtime_game_hash" to runtimeConfig.gameHash,
                "current_game_hash" to currentHash,
                "leaderboard_game_id" to leaderboard.gameId.id,
            )
            return false
        }

        val expectedGameId = currentRetroAchievementsGameId ?: runtimeConfig.gameId
        if (expectedGameId == null || expectedGameId != leaderboard.gameId.id || (runtimeConfig.gameId != null && runtimeConfig.gameId != leaderboard.gameId.id)) {
            logContextMismatch(
                "leaderboard",
                leaderboardId,
                "game_id_mismatch",
                "leaderboard_game_id" to leaderboard.gameId.id,
                "runtime_game_id" to runtimeConfig.gameId,
                "current_game_id" to currentRetroAchievementsGameId,
            )
            return false
        }

        if (runtimeConfig.hardcoreEnabled != emulatorSession.isRetroAchievementsHardcoreModeEnabled) {
            logContextMismatch(
                "leaderboard",
                leaderboardId,
                "hardcore_mismatch",
                "runtime_hardcore" to runtimeConfig.hardcoreEnabled,
                "session_hardcore" to emulatorSession.isRetroAchievementsHardcoreModeEnabled,
            )
            return false
        }

        return true
    }

    private fun logContextMismatch(
        entityType: String,
        entityId: Long,
        reason: String,
        vararg fields: Pair<String, Any?>,
    ) {
        logRaTrace(
            "context_mismatch",
            "entity_type" to entityType,
            "entity_id" to entityId,
            "reason" to reason,
            "submit_path" to "kotlin_api",
            *fields,
        )
    }

    private suspend fun awaitOnlineSessionStart(timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isRetroAchievementsOnlineSessionStarted && System.currentTimeMillis() < deadline) {
            delay(250.milliseconds)
        }
        return isRetroAchievementsOnlineSessionStarted
    }

    private fun completeAchievementSubmissionTrace(achievementId: Long, result: String) {
        val startedAt = pendingRuntimeAchievementTriggers.remove(achievementId)
        if (startedAt == null) {
            logRaTrace(
                "runtime_submit_orphan",
                "entity_type" to "achievement",
                "entity_id" to achievementId,
                "result" to result,
                "submit_path" to "kotlin_api",
            )
            return
        }

        logRaTrace(
            "runtime_submit_resolved",
            "entity_type" to "achievement",
            "entity_id" to achievementId,
            "result" to result,
            "latency_ms" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            "submit_path" to "kotlin_api",
        )
    }

    private fun completeLeaderboardSubmissionTrace(leaderboardId: Long, result: String) {
        val startedAt = pendingRuntimeLeaderboardCompletions.remove(leaderboardId)
        if (startedAt == null) {
            logRaTrace(
                "runtime_submit_orphan",
                "entity_type" to "leaderboard",
                "entity_id" to leaderboardId,
                "result" to result,
                "submit_path" to "kotlin_api",
            )
            return
        }

        logRaTrace(
            "runtime_submit_resolved",
            "entity_type" to "leaderboard",
            "entity_id" to leaderboardId,
            "result" to result,
            "latency_ms" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            "submit_path" to "kotlin_api",
        )
    }

    private fun getRuntimeFallbackReasonMessage(reason: RuntimeFallbackReason): String {
        val messageRes = when (reason) {
            RuntimeFallbackReason.LOGIN_TIMEOUT -> R.string.ra_runtime_fallback_login_timeout
            RuntimeFallbackReason.LOGIN_FAILED -> R.string.ra_runtime_fallback_login_failed
            RuntimeFallbackReason.LOAD_TIMEOUT -> R.string.ra_runtime_fallback_load_timeout
            RuntimeFallbackReason.LOAD_FAILED -> R.string.ra_runtime_fallback_load_failed
            RuntimeFallbackReason.PERFORMANCE -> R.string.ra_runtime_fallback_performance
            RuntimeFallbackReason.UNKNOWN -> R.string.ra_runtime_fallback_unknown
        }
        return context.getString(messageRes)
    }

    private fun onLeaderboardAttemptStarted(startEvent: RAEvent.OnLeaderboardAttemptStarted) {
        sessionCoroutineScope.launch {
            if (settingsRepository.areRetroAchievementsLeaderboardIndicatorsEnabled()) {
                val leaderboard = retroAchievementsRepository.getLeaderboard(startEvent.leaderboardId)
                if (leaderboard != null) {
                    val setSummary = retroAchievementsRepository.getAchievementSetSummary(leaderboard.setId)
                    if (setSummary != null) {
                        _achievementsEvent.emit(RAEventUi.LeaderboardAttemptStarted(leaderboard, setSummary.iconUrl))
                    }
                }
            }
        }
    }

    private fun onLeaderboardAttemptUpdated(updateEvent: RAEvent.OnLeaderboardAttemptUpdated) {
        sessionCoroutineScope.launch {
            if (settingsRepository.areRetroAchievementsLeaderboardIndicatorsEnabled()) {
                _achievementsEvent.emit(RAEventUi.LeaderboardAttemptUpdated(updateEvent.leaderboardId, updateEvent.formattedValue))
            }
        }
    }

    private fun onLeaderboardAttemptCompleted(completionEvent: RAEvent.OnLeaderboardAttemptCompleted) {
        sessionCoroutineScope.launch {
            logRaTrace(
                "leaderboard_complete_received",
                "leaderboard_id" to completionEvent.leaderboardId,
                "value" to completionEvent.value,
                "network_mode" to retroAchievementsNetworkMode.name,
                "session_mode" to retroAchievementsSessionMode.name,
                "online" to networkStatusProvider.isOnline(),
            )
            if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.ONLINE_LIVE && !networkStatusProvider.isOnline()) {
                transitionToOfflineAccumulationIfNeeded()
            }

            if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
                // Offline mode: avoid server submission (no Smart Sync support for leaderboards in this POC).
                logRaTrace(
                    "leaderboard_submit_skipped_offline",
                    "leaderboard_id" to completionEvent.leaderboardId,
                )
                completeLeaderboardSubmissionTrace(completionEvent.leaderboardId, "offline_skipped")
                _achievementsEvent.emit(RAEventUi.LeaderboardAttemptCancelled(completionEvent.leaderboardId))
                return@launch
            }

            if (!emulatorSession.areLeaderboardsEnabled()) {
                logRaTrace(
                    "leaderboard_submit_skipped_mode",
                    "leaderboard_id" to completionEvent.leaderboardId,
                    "hardcore_enabled" to emulatorSession.isRetroAchievementsHardcoreModeEnabled,
                )
                completeLeaderboardSubmissionTrace(completionEvent.leaderboardId, "mode_skipped")
                _achievementsEvent.emit(RAEventUi.LeaderboardAttemptCancelled(completionEvent.leaderboardId))
                return@launch
            }

            if (!ensureLeaderboardSubmitContext(completionEvent.leaderboardId)) {
                completeLeaderboardSubmissionTrace(completionEvent.leaderboardId, "context_mismatch")
                _achievementsEvent.emit(RAEventUi.LeaderboardAttemptCancelled(completionEvent.leaderboardId))
                return@launch
            }

            if (emulatorSession.isRetroAchievementsHardcoreModeEnabled) {
                attemptSilentHardcoreReplayBeforeOnlineSubmission()
            }

            retroAchievementsRepository.submitLeaderboardEntry(completionEvent.leaderboardId, completionEvent.value).fold(
                onSuccess = { submissionResponse ->
                    logRaTrace(
                        "leaderboard_submit_success",
                        "leaderboard_id" to completionEvent.leaderboardId,
                        "rank" to submissionResponse.rank,
                    )
                    retroAchievementsRepository.getLeaderboard(completionEvent.leaderboardId)?.let { leaderboard ->
                        retroAchievementsRepository.getAchievementSetSummary(leaderboard.setId)?.let { setSummary ->
                            val submissionEvent = RAEventUi.LeaderboardEntrySubmitted(
                                leaderboardId = completionEvent.leaderboardId,
                                title = submissionResponse.title,
                                gameIcon = setSummary.iconUrl,
                                formattedScore = submissionResponse.formattedScore,
                                rank = submissionResponse.rank,
                                numberOfEntries = submissionResponse.numEntries,
                            )
                            _achievementsEvent.emit(submissionEvent)
                        }
                    }
                    completeLeaderboardSubmissionTrace(completionEvent.leaderboardId, "submit_success")
                },
                onFailure = { error ->
                    logRaTrace(
                        "leaderboard_submit_failed",
                        "leaderboard_id" to completionEvent.leaderboardId,
                        "error" to (error::class.simpleName ?: "Unknown"),
                    )
                    completeLeaderboardSubmissionTrace(completionEvent.leaderboardId, "submit_failed")
                    // Submission failed. Submit a cancellation event anyway to ensure the attempt indicator is dismissed
                    _achievementsEvent.emit(RAEventUi.LeaderboardAttemptCancelled(completionEvent.leaderboardId))
                },
            )
        }
    }

    private suspend fun attemptSilentHardcoreReplayBeforeOnlineSubmission() {
        if (!networkStatusProvider.isOnline()) return

        val rom = currentRom ?: return
        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return
        val userId = userAuth.username
        val contentId = rom.retroAchievementsHash

        val statusBefore = withContext(Dispatchers.IO) {
            offlineLedgerRepository.getStatus(userId, contentId)
        }
        if (statusBefore.integrity != OfflineLedgerIntegrity.OK || !statusBefore.hasPendingHardcoreUnlocks) {
            return
        }
        logRaTrace(
            "hardcore_silent_replay_attempt",
            "pending_hardcore" to statusBefore.pendingHardcoreUnlockCount,
            "game_id" to currentRetroAchievementsGameId,
            "content_id" to contentId,
        )

        withContext(Dispatchers.IO) {
            smartSyncEngine.syncHardcoreNow(userId, contentId)
        }

        val statusAfter = withContext(Dispatchers.IO) {
            offlineLedgerRepository.getStatus(userId, contentId)
        }
        if (statusAfter.integrity == OfflineLedgerIntegrity.OK && !statusAfter.hasPendingHardcoreUnlocks) {
            logRaTrace(
                "hardcore_silent_replay_complete",
                "pending_hardcore" to statusAfter.pendingHardcoreUnlockCount,
                "content_id" to contentId,
            )
            hardcoreOfflineLossTracker.clearPendingUnlocks(userId, contentId)
        } else {
            logRaTrace(
                "hardcore_silent_replay_partial",
                "pending_hardcore" to statusAfter.pendingHardcoreUnlockCount,
                "integrity" to statusAfter.integrity.name,
                "content_id" to contentId,
            )
        }
    }

    private fun onLeaderboardAttemptCancelled(cancelEvent: RAEvent.OnLeaderboardAttemptCancelled) {
        sessionCoroutineScope.launch {
            _achievementsEvent.emit(RAEventUi.LeaderboardAttemptCancelled(cancelEvent.leaderboardId))
        }
    }

    private suspend fun showSetMastery(setId: RASetId, forHardcoreMode: Boolean) {
        val announcementKey = setId.id to forHardcoreMode
        if (!announcedMasteryKeys.add(announcementKey)) {
            return
        }

        val rom = (emulatorSession.currentSessionType() as? EmulatorSession.SessionType.RomSession)?.rom
        if (rom == null) {
            announcedMasteryKeys.remove(announcementKey)
            return
        }

        val setSummary = retroAchievementsRepository.getAchievementSetSummary(setId)
        val raUserName = retroAchievementsRepository.getUserAuthentication()?.username
        val romPlayTime = romsRepository.getRomAtUri(rom.uri)?.totalPlayTime

        if (setSummary == null) {
            announcedMasteryKeys.remove(announcementKey)
            return
        }

        val title = if (setSummary.type == RAAchievementSet.Type.Core) {
            val gameSummary = retroAchievementsRepository.getGameSummary(rom.retroAchievementsHash)
            gameSummary?.title.orEmpty()
        } else {
            setSummary.title.orEmpty()
        }

        val masteryEvent = RAEventUi.GameMastered(
            gameTitle = title,
            gameIcon = setSummary.iconUrl,
            userName = raUserName,
            playTime = romPlayTime,
            forHardcodeMode = forHardcoreMode,
        )
        _achievementsEvent.emit(masteryEvent)
    }

    private fun startRetroAchievementsSession(rom: Rom, launchDecision: RetroAchievementsLaunchDecision) {
        sessionCoroutineScope.launch {
            offlineRetroAchievementsSession = null
            activeRuntimeBridgeConfig = null
            activeRuntimePath = RetroAchievementsRuntimePath.DISABLED
            didShowRuntimeFallbackEvent = false

            val networkMode = launchDecision.networkMode
            val offlineContext = if (networkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
                buildOfflineRetroAchievementsContext(rom)
            } else {
                null
            }
            val onlineBootstrap = if (networkMode == RetroAchievementsNetworkMode.ONLINE_LIVE) {
                withContext(Dispatchers.IO) { getRomAchievementData(rom) }
            } else {
                null
            }

            val achievementData = when (networkMode) {
                RetroAchievementsNetworkMode.ONLINE_LIVE -> onlineBootstrap?.achievementData
                    ?: GameAchievementData.withDisabledRetroAchievementsIntegration(GameAchievementData.IntegrationStatus.DISABLED_LOAD_ERROR)
                RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING -> offlineContext?.achievementData
                    ?: GameAchievementData.withDisabledRetroAchievementsIntegration(GameAchievementData.IntegrationStatus.DISABLED_LOAD_ERROR)
            }

            emulatorSession.updateRetroAchievementsIntegrationStatus(achievementData.retroAchievementsIntegrationStatus)
            if (!achievementData.isRetroAchievementsIntegrationEnabled) {
                if (networkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING && offlineContext?.missingCache == true) {
                    _raIntegrationEvent.tryEmit(RAIntegrationEvent.OfflineDisabledNoCache(achievementData.icon))
                } else if (achievementData.retroAchievementsIntegrationStatus == GameAchievementData.IntegrationStatus.DISABLED_LOAD_ERROR) {
                    _raIntegrationEvent.tryEmit(RAIntegrationEvent.Failed(achievementData.icon))
                }

                return@launch
            }

            raSessionJob = launch {
                // Wait until the emulator has actually started
                ensureEmulatorIsRunning().firstOrNull()

                when (networkMode) {
                    RetroAchievementsNetworkMode.ONLINE_LIVE -> {
                        val runtimeConfig = buildOnlineRuntimeConfig(rom, launchDecision)
                        activeRuntimeBridgeConfig = runtimeConfig
                        activeRuntimePath = if (runtimeConfig?.useRcClientRuntime == true) {
                            RetroAchievementsRuntimePath.RC_CLIENT
                        } else {
                            RetroAchievementsRuntimePath.LEGACY
                        }

                        emulatorManager.setupRetroAchievements(achievementData, runtimeConfig)
                        isRetroAchievementsOnlineSessionStarted = false
                        if (onlineBootstrap?.source == OnlineRetroAchievementsBootstrapSource.CACHE) {
                            launch {
                                refreshAndPromoteRetroAchievementsDataIfNeeded(
                                    rom = rom,
                                    launchDecision = launchDecision,
                                    cachedAchievementData = achievementData,
                                )
                            }
                        }
                        emitRetroAchievementsModeToast(
                            status = if (launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE) {
                                ToastEvent.RetroAchievementsModeStatus.HARDCORE
                            } else {
                                ToastEvent.RetroAchievementsModeStatus.SOFTCORE
                            }
                        )
                        emitRetroAchievementsLoadedPopup(achievementData)

                        while (isActive) {
                            if (retroAchievementsNetworkMode != RetroAchievementsNetworkMode.ONLINE_LIVE) {
                                break
                            }

                            if (!networkStatusProvider.isOnline()) {
                                transitionToOfflineAccumulationIfNeeded()
                                delay(15.seconds)
                                continue
                            }

                            if (!isRetroAchievementsOnlineSessionStarted) {
                                val startResult = withContext(Dispatchers.IO) {
                                    retroAchievementsRepository.startSession(rom.retroAchievementsHash)
                                }
                                if (startResult.isFailure) {
                                    delay(15.seconds)
                                    continue
                                }

                                isRetroAchievementsOnlineSessionStarted = true
                            }

                            // TODO: Should we pause the session if the app goes to background? If so, how?
                            delay(2.minutes)
                            val richPresenceDescription = MelonEmulator.getRichPresenceStatus()
                            withContext(Dispatchers.IO) {
                                retroAchievementsRepository.sendSessionHeartbeat(rom.retroAchievementsHash, richPresenceDescription)
                            }
                        }
                    }
                    RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING -> {
                        val context = offlineContext ?: return@launch
                        currentRetroAchievementsGameId = context.cache.gameId
                        val userAuth = retroAchievementsRepository.getUserAuthentication()
                        val runtimeConfig = if (userAuth != null) {
                            RARuntimeBridgeConfig(
                                useRcClientRuntime = false,
                                userAgent = retroAchievementsUserAgent,
                                username = userAuth.username,
                                apiToken = userAuth.token,
                                gameHash = rom.retroAchievementsHash,
                                gameId = context.cache.gameId,
                                hardcoreEnabled = launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE,
                                unofficialEnabled = settingsRepository.areRetroAchievementsUnofficialAchievementsEnabled(),
                                encoreEnabled = settingsRepository.isRetroAchievementsEncoreModeEnabled(),
                            )
                        } else {
                            null
                        }
                        activeRuntimeBridgeConfig = runtimeConfig
                        activeRuntimePath = RetroAchievementsRuntimePath.LEGACY_OFFLINE

                        val startedAtEpochMs = System.currentTimeMillis()
                        val sessionId = UUID.randomUUID().toString()
                        val unlockMode = if (launchDecision.sessionMode == RetroAchievementsSessionMode.HARDCORE) {
                            OfflineUnlockMode.HARDCORE
                        } else {
                            OfflineUnlockMode.SOFTCORE
                        }
                        val offlineType = launchDecision.initialOfflineType ?: OfflineUnlockType.OFFLINE_FROM_START

                        offlineRetroAchievementsSession = OfflineRetroAchievementsSession(
                            userId = context.userId,
                            contentId = context.contentId,
                            gameId = context.cache.gameId,
                            unlockMode = unlockMode,
                            offlineType = offlineType,
                            sessionId = sessionId,
                            startedAtEpochMs = startedAtEpochMs,
                            nextOrderIndex = 0L,
                        )

                        withContext(Dispatchers.IO) {
                            offlineLedgerRepository.appendSessionStart(
                                userId = context.userId,
                                contentId = context.contentId,
                                gameId = context.cache.gameId,
                                sessionId = sessionId,
                                startedAtEpochMs = startedAtEpochMs,
                                isHardcore = unlockMode == OfflineUnlockMode.HARDCORE,
                                unlockMode = unlockMode,
                                offlineType = offlineType,
                            )
                        }

                        if (unlockMode == OfflineUnlockMode.HARDCORE) {
                            hardcoreOfflineLossTracker.markPendingUnlocks(
                                userId = context.userId,
                                contentId = context.contentId,
                                gameTitle = rom.name,
                            )
                        }

                        emulatorManager.setupRetroAchievements(achievementData, runtimeConfig)
                        emitRetroAchievementsModeToast(
                            status = ToastEvent.RetroAchievementsModeStatus.SOFTCORE_OFFLINE,
                            offlineNoInternetAtStart = launchDecision.offlineDueToNoInternetAtStart,
                        )
                        emitRetroAchievementsLoadedPopup(achievementData)
                    }
                }
            }
        }
    }

    private fun emitRetroAchievementsModeToast(
        status: ToastEvent.RetroAchievementsModeStatus,
        offlineNoInternetAtStart: Boolean = false,
    ) {
        _toastEvent.tryEmit(
            ToastEvent.RetroAchievementsMode(
                status = status,
                offlineNoInternetAtStart = offlineNoInternetAtStart,
            )
        )
    }

    private fun emitRetroAchievementsLoadedPopup(achievementData: GameAchievementData) {
        if (achievementData.hasAchievements) {
            _raIntegrationEvent.tryEmit(
                RAIntegrationEvent.Loaded(
                    icon = achievementData.icon,
                    unlockedAchievements = achievementData.unlockedAchievementCount,
                    totalAchievements = achievementData.totalAchievementCount,
                )
            )
        } else {
            _raIntegrationEvent.tryEmit(RAIntegrationEvent.LoadedNoAchievements(achievementData.icon))
        }
    }

    private data class OfflineRetroAchievementsContext(
        val userId: String,
        val contentId: String,
        val cache: OfflinePrefetchCacheFile,
        val achievementData: GameAchievementData,
        val missingCache: Boolean,
    )

    private suspend fun buildOfflineRetroAchievementsContext(rom: Rom): OfflineRetroAchievementsContext? {
        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return null
        val userId = userAuth.username
        val contentId = rom.retroAchievementsHash

        val cache = try {
            withContext(Dispatchers.IO) {
                offlinePrefetchCacheRepository.readValid(userId, contentId)
            }
        } catch (_: Exception) {
            null
        }

        val gameSummary = retroAchievementsRepository.getGameSummary(rom.retroAchievementsHash)

        if (cache == null) {
            return OfflineRetroAchievementsContext(
                userId = userId,
                contentId = contentId,
                cache = OfflinePrefetchCacheFile(),
                achievementData = GameAchievementData.withDisabledRetroAchievementsIntegration(
                    status = GameAchievementData.IntegrationStatus.DISABLED_LOAD_ERROR,
                    icon = gameSummary?.icon,
                ),
                missingCache = true,
            )
        }

        val isHardcoreModeEnabled = emulatorSession.isRetroAchievementsHardcoreModeEnabled
        val unlockedIds = try {
            withContext(Dispatchers.IO) {
                retroAchievementsDao.getGameUserUnlockedAchievements(cache.gameId, isHardcoreModeEnabled).map { it.achievementId }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }

        val lockedAchievements = cache.achievements
            .asSequence()
            .filterNot { unlockedIds.contains(it.id) }
            .map { RASimpleAchievement(it.id, it.memoryAddress) }
            .toList()

        val achievementData = if (cache.achievements.isEmpty()) {
            GameAchievementData.withLimitedRetroAchievementsIntegration(
                richPresencePatch = cache.richPresencePatch,
                icon = gameSummary?.icon,
            )
        } else {
            GameAchievementData.withFullRetroAchievementsIntegration(
                lockedAchievements = lockedAchievements,
                leaderboards = emptyList(), // Leaderboards are disabled in offline mode (POC).
                totalAchievementCount = cache.achievements.size,
                richPresencePatch = cache.richPresencePatch,
                icon = gameSummary?.icon,
            )
        }

        return OfflineRetroAchievementsContext(
            userId = userId,
            contentId = contentId,
            cache = cache,
            achievementData = achievementData,
            missingCache = false,
        )
    }

    private fun startTrackingFps() {
        sessionCoroutineScope.launch {
            while (isActive) {
                delay(1.seconds)
                _currentFps.value = emulatorManager.getFps().roundToInt()
            }
        }
    }

    private fun filterRomPauseMenuOption(option: RomPauseMenuOption): Boolean {
        return when (option) {
            RomPauseMenuOption.SAVE_STATE -> emulatorSession.areSaveStatesAllowed()
            RomPauseMenuOption.REWIND -> settingsRepository.isRewindEnabled() && emulatorSession.areSaveStateLoadsAllowed()
            RomPauseMenuOption.LOAD_STATE -> emulatorSession.areSaveStateLoadsAllowed()
            RomPauseMenuOption.CHEATS -> emulatorSession.areCheatsEnabled()
            RomPauseMenuOption.VIEW_ACHIEVEMENTS -> emulatorSession.isRetroAchievementsEnabledForSession()
            else -> true
        }
    }

    private fun ensureEmulatorIsRunning(): Flow<Unit> {
        return _emulatorState.filter { it.isRunning() }.take(1).map { }
    }

    private suspend fun startEmulatorSession(
        sessionType: EmulatorSession.SessionType,
        isRetroAchievementsHardcoreModeEnabled: Boolean = settingsRepository.isRetroAchievementsHardcoreEnabled(),
    ) {
        val isUserAuthenticatedInRetroAchievements = retroAchievementsRepository.isUserAuthenticated()
        emulatorSession.startSession(
            areRetroAchievementsEnabled = isUserAuthenticatedInRetroAchievements,
            isRetroAchievementsHardcoreModeEnabled = isRetroAchievementsHardcoreModeEnabled,
            sessionType = sessionType,
        )
    }

    private fun dispatchSessionUpdateActions(actions: List<EmulatorSessionUpdateAction>) {
        actions.forEach {
            when (it) {
                EmulatorSessionUpdateAction.DisableRetroAchievements -> {
                    _achievementsEvent.tryEmit(RAEventUi.Reset)
                    emulatorManager.unloadRetroAchievementsData()
                    raSessionJob?.cancel()
                    raSessionJob = null
                    activeRuntimeBridgeConfig = null
                    activeRuntimePath = RetroAchievementsRuntimePath.DISABLED
                    didShowRuntimeFallbackEvent = false
                    announcedMasteryKeys.clear()
                    pendingRuntimeAchievementTriggers.clear()
                    pendingRuntimeLeaderboardCompletions.clear()
                }
                EmulatorSessionUpdateAction.EnableRetroAchievements -> {
                    (emulatorSession.currentSessionType() as? EmulatorSession.SessionType.RomSession)?.rom?.let { currentRom ->
                        startRetroAchievementsSession(
                            rom = currentRom,
                            launchDecision = RetroAchievementsLaunchDecision(
                                networkMode = retroAchievementsNetworkMode,
                                sessionMode = retroAchievementsSessionMode,
                                initialOfflineType = if (retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING) {
                                    if (startedSessionOnlineLive) {
                                        OfflineUnlockType.OFFLINE_AFTER_START
                                    } else {
                                        OfflineUnlockType.OFFLINE_FROM_START
                                    }
                                } else {
                                    null
                                },
                                isHardcoreEligibleAfterOnlineStart = isHardcoreEligibleAfterOnlineStart,
                                offlineDueToNoInternetAtStart = !startedSessionOnlineLive && retroAchievementsNetworkMode == RetroAchievementsNetworkMode.OFFLINE_ACCUMULATING,
                            ),
                        )
                    }
                }
                EmulatorSessionUpdateAction.NotifyRetroAchievementsModeSwitch -> {
                    _toastEvent.tryEmit(ToastEvent.CannotSwitchRetroAchievementsMode)
                }
            }
        }
    }

    private fun logRaRuntimeEvent(event: RAEvent) {
        when (event) {
            is RAEvent.OnAchievementTriggered -> {
                pendingRuntimeAchievementTriggers[event.achievementId] = System.currentTimeMillis()
                logRaTrace("runtime_event_achievement_triggered", "achievement_id" to event.achievementId)
            }
            is RAEvent.OnLeaderboardAttemptCompleted -> {
                pendingRuntimeLeaderboardCompletions[event.leaderboardId] = System.currentTimeMillis()
                logRaTrace(
                    "runtime_event_leaderboard_completed",
                    "leaderboard_id" to event.leaderboardId,
                    "value" to event.value,
                )
            }
            is RAEvent.OnGameCompleted -> {
                logRaTrace("runtime_event_game_completed", "subset_id" to event.subsetId)
            }
            is RAEvent.OnSubsetCompleted -> {
                logRaTrace("runtime_event_subset_completed", "subset_id" to event.subsetId)
            }
            is RAEvent.OnServerError -> {
                logRaTrace(
                    "runtime_event_server_error",
                    "api" to event.api,
                    "related_id" to event.relatedId,
                    "result_code" to event.resultCode,
                )
            }
            RAEvent.OnDisconnected -> {
                logRaTrace("runtime_event_disconnected")
            }
            RAEvent.OnReconnected -> {
                logRaTrace("runtime_event_reconnected")
            }
            is RAEvent.OnRuntimeFallback -> {
                logRaTrace("runtime_event_fallback", "reason" to event.reason.name)
            }
            is RAEvent.OnAchievementPrimed,
            is RAEvent.OnAchievementUnPrimed,
            is RAEvent.OnAchievementProgressUpdated,
            is RAEvent.OnLeaderboardAttemptStarted,
            is RAEvent.OnLeaderboardAttemptUpdated,
            is RAEvent.OnLeaderboardAttemptCancelled -> {
                // Keep high-volume runtime events out of structured trace logs.
            }
        }
    }

    private fun logRaTrace(eventType: String, vararg fields: Pair<String, Any?>) {
        if (!isDebugBuild()) {
            return
        }

        val message = buildString {
            append("event_type=").append(eventType)
            append(" network_mode=").append(retroAchievementsNetworkMode.name)
            append(" session_mode=").append(retroAchievementsSessionMode.name)
            append(" game_id=").append(currentRetroAchievementsGameId ?: "none")
            append(" runtime_path=").append(activeRuntimePath.traceValue)
            append(" session_active=").append(currentSessionIsActive())
            fields.forEach { (key, value) ->
                if (value != null) {
                    append(' ')
                    append(key)
                    append('=')
                    append(value.toString().replace(' ', '_'))
                }
            }
        }
        Log.i(RA_TRACE_TAG, message)
    }

    private fun currentSessionIsActive(): Boolean {
        return isRetroAchievementsOnlineSessionStarted || offlineRetroAchievementsSession != null
    }

    private fun isDebugBuild(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override fun onCleared() {
        super.onCleared()
        sessionCoroutineScope.cancel()
        emulatorManager.cleanEmulator()
    }

    private class EmulatorSessionCoroutineScope : CoroutineScope {
        private var currentCoroutineContext: CoroutineContext = EmptyCoroutineContext

        override val coroutineContext: CoroutineContext get() = currentCoroutineContext

        fun notifyNewSessionStarted() {
            cancel()
            currentCoroutineContext = SupervisorJob() + Dispatchers.Main.immediate
        }

        fun cancel() {
            currentCoroutineContext.cancel()
        }
    }
}
