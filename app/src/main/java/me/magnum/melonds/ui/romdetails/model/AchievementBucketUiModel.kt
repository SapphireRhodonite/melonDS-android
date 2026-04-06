package me.magnum.melonds.ui.romdetails.model

import me.magnum.melonds.ui.common.achievements.ui.model.AchievementUiModel

data class AchievementBucketUiModel(
    val bucket: Bucket,
    val achievements: List<AchievementUiModel>,
) {

    /**
     * The different buckets into which displayed achievements can be inserted. The enum entries are defined in their preferred display order.
     */
    enum class Bucket(val displayOrder: Int) {
        PendingSubmissions(0),
        ActiveChallenges(1),
        RecentlyUnlocked(2),
        Unsynced(3),
        AlmostThere(4),
        Locked(5),
        Unsupported(6),
        Unofficial(7),
        Unlocked(8),
    }
}
