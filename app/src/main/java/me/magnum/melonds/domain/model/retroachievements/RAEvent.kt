package me.magnum.melonds.domain.model.retroachievements

sealed class RAEvent {
    enum class RuntimeFallbackReason {
        LOGIN_TIMEOUT,
        LOGIN_FAILED,
        LOAD_TIMEOUT,
        LOAD_FAILED,
        PERFORMANCE,
        UNKNOWN,
    }

    data class OnAchievementPrimed(val achievementId: Long) : RAEvent()
    data class OnAchievementUnPrimed(val achievementId: Long) : RAEvent()
    data class OnAchievementTriggered(val achievementId: Long) : RAEvent()
    data class OnAchievementProgressUpdated(val achievementId: Long, val current: Int, val target: Int, val progress: String) : RAEvent()
    data class OnGameCompleted(val subsetId: Long) : RAEvent()
    data class OnSubsetCompleted(val subsetId: Long) : RAEvent()
    data class OnServerError(val api: String, val relatedId: Long, val resultCode: Int, val message: String) : RAEvent()
    data object OnDisconnected : RAEvent()
    data object OnReconnected : RAEvent()
    data class OnRuntimeFallback(val reason: RuntimeFallbackReason) : RAEvent()
    data class OnLeaderboardAttemptStarted(val leaderboardId: Long) : RAEvent()
    data class OnLeaderboardAttemptUpdated(val leaderboardId: Long, val formattedValue: String) : RAEvent()
    data class OnLeaderboardAttemptCompleted(val leaderboardId: Long, val value: Int) : RAEvent()
    data class OnLeaderboardAttemptCancelled(val leaderboardId: Long) : RAEvent()
}
