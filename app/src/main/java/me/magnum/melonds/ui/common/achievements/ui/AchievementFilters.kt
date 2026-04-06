package me.magnum.melonds.ui.common.achievements.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.magnum.melonds.R
import me.magnum.melonds.ui.romdetails.model.AchievementBucketUiModel
import me.magnum.rcheevosapi.model.RAAchievement

enum class AchievementTypeFilter(val displayOrder: Int) {
    All(0),
    Core(1),
    Unofficial(2),
    ;

    fun matches(type: RAAchievement.Type): Boolean {
        return when (this) {
            All -> true
            Core -> type == RAAchievement.Type.CORE
            Unofficial -> type == RAAchievement.Type.UNOFFICIAL
        }
    }
}

enum class AchievementStateFilter(
    val displayOrder: Int,
    val bucket: AchievementBucketUiModel.Bucket?,
) {
    All(0, null),
    PendingSubmissions(1, AchievementBucketUiModel.Bucket.PendingSubmissions),
    ActiveChallenges(2, AchievementBucketUiModel.Bucket.ActiveChallenges),
    RecentlyUnlocked(3, AchievementBucketUiModel.Bucket.RecentlyUnlocked),
    Unsynced(4, AchievementBucketUiModel.Bucket.Unsynced),
    AlmostThere(5, AchievementBucketUiModel.Bucket.AlmostThere),
    Locked(6, AchievementBucketUiModel.Bucket.Locked),
    Unsupported(7, AchievementBucketUiModel.Bucket.Unsupported),
    Unofficial(8, AchievementBucketUiModel.Bucket.Unofficial),
    Unlocked(9, AchievementBucketUiModel.Bucket.Unlocked),
    ;

    fun matches(bucketType: AchievementBucketUiModel.Bucket): Boolean {
        return bucket == null || bucket == bucketType
    }

    companion object {
        fun fromBucket(bucket: AchievementBucketUiModel.Bucket): AchievementStateFilter {
            return when (bucket) {
                AchievementBucketUiModel.Bucket.PendingSubmissions -> PendingSubmissions
                AchievementBucketUiModel.Bucket.ActiveChallenges -> ActiveChallenges
                AchievementBucketUiModel.Bucket.RecentlyUnlocked -> RecentlyUnlocked
                AchievementBucketUiModel.Bucket.Unsynced -> Unsynced
                AchievementBucketUiModel.Bucket.AlmostThere -> AlmostThere
                AchievementBucketUiModel.Bucket.Locked -> Locked
                AchievementBucketUiModel.Bucket.Unsupported -> Unsupported
                AchievementBucketUiModel.Bucket.Unofficial -> Unofficial
                AchievementBucketUiModel.Bucket.Unlocked -> Unlocked
            }
        }
    }
}

@Composable
fun AchievementFiltersRow(
    typeFilter: AchievementTypeFilter,
    onTypeFilterChanged: (AchievementTypeFilter) -> Unit,
    stateFilter: AchievementStateFilter,
    availableStateFilters: List<AchievementStateFilter>,
    onStateFilterChanged: (AchievementStateFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterGroup(
            title = stringResource(id = R.string.retro_achievements_filter_type),
            options = AchievementTypeFilter.entries.sortedBy { it.displayOrder },
            selected = typeFilter,
            onSelected = onTypeFilterChanged,
            optionLabel = { getTypeFilterLabel(it) },
            modifier = Modifier.weight(1f),
        )
        FilterGroup(
            title = stringResource(id = R.string.retro_achievements_filter_state),
            options = availableStateFilters.sortedBy { it.displayOrder },
            selected = stateFilter,
            onSelected = onStateFilterChanged,
            optionLabel = { getStateFilterLabel(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun <T> FilterGroup(
    title: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.caption,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(options) { option ->
                val selectedOption = option == selected
                Text(
                    text = optionLabel(option),
                    style = MaterialTheme.typography.caption,
                    color = if (selectedOption) MaterialTheme.colors.onSecondary else MaterialTheme.colors.onSurface,
                    modifier = Modifier
                        .background(
                            color = if (selectedOption) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurface.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(50),
                        )
                        .clickable { onSelected(option) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun getTypeFilterLabel(filter: AchievementTypeFilter): String {
    return when (filter) {
        AchievementTypeFilter.All -> stringResource(id = R.string.retro_achievements_filter_all)
        AchievementTypeFilter.Core -> stringResource(id = R.string.retro_achievements_filter_core)
        AchievementTypeFilter.Unofficial -> stringResource(id = R.string.retro_achievements_filter_unofficial)
    }
}

@Composable
private fun getStateFilterLabel(filter: AchievementStateFilter): String {
    return when (filter) {
        AchievementStateFilter.All -> stringResource(id = R.string.retro_achievements_filter_all)
        AchievementStateFilter.PendingSubmissions -> stringResource(id = R.string.retro_achievements_pending_unlocks)
        AchievementStateFilter.ActiveChallenges -> stringResource(id = R.string.retro_achievements_active_challenges)
        AchievementStateFilter.RecentlyUnlocked -> stringResource(id = R.string.retro_achievements_recently_unlokced)
        AchievementStateFilter.Unsynced -> stringResource(id = R.string.retro_achievements_unsynced)
        AchievementStateFilter.AlmostThere -> stringResource(id = R.string.retro_achievements_almost_there)
        AchievementStateFilter.Locked -> stringResource(id = R.string.retro_achievements_locked)
        AchievementStateFilter.Unsupported -> stringResource(id = R.string.retro_achievements_unsupported)
        AchievementStateFilter.Unofficial -> stringResource(id = R.string.retro_achievements_unofficial)
        AchievementStateFilter.Unlocked -> stringResource(id = R.string.retro_achievements_unlocked)
    }
}
