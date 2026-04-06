package me.magnum.melonds.ui.emulator

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import me.magnum.melonds.domain.model.retroachievements.RAEvent
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievement
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.domain.services.EmulatorManager
import me.magnum.melonds.impl.emulator.EmulatorSession
import me.magnum.melonds.impl.retroachievements.offline.OfflineLedgerIntegrity
import me.magnum.melonds.impl.retroachievements.offline.OfflineLedgerRepository
import me.magnum.melonds.ui.common.achievements.ui.model.AchievementUiModel
import me.magnum.melonds.ui.common.achievements.viewmodel.RetroAchievementsViewModel
import me.magnum.melonds.ui.emulator.component.RetroAchievementsSubmissionHandler
import me.magnum.melonds.ui.romdetails.model.AchievementBucketUiModel
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@HiltViewModel
class EmulatorRetroAchievementsViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val retroAchievementsRepository: RetroAchievementsRepository,
    private val offlineLedgerRepository: OfflineLedgerRepository,
    private val emulatorSession: EmulatorSession,
    private val emulatorManager: EmulatorManager,
    private val achievementsSubmissionHandler: RetroAchievementsSubmissionHandler,
) : RetroAchievementsViewModel(retroAchievementsRepository, settingsRepository) {

    private companion object {
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_LOCKED = 1
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_UNLOCKED = 2
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_UNSUPPORTED = 3
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_UNOFFICIAL = 4
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_RECENTLY_UNLOCKED = 5
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_ACTIVE_CHALLENGE = 6
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_ALMOST_THERE = 7
        private const val RC_CLIENT_ACHIEVEMENT_BUCKET_UNSYNCED = 8
    }

    private class TimedUnlockedAchievement(
        val achievementId: Long,
        val unlockedAt: Instant,
    )

    private var activeChallengesIds = emptyList<Long>()
    private var recentlyUnlockedAchievements = emptyList<TimedUnlockedAchievement>()

    init {
        viewModelScope.launch {
            emulatorManager.observeRetroAchievementEvents().collect { event ->
                when (event) {
                    is RAEvent.OnAchievementPrimed -> activeChallengesIds += event.achievementId
                    is RAEvent.OnAchievementUnPrimed -> activeChallengesIds -= event.achievementId
                    is RAEvent.OnAchievementTriggered -> recentlyUnlockedAchievements += TimedUnlockedAchievement(event.achievementId, Clock.System.now())
                    else -> { /* no-op */ }
                }
            }
        }
    }

    fun onSessionReset() {
        activeChallengesIds = emptyList()
    }

    override fun getRom(): Rom {
        val romSession = emulatorSession.currentSessionType() as? EmulatorSession.SessionType.RomSession
        if (romSession == null) {
            error("Emulator must be running a ROM session")
        }

        return romSession.rom
    }

    override suspend fun getRuntimeBucketByAchievementId(rom: Rom): Map<Long, AchievementBucketUiModel.Bucket> {
        return retroAchievementsRepository.getRuntimeAchievementBuckets()
            .associate { it.achievementId to mapRuntimeBucketType(it.bucketType) }
    }

    override suspend fun getRuntimeSubsetOrder(rom: Rom): Map<Long, Int> {
        return retroAchievementsRepository.getRuntimeSubsetIds()
            .withIndex()
            .associate { it.value to it.index }
    }

    override suspend fun buildAchievementBuckets(
        achievements: List<RAUserAchievement>,
        runtimeBucketByAchievementId: Map<Long, AchievementBucketUiModel.Bucket>,
    ): List<AchievementBucketUiModel> {
        val now = Clock.System.now()
        val pendingAchievements = achievementsSubmissionHandler.getPendingAchievementsFlow().firstOrNull() ?: emptyList()
        return retroAchievementsRepository.getRuntimeUserAchievements(achievements).groupingBy { runtimeAchievement ->
            when {
                pendingAchievements.any { it.id == runtimeAchievement.userAchievement.achievement.id } -> AchievementBucketUiModel.Bucket.PendingSubmissions
                runtimeBucketByAchievementId.containsKey(runtimeAchievement.userAchievement.achievement.id) ->
                    runtimeBucketByAchievementId.getValue(runtimeAchievement.userAchievement.achievement.id)
                runtimeAchievement.userAchievement.isUnlocked -> {
                    val recentlyUnlockedAchievement = recentlyUnlockedAchievements.firstOrNull { it.achievementId == runtimeAchievement.userAchievement.achievement.id }
                    if (recentlyUnlockedAchievement != null && (now - recentlyUnlockedAchievement.unlockedAt) < 10.minutes) {
                        AchievementBucketUiModel.Bucket.RecentlyUnlocked
                    } else {
                        AchievementBucketUiModel.Bucket.Unlocked
                    }
                }
                activeChallengesIds.any { it == runtimeAchievement.userAchievement.achievement.id } -> AchievementBucketUiModel.Bucket.ActiveChallenges
                runtimeAchievement.relativeProgress() >= 0.8f -> AchievementBucketUiModel.Bucket.AlmostThere
                else -> AchievementBucketUiModel.Bucket.Locked
            }
        }.aggregate { _, accumulator: MutableList<AchievementUiModel>?, element, _ ->
            val achievementUiModel = AchievementUiModel.RuntimeAchievementUiModel(element)
            accumulator?.apply {
                add(achievementUiModel)
            } ?: mutableListOf(achievementUiModel)
        }.map {
            val achievements = if (it.key == AchievementBucketUiModel.Bucket.RecentlyUnlocked) {
                // Sort recently unlocked achievements
                it.value.sortedByDescending { achievement ->
                    recentlyUnlockedAchievements.firstOrNull { it.achievementId == achievement.actualAchievement().id }?.unlockedAt
                }
            } else {
                it.value
            }
            AchievementBucketUiModel(it.key, achievements)
        }.sortedBy {
            it.bucket.displayOrder
        }
    }

    override suspend fun getPendingLedgerAchievementIds(rom: Rom): Set<Long> {
        val userAuth = retroAchievementsRepository.getUserAuthentication() ?: return emptySet()
        val status = offlineLedgerRepository.getStatus(userAuth.username, rom.retroAchievementsHash)
        if (status.integrity != OfflineLedgerIntegrity.OK) {
            return emptySet()
        }

        return status.pendingUnlocks.map { it.achievementId }.toSet()
    }

    private fun mapRuntimeBucketType(runtimeBucketType: Int): AchievementBucketUiModel.Bucket {
        return when (runtimeBucketType) {
            RC_CLIENT_ACHIEVEMENT_BUCKET_LOCKED -> AchievementBucketUiModel.Bucket.Locked
            RC_CLIENT_ACHIEVEMENT_BUCKET_UNLOCKED -> AchievementBucketUiModel.Bucket.Unlocked
            RC_CLIENT_ACHIEVEMENT_BUCKET_UNSUPPORTED -> AchievementBucketUiModel.Bucket.Unsupported
            RC_CLIENT_ACHIEVEMENT_BUCKET_UNOFFICIAL -> AchievementBucketUiModel.Bucket.Unofficial
            RC_CLIENT_ACHIEVEMENT_BUCKET_RECENTLY_UNLOCKED -> AchievementBucketUiModel.Bucket.RecentlyUnlocked
            RC_CLIENT_ACHIEVEMENT_BUCKET_ACTIVE_CHALLENGE -> AchievementBucketUiModel.Bucket.ActiveChallenges
            RC_CLIENT_ACHIEVEMENT_BUCKET_ALMOST_THERE -> AchievementBucketUiModel.Bucket.AlmostThere
            RC_CLIENT_ACHIEVEMENT_BUCKET_UNSYNCED -> AchievementBucketUiModel.Bucket.Unsynced
            else -> AchievementBucketUiModel.Bucket.Locked
        }
    }
}
