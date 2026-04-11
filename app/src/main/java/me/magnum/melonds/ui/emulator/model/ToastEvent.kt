package me.magnum.melonds.ui.emulator.model

import me.magnum.melonds.domain.model.VideoRenderer

sealed class ToastEvent {
    data object GbaLoadFailed : ToastEvent()
    data object RewindNotEnabled : ToastEvent()
    data object RewindNotAvailableWhileRAHardcoreModeEnabled : ToastEvent()
    data object StateSaveFailed : ToastEvent()
    data object StateLoadFailed : ToastEvent()
    data object StateStateDoesNotExist : ToastEvent()
    data object QuickSaveSuccessful : ToastEvent()
    data object QuickLoadSuccessful : ToastEvent()
    data object CannotLoadSaveStatesWhenRAHardcoreIsEnabled : ToastEvent()
    data object CannotUseCheatsWhenRAHardcoreIsEnabled : ToastEvent()
    data object CannotSaveStateWhenRunningFirmware : ToastEvent()
    data object CannotLoadStateWhenRunningFirmware : ToastEvent()
    data object CannotSwitchRetroAchievementsMode : ToastEvent()
    data object OfflineAchievementsLedgerTampered : ToastEvent()
    data object OfflineAchievementsSyncFailed : ToastEvent()
    data class HardcoreOfflineUnsyncedWarning(
        val pendingHardcoreCount: Int,
    ) : ToastEvent()
    data class RetroAchievementsMode(
        val status: RetroAchievementsModeStatus,
        val offlineNoInternetAtStart: Boolean = false,
    ) : ToastEvent()
    data class OfflineSoftcorePendingNotice(
        val pendingSoftcoreCount: Int,
    ) : ToastEvent()

    enum class OfflineAchievementNotSyncedReason {
        MISSING_FROM_CURRENT_SET,
        DEFINITION_CHANGED,
        NOT_IN_PREFETCH_CACHE,
    }

    enum class RetroAchievementsModeStatus {
        SOFTCORE,
        HARDCORE,
        SOFTCORE_OFFLINE,
    }

    data class OfflineAchievementNotSynced(
        val title: String,
        val reason: OfflineAchievementNotSyncedReason,
    ) : ToastEvent()

    data class OfflineAchievementsNotSyncedSummary(
        val skippedCount: Int,
    ) : ToastEvent()
    data class RendererInitFailed(
        val renderer: VideoRenderer,
    ) : ToastEvent()
    data class RendererDebugCaptureLogged(
        val captureId: String,
    ) : ToastEvent()
    data object RendererDebugCaptureFailed : ToastEvent()
    data object GbaModeNotSupported : ToastEvent()
    data object InternalError : ToastEvent()
}
