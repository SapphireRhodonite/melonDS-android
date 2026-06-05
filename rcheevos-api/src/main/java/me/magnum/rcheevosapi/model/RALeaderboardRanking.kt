package me.magnum.rcheevosapi.model

import java.net.URL

data class RALeaderboardRanking(
    val leaderboardId: Long,
    val totalEntries: Int,
    val entries: List<RALeaderboardRankingEntry>,
)

data class RALeaderboardRankingEntry(
    val user: String,
    val rank: Int,
    val rawScore: Int,
    val formattedScore: String,
    val submittedAtEpochSeconds: Long,
    val avatarUrl: URL?,
)
