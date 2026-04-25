package me.magnum.melonds.ui.emulator.model

import me.magnum.melonds.domain.model.RomInfo
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.ui.emulator.rewind.model.RewindWindow

sealed class EmulatorUiEvent {
    sealed class OpenScreen : EmulatorUiEvent() {
        data object SettingsScreen : OpenScreen()
        data class CheatsScreen(val romInfo: RomInfo) : OpenScreen()
    }
    data class ShowPauseMenu(val pauseMenu: PauseMenu) : EmulatorUiEvent()
    data class ShowRewindWindow(val rewindWindow: RewindWindow) : EmulatorUiEvent()
    data class ShowRomSaveStates(val saveStates: List<SaveStateSlot>, val reason: Reason) : EmulatorUiEvent() {
        enum class Reason {
            SAVING,
            LOADING,
        }
    }
    data object ShowAchievementList : EmulatorUiEvent()
    data object ShowPendingSubmissionsDialog : EmulatorUiEvent()
    data object CloseEmulator : EmulatorUiEvent()
    data object ShowDualScreenPresets : EmulatorUiEvent()
    data object ShowRendererDebugMenu : EmulatorUiEvent()
    data object ShowRenderer2DDebugControls : EmulatorUiEvent()
    data class ShowOfflineAchievementsSyncChoice(val pendingUnlockCount: Int) : EmulatorUiEvent()
    data class ShowHardcorePendingExitWarning(
        val pendingHardcoreCount: Int,
    ) : EmulatorUiEvent()
    data class ShowOfflineAchievementsSyncProgress(val totalUnlockCount: Int) : EmulatorUiEvent()
    data object HideOfflineAchievementsSyncProgress : EmulatorUiEvent()
}
