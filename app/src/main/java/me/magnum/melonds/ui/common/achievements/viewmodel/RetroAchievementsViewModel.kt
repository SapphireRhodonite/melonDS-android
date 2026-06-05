package me.magnum.melonds.ui.common.achievements.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievement
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.ui.romdetails.model.AchievementBucketUiModel
import me.magnum.melonds.ui.romdetails.model.AchievementSetUiModel
import me.magnum.melonds.ui.romdetails.model.RomAchievementsSummary
import me.magnum.melonds.ui.romdetails.model.RomRetroAchievementsUiState
import me.magnum.rcheevosapi.exception.UserTokenExpiredException
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RALeaderboard
import me.magnum.rcheevosapi.model.RALeaderboardRanking
import me.magnum.rcheevosapi.model.RAUserAuth

abstract class RetroAchievementsViewModel (
    private val retroAchievementsRepository: RetroAchievementsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RomRetroAchievementsUiState>(RomRetroAchievementsUiState.Loading)
    val uiState by lazy {
        loadAchievements()
        _uiState.asStateFlow()
    }

    private val _viewAchievementEvent = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val viewAchievementEvent = _viewAchievementEvent.asSharedFlow()

    private var achievementLoadJob: Job? = null

    protected abstract fun getRom(): Rom
    protected open suspend fun getPendingLedgerAchievementIds(rom: Rom): Set<Long> = emptySet()
    protected open suspend fun getRuntimeBucketByAchievementId(rom: Rom): Map<Long, AchievementBucketUiModel.Bucket> = emptyMap()
    protected open suspend fun getRuntimeSubsetOrder(rom: Rom): Map<Long, Int> = emptyMap()
    protected open suspend fun getUserGameData(rom: Rom, forHardcoreMode: Boolean) =
        retroAchievementsRepository.getUserGameData(rom.retroAchievementsHash, forHardcoreMode)

    protected abstract suspend fun buildAchievementBuckets(
        achievements: List<RAUserAchievement>,
        runtimeBucketByAchievementId: Map<Long, AchievementBucketUiModel.Bucket>,
    ): List<AchievementBucketUiModel>

    private fun loadAchievements() {
        achievementLoadJob?.cancel()
        achievementLoadJob = viewModelScope.launch {
            val userAuth = retroAchievementsRepository.getUserAuthentication()
            when (userAuth) {
                is RAUserAuth.Authenticated -> {
                    val rom = kotlin.runCatching { getRom() }.getOrElse {
                        _uiState.value = RomRetroAchievementsUiState.AchievementLoadError
                        return@launch
                    }
                    val forHardcoreMode = settingsRepository.isRetroAchievementsHardcoreEnabled()
                    val runtimeBucketByAchievementId = withContext(Dispatchers.Default) {
                        runCatching { getRuntimeBucketByAchievementId(rom) }.getOrElse { emptyMap() }
                    }
                    val runtimeSubsetOrder = withContext(Dispatchers.Default) {
                        runCatching { getRuntimeSubsetOrder(rom) }.getOrElse { emptyMap() }
                    }
                    getUserGameData(rom, forHardcoreMode).fold(
                        onSuccess = { userGameData ->
                            val sets = userGameData?.sets.orEmpty().map { set ->
                                AchievementSetUiModel(
                                    setId = set.id.id,
                                    setTitle = set.title,
                                    setType = set.type,
                                    setIcon = set.iconUrl,
                                    setSummary = buildAchievementsSummary(forHardcoreMode, set.achievements),
                                    buckets = buildAchievementBuckets(set.achievements, runtimeBucketByAchievementId),
                                    leaderboards = set.leaderboards,
                                )
                            }
                            val orderedSets = if (runtimeSubsetOrder.isNotEmpty()) {
                                sets.sortedWith(
                                    compareBy<AchievementSetUiModel> { runtimeSubsetOrder[it.setId] ?: Int.MAX_VALUE }
                                        .thenBy { it.setId }
                                )
                            } else {
                                sets
                            }
                            val pendingLedgerAchievementIds = withContext(Dispatchers.IO) {
                                runCatching { getPendingLedgerAchievementIds(rom) }.getOrElse { emptySet() }
                            }
                            _uiState.value = RomRetroAchievementsUiState.Ready(
                                sets = orderedSets,
                                pendingLedgerAchievementIds = pendingLedgerAchievementIds,
                            )
                        },
                        onFailure = {
                            ensureActive()
                            if (it is UserTokenExpiredException) {
                                val userAuth = retroAchievementsRepository.getUserAuthentication()
                                val existingUsername = (userAuth as? RAUserAuth.AuthenticationExpired)?.username
                                _uiState.value = RomRetroAchievementsUiState.LoggedOut(existingUsername)
                            } else {
                                _uiState.value = RomRetroAchievementsUiState.AchievementLoadError
                            }
                        },
                    )
                }
                is RAUserAuth.AuthenticationExpired -> _uiState.value = RomRetroAchievementsUiState.LoggedOut(userAuth.username)
                null -> _uiState.value = RomRetroAchievementsUiState.LoggedOut(null)
            }
        }
    }

    fun login(username: String, password: String) {
        _uiState.value = RomRetroAchievementsUiState.Loading
        viewModelScope.launch {
            val result = retroAchievementsRepository.login(username, password)
            if (result.isSuccess) {
                loadAchievements()
            } else {
                _uiState.value = RomRetroAchievementsUiState.LoginError
            }
        }
    }

    fun retryLoadAchievements() {
        _uiState.value = RomRetroAchievementsUiState.Loading
        loadAchievements()
    }

    fun viewAchievement(achievement: RAAchievement) {
        val achievementUrl = "https://retroachievements.org/achievement/${achievement.id}"
        _viewAchievementEvent.tryEmit(achievementUrl)
    }

    fun viewLeaderboard(leaderboard: RALeaderboard) {
        val leaderboardUrl = "https://retroachievements.org/leaderboardinfo.php?i=${leaderboard.id}"
        _viewAchievementEvent.tryEmit(leaderboardUrl)
    }

    suspend fun getLeaderboardRanking(leaderboard: RALeaderboard): Result<RALeaderboardRanking> {
        return retroAchievementsRepository.getLeaderboardRanking(leaderboard.id)
    }

    private fun buildAchievementsSummary(forHardcoreMode: Boolean, userAchievements: List<RAUserAchievement>): RomAchievementsSummary {
        return RomAchievementsSummary(
            forHardcoreMode = forHardcoreMode,
            totalAchievements = userAchievements.size,
            completedAchievements = userAchievements.count { it.isUnlocked },
            totalPoints = userAchievements.sumOf { it.userPointsWorth() },
        )
    }
}
