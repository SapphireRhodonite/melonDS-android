package me.magnum.melonds.impl.retroachievements.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.magnum.rcheevosapi.RAApi
import me.magnum.rcheevosapi.exception.UnsuccessfulRequestException
import me.magnum.rcheevosapi.exception.UserNotAuthenticatedException
import me.magnum.rcheevosapi.model.RAGameId
import java.io.IOException
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

enum class SmartSyncSkipReason {
    MISSING_FROM_CURRENT_SET,
    DEFINITION_CHANGED,
    NOT_IN_PREFETCH_CACHE,
}

data class SmartSyncSkippedAchievement(
    val achievementId: Long,
    val reason: SmartSyncSkipReason,
)

data class SmartSyncResult(
    val submittedCount: Int,
    val skipped: List<SmartSyncSkippedAchievement>,
    val totalCount: Int,
) {
    val skippedCount: Int get() = skipped.size
}

class SmartSyncEngine(
    private val raApi: RAApi,
    private val ledgerRepository: OfflineLedgerRepository,
    private val prefetchCacheRepository: OfflinePrefetchCacheRepository,
) {

    suspend fun syncNow(userId: String, contentId: String): Result<SmartSyncResult> {
        return syncFiltered(userId = userId, contentId = contentId, modeFilter = null)
    }

    suspend fun syncSoftcoreNow(userId: String, contentId: String): Result<SmartSyncResult> {
        return syncFiltered(userId = userId, contentId = contentId, modeFilter = setOf(OfflineUnlockMode.SOFTCORE))
    }

    suspend fun syncHardcoreNow(userId: String, contentId: String): Result<SmartSyncResult> {
        return syncFiltered(userId = userId, contentId = contentId, modeFilter = setOf(OfflineUnlockMode.HARDCORE))
    }

    private suspend fun syncFiltered(
        userId: String,
        contentId: String,
        modeFilter: Set<OfflineUnlockMode>?,
    ): Result<SmartSyncResult> = withContext(Dispatchers.IO) {
        val ledgerStatus = ledgerRepository.getStatus(userId, contentId)
        if (ledgerStatus.integrity != OfflineLedgerIntegrity.OK) {
            return@withContext Result.failure(IllegalStateException("Ledger integrity is ${ledgerStatus.integrity}"))
        }

        val pending = ledgerStatus.pendingUnlocks.filter { unlock ->
            modeFilter?.contains(unlock.unlockMode) ?: true
        }
        if (pending.isEmpty()) {
            return@withContext Result.success(
                SmartSyncResult(
                    submittedCount = 0,
                    skipped = emptyList(),
                    totalCount = 0,
                )
            )
        }

        val cache = prefetchCacheRepository.readValid(userId, contentId)
            ?: return@withContext Result.failure(IllegalStateException("Prefetch cache missing"))

        val cachedDefinitionById = cache.achievements.associate { it.id to it.memoryAddress }

        val currentGame = raApi.getGameAchievementSets(contentId).getOrElse { e ->
            return@withContext Result.failure(e)
        }

        if (currentGame.id.id != cache.gameId) {
            return@withContext Result.failure(IllegalStateException("Game ID mismatch"))
        }

        val currentDefinitionById = currentGame.sets
            .asSequence()
            .flatMap { it.achievements.asSequence() }
            .associate { it.id to it.memoryAddress }

        val startSessionResult = raApi.startSession(
            gameId = RAGameId(cache.gameId),
            gameHash = contentId,
            forHardcoreMode = pending.any { it.unlockMode == OfflineUnlockMode.HARDCORE },
        )
        if (startSessionResult.isFailure) {
            return@withContext Result.failure(startSessionResult.exceptionOrNull() ?: IllegalStateException("Failed to start RA session"))
        }

        val plan = SmartSyncPlanner.plan(
            pendingUnlocks = pending,
            sessions = ledgerStatus.sessions,
        )

        val skipped = mutableListOf<SmartSyncSkippedAchievement>()
        var submitted = 0
        for (item in plan) {
            delay(item.delayBeforeMs)
            val unlock = item.unlock

            val cachedDefinition = cachedDefinitionById[unlock.achievementId]
            val currentDefinition = currentDefinitionById[unlock.achievementId]

            val skipReason = when {
                cachedDefinition == null -> SmartSyncSkipReason.NOT_IN_PREFETCH_CACHE
                currentDefinition == null -> SmartSyncSkipReason.MISSING_FROM_CURRENT_SET
                currentDefinition != cachedDefinition -> SmartSyncSkipReason.DEFINITION_CHANGED
                else -> null
            }

            if (skipReason != null) {
                skipped += SmartSyncSkippedAchievement(achievementId = unlock.achievementId, reason = skipReason)
            } else {
                val awardResult = retryingAward(
                    achievementId = unlock.achievementId,
                    isHardcore = unlock.unlockMode == OfflineUnlockMode.HARDCORE,
                )
                if (awardResult.isFailure) {
                    return@withContext Result.failure(awardResult.exceptionOrNull()!!)
                }
                submitted++
            }

            ledgerRepository.appendAchievementAck(
                userId = userId,
                contentId = contentId,
                gameId = unlock.gameId,
                achievementId = unlock.achievementId,
                isHardcore = unlock.unlockMode == OfflineUnlockMode.HARDCORE,
                ackedSeq = unlock.seq,
                unlockMode = unlock.unlockMode,
                offlineType = unlock.offlineType,
            ).getOrElse { e ->
                return@withContext Result.failure(e)
            }
        }

        Result.success(
            SmartSyncResult(
                submittedCount = submitted,
                skipped = skipped,
                totalCount = pending.size,
            )
        )
    }

    private suspend fun retryingAward(achievementId: Long, isHardcore: Boolean): Result<Unit> {
        var attempt = 0
        var backoffMs = 2000L

        while (true) {
            attempt++
            val result = raApi.awardAchievement(achievementId, isHardcore)
            result.onSuccess {
                // OK (awarded) OR already unlocked (awarded=false) -> consider acked either way.
                return Result.success(Unit)
            }

            val exception = result.exceptionOrNull() ?: Exception("Unknown error")

            if (exception is UnsuccessfulRequestException && exception.message?.contains("User already has", ignoreCase = true) == true) {
                return Result.success(Unit)
            }

            if (exception is UserNotAuthenticatedException) {
                return Result.failure(exception)
            }

            if (exception is IOException || exception.cause is IOException) {
                if (attempt >= 5) {
                    return Result.failure(exception)
                }
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, 60.seconds.inWholeMilliseconds)
                continue
            }

            return Result.failure(exception)
        }
    }
}
