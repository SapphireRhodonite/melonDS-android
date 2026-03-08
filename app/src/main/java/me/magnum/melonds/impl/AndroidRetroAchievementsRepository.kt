package me.magnum.melonds.impl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.common.suspendMapCatching
import me.magnum.melonds.common.suspendRecoverCatching
import me.magnum.melonds.common.suspendRunCatching
import me.magnum.melonds.common.workers.RetroAchievementsSubmissionWorker
import me.magnum.melonds.database.daos.RetroAchievementsDao
import me.magnum.melonds.database.entities.retroachievements.RAGameEntity
import me.magnum.melonds.database.entities.retroachievements.RAGameHashEntity
import me.magnum.melonds.database.entities.retroachievements.RAGameSetMetadata
import me.magnum.melonds.database.entities.retroachievements.RAPendingAchievementSubmissionEntity
import me.magnum.melonds.database.entities.retroachievements.RAUserAchievementEntity
import me.magnum.melonds.domain.model.retroachievements.RAAchievementSetSummary
import me.magnum.melonds.domain.model.retroachievements.RAGameSummary
import me.magnum.melonds.domain.model.retroachievements.RARuntimeUserAchievement
import me.magnum.melonds.domain.model.retroachievements.RASimpleRuntimeAchievementBucketEntry
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievement
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievementSet
import me.magnum.melonds.domain.model.retroachievements.RAUserGameData
import me.magnum.melonds.domain.model.retroachievements.exception.RAGameNotExist
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.impl.mappers.retroachievements.mapToEntity
import me.magnum.melonds.impl.mappers.retroachievements.mapToModel
import me.magnum.melonds.utils.enumValueOfIgnoreCase
import me.magnum.rcheevosapi.RAApi
import me.magnum.rcheevosapi.RAUserAuthStore
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RAAwardAchievementResponse
import me.magnum.rcheevosapi.model.RAGame
import me.magnum.rcheevosapi.model.RAGameId
import me.magnum.rcheevosapi.model.RALeaderboard
import me.magnum.rcheevosapi.model.RASetId
import me.magnum.rcheevosapi.model.RASubmitLeaderboardEntryResponse
import me.magnum.rcheevosapi.model.RAUserAuth
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class AndroidRetroAchievementsRepository(
    private val raApi: RAApi,
    private val retroAchievementsDao: RetroAchievementsDao,
    private val raUserAuthStore: RAUserAuthStore,
    private val sharedPreferences: SharedPreferences,
    private val context: Context,
) : RetroAchievementsRepository {

    private companion object {
        const val RA_HASH_LIBRARY_LAST_UPDATED = "ra_hash_library_last_updated"
        const val PENDING_ACHIEVEMENT_SUBMISSION_WORKER_NAME = "ra_pending_achievement_submission_worker"
        const val RA_UNOFFICIAL_ENABLED = "ra_unofficial_enabled"
        const val RA_TRACE_TAG = "RATrace"
    }

    override suspend fun isUserAuthenticated(): Boolean {
        return raUserAuthStore.getUserAuth() != null
    }

    override suspend fun getUserAuthentication(): RAUserAuth? {
        return raUserAuthStore.getUserAuth()
    }

    override suspend fun login(username: String, password: String): Result<Unit> {
        return raApi.login(username, password)
    }

    override suspend fun logout() {
        retroAchievementsDao.deleteAllAchievementUserData()
        raUserAuthStore.clearUserAuth()
    }

    override suspend fun getCachedUserGameData(gameHash: String, forHardcoreMode: Boolean): Result<RAUserGameData?> = suspendRunCatching {
        val gameId = retroAchievementsDao.getGameHashEntity(gameHash)?.let { RAGameId(it.gameId) } ?: return@suspendRunCatching null
        val gameData = retroAchievementsDao.getGameWithSets(gameId.id)?.mapToModel()
        val userUnlocks = retroAchievementsDao.getGameUserUnlockedAchievements(gameId.id, forHardcoreMode).map { it.achievementId }
        buildUserGameData(gameData, userUnlocks, forHardcoreMode)
    }

    override suspend fun getUserGameData(gameHash: String, forHardcoreMode: Boolean): Result<RAUserGameData?> {
        return getUserGameDataInternal(gameHash, forHardcoreMode, forceRefresh = false)
    }

    override suspend fun refreshUserGameData(gameHash: String, forHardcoreMode: Boolean): Result<RAUserGameData?> {
        return getUserGameDataInternal(gameHash, forHardcoreMode, forceRefresh = true)
    }

    private suspend fun getUserGameDataInternal(
        gameHash: String,
        forHardcoreMode: Boolean,
        forceRefresh: Boolean,
    ): Result<RAUserGameData?> {
        val gameIdResult = getGameIdFromGameHash(gameHash, forceRefreshHashLibrary = forceRefresh)
        if (gameIdResult.isFailure) {
            return Result.failure(gameIdResult.exceptionOrNull()!!)
        }

        val gameId = gameIdResult.getOrThrow()
        if (gameId == null) {
            return Result.success(null)
        }

        val gameSetMetadata = retroAchievementsDao.getGameSetMetadata(gameId.id)
        val currentMetadata = CurrentGameSetMetadata(gameId, gameSetMetadata)

        val gameDataResult = fetchGameData(gameId, gameHash, currentMetadata, forceRefresh)
        if (gameDataResult.isFailure) {
            return Result.failure(gameDataResult.exceptionOrNull()!!)
        }

        val userUnlocksResult = fetchGameUserUnlockedAchievements(gameId, forHardcoreMode, currentMetadata, forceRefresh)
        if (userUnlocksResult.isFailure) {
            return Result.failure(userUnlocksResult.exceptionOrNull()!!)
        }

        val gameData = gameDataResult.getOrThrow()
        val userUnlocks = userUnlocksResult.getOrThrow()

        return Result.success(buildUserGameData(gameData, userUnlocks, forHardcoreMode))
    }

    override suspend fun getRuntimeUserAchievements(achievements: List<RAUserAchievement>): List<RARuntimeUserAchievement> = withContext(Dispatchers.Default) {
        val runtimeAchievements = MelonEmulator.getRuntimeAchievements()
        achievements.map { userAchievement ->
            val runtimeAchievement = runtimeAchievements.firstOrNull { it.id == userAchievement.achievement.id }
            RARuntimeUserAchievement(
                userAchievement = userAchievement,
                progress = runtimeAchievement?.value ?: 0,
                target = runtimeAchievement?.target ?: 0,
            )
        }
    }

    override suspend fun getRuntimeAchievementBuckets(): List<RASimpleRuntimeAchievementBucketEntry> = withContext(Dispatchers.Default) {
        MelonEmulator.getRuntimeAchievementBuckets().toList()
    }

    override suspend fun getRuntimeSubsetIds(): List<Long> = withContext(Dispatchers.Default) {
        MelonEmulator.getRuntimeSubsetIds().toList()
    }

    override suspend fun getGameSummary(gameHash: String): RAGameSummary? {
        val gameId = getGameIdFromGameHash(gameHash).getOrNull() ?: return null
        return getGameSummary(gameId)
    }

    override suspend fun getGameSummary(gameId: RAGameId): RAGameSummary? {
        return retroAchievementsDao.getGame(gameId.id)?.let {
            RAGameSummary(
                title = it.title,
                icon = URL(it.icon),
                richPresencePatch = it.richPresencePatch,
            )
        }
    }

    override suspend fun getAchievementSetSummary(setId: RASetId): RAAchievementSetSummary? {
        return retroAchievementsDao.getAchievementSet(setId.id)?.let {
            RAAchievementSetSummary(
                setId = it.id,
                gameId = RAGameId(it.gameId),
                title = it.title,
                type = enumValueOfIgnoreCase(it.type),
                iconUrl = URL(it.iconUrl),
            )
        }
    }

    override suspend fun getAchievement(achievementId: Long): Result<RAAchievement?> {
        return suspendRunCatching {
            retroAchievementsDao.getAchievement(achievementId)
        }.map {
            it?.mapToModel()
        }
    }

    override suspend fun awardAchievement(achievement: RAAchievement, forHardcoreMode: Boolean): Result<RAAwardAchievementResponse> {
        return submitAchievementAward(achievement.id, achievement.gameId, forHardcoreMode).onFailure {
            scheduleAchievementSubmissionJob()
        }
    }

    override suspend fun submitPendingAchievements(): Result<Unit> {
        retroAchievementsDao.getPendingAchievementSubmissions().forEach {
            logRaTrace(
                "pending_award_retry_attempt",
                "achievement_id" to it.achievementId,
                "game_id" to it.gameId,
                "hardcore" to it.forHardcoreMode,
            )
            // Do not schedule resubmission if this fails. The current submission job should schedule another attempt
            val submissionResult = submitAchievementAward(it.achievementId, RAGameId(it.gameId), it.forHardcoreMode)
            if (submissionResult.isFailure) {
                logRaTrace(
                    "pending_award_retry_failed",
                    "achievement_id" to it.achievementId,
                    "game_id" to it.gameId,
                    "hardcore" to it.forHardcoreMode,
                    "error" to (submissionResult.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"),
                )
                return submissionResult.map { }
            }

            retroAchievementsDao.removePendingAchievementSubmission(it)
            logRaTrace(
                "pending_award_retry_success",
                "achievement_id" to it.achievementId,
                "game_id" to it.gameId,
                "hardcore" to it.forHardcoreMode,
            )
        }

        return Result.success(Unit)
    }

    override suspend fun getLeaderboard(leaderboardId: Long): RALeaderboard? {
        return retroAchievementsDao.getLeaderboard(leaderboardId)?.mapToModel()
    }

    override suspend fun submitLeaderboardEntry(leaderboardId: Long, value: Int): Result<RASubmitLeaderboardEntryResponse> {
        val gameHash = retroAchievementsDao.getLeaderboard(leaderboardId)
            ?.gameId
            ?.let { retroAchievementsDao.getAnyGameHashForGameId(it) }
        logRaTrace(
            "leaderboard_submit_attempt",
            "leaderboard_id" to leaderboardId,
            "value" to value,
            "game_hash" to gameHash,
        )
        return raApi.submitLeaderboardEntry(leaderboardId, value, gameHash).onSuccess {
            logRaTrace(
                "leaderboard_submit_success",
                "leaderboard_id" to leaderboardId,
                "rank" to it.rank,
                "entries" to it.numEntries,
            )
        }.onFailure { error ->
            logRaTrace(
                "leaderboard_submit_failed",
                "leaderboard_id" to leaderboardId,
                "error" to (error::class.simpleName ?: "Unknown"),
            )
        }
    }

    override suspend fun startSession(gameHash: String): Result<Unit> {
        val gameId = getGameIdFromGameHash(gameHash).getOrNull() ?: return Result.failure(RAGameNotExist(gameHash))
        logRaTrace(
            "session_start_attempt",
            "game_hash" to gameHash,
            "game_id" to gameId.id,
        )
        return raApi.startSession(gameId).onSuccess {
            logRaTrace(
                "session_start_success",
                "game_hash" to gameHash,
                "game_id" to gameId.id,
            )
        }.onFailure { error ->
            logRaTrace(
                "session_start_failed",
                "game_hash" to gameHash,
                "game_id" to gameId.id,
                "error" to (error::class.simpleName ?: "Unknown"),
            )
        }
    }

    override suspend fun sendSessionHeartbeat(gameHash: String, richPresenceDescription: String?) {
        val gameId = getGameIdFromGameHash(gameHash).getOrNull() ?: return
        logRaTrace(
            "session_ping",
            "game_hash" to gameHash,
            "game_id" to gameId.id,
            "rich_presence" to (!richPresenceDescription.isNullOrBlank()),
        )
        raApi.sendPing(gameId, richPresenceDescription)
    }

    private suspend fun submitAchievementAward(achievementId: Long, gameId: RAGameId, forHardcoreMode: Boolean): Result<RAAwardAchievementResponse> {
        // Award the achievement immediately locally
        val userAchievement = RAUserAchievementEntity(
            gameId = gameId.id,
            achievementId = achievementId,
            isUnlocked = true,
            isHardcore = forHardcoreMode,
        )
        retroAchievementsDao.addUserAchievement(userAchievement)

        val gameHash = retroAchievementsDao.getAnyGameHashForGameId(gameId.id)
        logRaTrace(
            "achievement_submit_attempt",
            "achievement_id" to achievementId,
            "game_id" to gameId.id,
            "hardcore" to forHardcoreMode,
            "game_hash" to gameHash,
        )

        return raApi.awardAchievement(achievementId, forHardcoreMode, gameHash).onSuccess { response ->
            logRaTrace(
                "achievement_submit_success",
                "achievement_id" to achievementId,
                "game_id" to gameId.id,
                "hardcore" to forHardcoreMode,
                "awarded" to response.achievementAwarded,
            )
        }.onFailure { error ->
            logRaTrace(
                "achievement_submit_failed",
                "achievement_id" to achievementId,
                "game_id" to gameId.id,
                "hardcore" to forHardcoreMode,
                "error" to (error::class.simpleName ?: "Unknown"),
            )
            // On failure, insert it into the pending achievements to be re-submitted later
            val pendingAchievementSubmissionEntity = RAPendingAchievementSubmissionEntity(
                achievementId = achievementId,
                gameId = gameId.id,
                forHardcoreMode = forHardcoreMode,
            )
            retroAchievementsDao.addPendingAchievementSubmission(pendingAchievementSubmissionEntity)
            logRaTrace(
                "achievement_submit_queued_pending",
                "achievement_id" to achievementId,
                "game_id" to gameId.id,
                "hardcore" to forHardcoreMode,
            )
        }
    }

    private suspend fun getGameIdFromGameHash(gameHash: String, forceRefreshHashLibrary: Boolean = false): Result<RAGameId?> {
        return if (forceRefreshHashLibrary || mustRefreshHashLibrary()) {
            raApi.getGameHashList()
                .onSuccess { gameHashes ->
                    val gameHashEntities = gameHashes.map {
                        RAGameHashEntity(it.key, it.value.id)
                    }
                    retroAchievementsDao.updateGameHashLibrary(gameHashEntities)
                    sharedPreferences.edit {
                        putLong(RA_HASH_LIBRARY_LAST_UPDATED, Clock.System.now().toEpochMilliseconds())
                    }
                }
                .map {
                    it[gameHash]
                }
                .suspendRecoverCatching {
                    retroAchievementsDao.getGameHashEntity(gameHash)?.let {
                        RAGameId(it.gameId)
                    }
                }
        } else {
            suspendRunCatching {
                retroAchievementsDao.getGameHashEntity(gameHash)?.let {
                    RAGameId(it.gameId)
                }
            }
        }
    }

    private suspend fun fetchGameData(
        gameId: RAGameId,
        gameHash: String,
        gameSetMetadata: CurrentGameSetMetadata,
        forceRefresh: Boolean,
    ): Result<RAGame?> {
        return if (forceRefresh || mustRefreshAchievementSet(gameSetMetadata.currentMetadata)) {
            raApi.getGameAchievementSets(gameHash).suspendMapCatching { game ->
                val sets = game.sets.map {
                    it.mapToEntity()
                }
                val achievementEntities = game.sets.flatMap { set ->
                    set.achievements.map { it.mapToEntity() }
                }
                val leaderboardEntities = game.sets.flatMap { set ->
                    set.leaderboards.map { it.mapToEntity() }
                }

                val gameEntity = RAGameEntity(game.id.id, game.richPresencePatch, game.title, game.icon.toString())
                val newMetadata = gameSetMetadata.withNewAchievementSetUpdate()
                retroAchievementsDao.updateGameData(gameEntity, sets, achievementEntities, leaderboardEntities)
                retroAchievementsDao.updateGameSetMetadata(newMetadata)
                game
            }.suspendRecoverCatching { exception ->
                if (gameSetMetadata.isGameAchievementDataKnown()) {
                    // Load DB data because we know that it was previously loaded
                    retroAchievementsDao.getGameWithSets(gameId.id)?.mapToModel()
                } else {
                    // Try to load whatever is cached locally anyway (may be present even if metadata was lost).
                    retroAchievementsDao.getGameWithSets(gameId.id)?.mapToModel() ?: throw exception
                }
            }
        } else {
            suspendRunCatching {
                retroAchievementsDao.getGameWithSets(gameId.id)?.mapToModel()
            }
        }
    }

    private suspend fun fetchGameUserUnlockedAchievements(
        gameId: RAGameId,
        forHardcoreMode: Boolean,
        gameSetMetadata: CurrentGameSetMetadata,
        forceRefresh: Boolean,
    ): Result<List<Long>> {
        return if (forceRefresh || mustRefreshUserData(gameSetMetadata.currentMetadata, forHardcoreMode)) {
            raApi.getUserUnlockedAchievements(gameId, forHardcoreMode).onSuccess { userUnlocks ->
                val userAchievementEntities = userUnlocks.map {
                    RAUserAchievementEntity(
                        gameId = gameId.id,
                        achievementId = it,
                        isUnlocked = true,
                        isHardcore = forHardcoreMode,
                    )
                }

                val newMetadata = gameSetMetadata.withNewUserAchievementsUpdate(forHardcoreMode)
                retroAchievementsDao.updateGameUserUnlockedAchievements(gameId.id, userAchievementEntities)
                retroAchievementsDao.updateGameSetMetadata(newMetadata)
            }.suspendRecoverCatching { exception ->
                if (gameSetMetadata.isUserAchievementDataKnown(forHardcoreMode)) {
                    // Load DB data because we know that it was previously loaded
                    retroAchievementsDao.getGameUserUnlockedAchievements(gameId.id, forHardcoreMode).map {
                        it.achievementId
                    }
                } else {
                    // Try to load whatever is cached locally anyway (may be present even if metadata was lost).
                    retroAchievementsDao.getGameUserUnlockedAchievements(gameId.id, forHardcoreMode).map {
                        it.achievementId
                    }
                }
            }
        } else {
            suspendRunCatching {
                retroAchievementsDao.getGameUserUnlockedAchievements(gameId.id, forHardcoreMode).map {
                    it.achievementId
                }
            }
        }
    }

    private fun mustRefreshHashLibrary(): Boolean {
        val hashLibraryLastUpdateTimestamp = sharedPreferences.getLong(RA_HASH_LIBRARY_LAST_UPDATED, 0)
        val hashLibraryLastUpdate = Instant.fromEpochMilliseconds(hashLibraryLastUpdateTimestamp)

        // Update the game hash library once a day
        return (Clock.System.now() - hashLibraryLastUpdate) > 1.days
    }

    private fun mustRefreshAchievementSet(gameSetMetadata: RAGameSetMetadata?): Boolean {
        if (gameSetMetadata?.lastAchievementSetUpdated == null) {
            return true
        }

        // Update the achievement set once a week
        return (Clock.System.now() - gameSetMetadata.lastAchievementSetUpdated) >= 7.days
    }

    private fun mustRefreshUserData(gameSetMetadata: RAGameSetMetadata?, forHardcoreMode: Boolean): Boolean {
        val lastUserDataUpdateTimestamp = if (forHardcoreMode) {
            gameSetMetadata?.lastHardcoreUserDataUpdated
        } else {
            gameSetMetadata?.lastSoftcoreUserDataUpdated
        }

        if (lastUserDataUpdateTimestamp == null) {
            return true
        }

        // Sync user achievement data once a day
        return (Clock.System.now() - lastUserDataUpdateTimestamp) >= 1.days
    }

    private fun scheduleAchievementSubmissionJob() {
        val workConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<RetroAchievementsSubmissionWorker>()
            .setConstraints(workConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(PENDING_ACHIEVEMENT_SUBMISSION_WORKER_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
    }

    private fun logRaTrace(eventType: String, vararg fields: Pair<String, Any?>) {
        if (!isDebugBuild()) {
            return
        }

        val message = buildString {
            append("event_type=").append(eventType)
            append(" submit_path=").append("kotlin_api")
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

    private fun isDebugBuild(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun buildUserGameData(gameData: RAGame?, userUnlocks: List<Long>, forHardcoreMode: Boolean): RAUserGameData? {
        val includeUnofficialAchievements = sharedPreferences.getBoolean(RA_UNOFFICIAL_ENABLED, true)
        return gameData?.let { game ->
            val userSets = game.sets.map { set ->
                RAUserAchievementSet(
                    id = set.id,
                    gameId = set.gameId,
                    title = set.title,
                    type = set.type,
                    iconUrl = set.iconUrl,
                    achievements = set.achievements
                        .filter { achievement ->
                            achievement.type == RAAchievement.Type.CORE ||
                                (includeUnofficialAchievements && achievement.type == RAAchievement.Type.UNOFFICIAL)
                        }
                        .map { achievement ->
                            RAUserAchievement(
                                achievement = achievement,
                                isUnlocked = userUnlocks.contains(achievement.id),
                                forHardcoreMode = forHardcoreMode,
                            )
                        },
                    leaderboards = set.leaderboards.filter { !it.hidden },
                )
            }

            RAUserGameData(
                id = game.id,
                title = game.title,
                icon = game.icon,
                richPresencePatch = game.richPresencePatch,
                sets = userSets,
            )
        }
    }

    private class CurrentGameSetMetadata(private val gameId: RAGameId, initialMetadata: RAGameSetMetadata?) {
        var currentMetadata = initialMetadata
            private set

        fun withNewAchievementSetUpdate(): RAGameSetMetadata {
            return (currentMetadata?.copy(lastAchievementSetUpdated = Clock.System.now()) ?: RAGameSetMetadata(gameId.id, Clock.System.now(), null, null)).also {
                currentMetadata = it
            }
        }

        fun withNewUserAchievementsUpdate(forHardcoreMode: Boolean): RAGameSetMetadata {
            return if (forHardcoreMode) {
                currentMetadata?.copy(lastHardcoreUserDataUpdated = Clock.System.now()) ?: RAGameSetMetadata(gameId.id, null, null, Clock.System.now()).also {
                    currentMetadata = it
                }
            } else {
                currentMetadata?.copy(lastSoftcoreUserDataUpdated = Clock.System.now()) ?: RAGameSetMetadata(gameId.id, null, Clock.System.now(), null).also {
                    currentMetadata = it
                }
            }
        }

        fun isGameAchievementDataKnown(): Boolean {
            return currentMetadata?.lastAchievementSetUpdated != null
        }

        fun isUserAchievementDataKnown(forHardcoreMode: Boolean): Boolean {
            return if (forHardcoreMode) {
                currentMetadata?.lastHardcoreUserDataUpdated != null
            } else {
                currentMetadata?.lastSoftcoreUserDataUpdated != null
            }
        }
    }
}
