package me.magnum.melonds.ui.romdetails

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.ui.common.rom.EmulatorLaunchValidatorDelegate
import me.magnum.melonds.ui.emulator.EmulatorActivity
import me.magnum.melonds.ui.romdetails.ui.RomDetailsScreen
import me.magnum.melonds.ui.theme.MelonTheme
import me.magnum.melonds.ui.romdetails.model.RomDetailsToastEvent

@AndroidEntryPoint
class RomDetailsActivity : AppCompatActivity() {

    companion object {
        const val KEY_ROM = "rom"
    }

    private val romDetailsViewModel by viewModels<RomDetailsViewModel>()
    private val romRetroAchievementsViewModel by viewModels<RomDetailsRetroAchievementsViewModel>()

    override fun onResume() {
        super.onResume()
        romRetroAchievementsViewModel.refreshOfflineAchievementsStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val emulatorLauncherValidatorDelegate = EmulatorLaunchValidatorDelegate(this, object : EmulatorLaunchValidatorDelegate.Callback {
            override fun onRomValidated(rom: Rom) {
                val intent = EmulatorActivity.getRomEmulatorActivityIntent(this@RomDetailsActivity, rom)
                startActivity(intent)
            }

            override fun onFirmwareValidated(consoleType: ConsoleType) {
                // Do nothing (can't launch firmware fro this screen)
            }

            override fun onValidationAborted() {
                // Do nothing
            }
        })

        setContent {
            val rom by romDetailsViewModel.rom.collectAsState()
            val romConfig by romDetailsViewModel.romConfigUiState.collectAsState()

            val retroAchievementsUiState by romRetroAchievementsViewModel.uiState.collectAsState()
            val offlineAchievementsUiState by romRetroAchievementsViewModel.offlineAchievementsUiState.collectAsState()

            LaunchedEffect(null) {
                romRetroAchievementsViewModel.viewAchievementEvent.collect {
                    launchViewAchievementIntent(it)
                }
            }

            LaunchedEffect(Unit) {
                romRetroAchievementsViewModel.toastEvent.collectLatest { event ->
                    val message = when (event) {
                        is RomDetailsToastEvent.OfflineAchievementNotSynced -> {
                            val messageRes = when (event.reason) {
                                RomDetailsToastEvent.OfflineAchievementNotSyncedReason.MISSING_FROM_CURRENT_SET -> R.string.offline_ra_sync_skipped_missing_toast
                                RomDetailsToastEvent.OfflineAchievementNotSyncedReason.DEFINITION_CHANGED -> R.string.offline_ra_sync_skipped_definition_changed_toast
                                RomDetailsToastEvent.OfflineAchievementNotSyncedReason.NOT_IN_PREFETCH_CACHE -> R.string.offline_ra_sync_skipped_cache_mismatch_toast
                                RomDetailsToastEvent.OfflineAchievementNotSyncedReason.SERVER_REJECTED -> R.string.offline_ra_sync_skipped_server_rejected_toast
                            }
                            if (event.reason == RomDetailsToastEvent.OfflineAchievementNotSyncedReason.SERVER_REJECTED) {
                                getString(
                                    messageRes,
                                    event.title,
                                    event.reasonDetail ?: getString(R.string.offline_ra_sync_skipped_server_rejected_unknown_reason),
                                )
                            } else {
                                getString(messageRes, event.title)
                            }
                        }
                        is RomDetailsToastEvent.OfflineAchievementsNotSyncedSummary -> {
                            getString(R.string.offline_ra_sync_skipped_summary_toast, event.skippedCount)
                        }
                    }

                    Toast.makeText(this@RomDetailsActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            LaunchedEffect(retroAchievementsUiState) {
                romRetroAchievementsViewModel.refreshOfflineAchievementsStatus()
            }

            MelonTheme {
                RomDetailsScreen(
                    rom = rom,
                    romConfigUiState = romConfig,
                    retroAchievementsUiState = retroAchievementsUiState,
                    offlineAchievementsUiState = offlineAchievementsUiState,
                    onNavigateBack = { onNavigateUp() },
                    onLaunchRom = {
                        emulatorLauncherValidatorDelegate.validateRom(it)
                    },
                    onRomConfigUpdate = {
                        romDetailsViewModel.onRomConfigUpdateEvent(it)
                    },
                    onCustomInputConfigEdited = {
                        romDetailsViewModel.refreshRom()
                    },
                    onRetroAchievementsLogin = { username, password ->
                        romRetroAchievementsViewModel.login(username, password)
                    },
                    onRetroAchievementsRetryLoad = {
                        romRetroAchievementsViewModel.retryLoadAchievements()
                    },
                    onViewAchievement = {
                        romRetroAchievementsViewModel.viewAchievement(it)
                    },
                    onOfflineSyncNow = {
                        romRetroAchievementsViewModel.syncOfflineAchievementsNow()
                    },
                )
            }
        }
    }

    private fun launchViewAchievementIntent(achievementUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = achievementUrl.toUri()
        }
        startActivity(intent)
    }
}
