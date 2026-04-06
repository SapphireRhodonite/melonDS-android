package me.magnum.melonds.ui.romdetails.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievement
import me.magnum.melonds.ui.common.MelonPreviewSet
import me.magnum.melonds.ui.common.achievements.ui.AchievementFiltersRow
import me.magnum.melonds.ui.common.achievements.ui.AchievementStateFilter
import me.magnum.melonds.ui.common.achievements.ui.AchievementTypeFilter
import me.magnum.melonds.ui.common.achievements.ui.model.AchievementUiModel
import me.magnum.melonds.ui.common.melonButtonColors
import me.magnum.melonds.ui.romdetails.model.AchievementBucketUiModel
import me.magnum.melonds.ui.romdetails.model.AchievementSetUiModel
import me.magnum.melonds.ui.romdetails.model.OfflineAchievementsUiState
import me.magnum.melonds.ui.romdetails.model.RomAchievementsSummary
import me.magnum.melonds.ui.romdetails.model.RomRetroAchievementsUiState
import me.magnum.melonds.ui.romdetails.ui.preview.mockRAAchievementPreview
import me.magnum.melonds.ui.theme.MelonTheme
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RAAchievementSet
import java.net.URL

private const val SETS_TABS_ITEM_TYPE = "sets"
private const val HEADER_ITEM_TYPE = "header"
private const val FILTERS_ITEM_TYPE = "filters"
private const val BUCKET_HEADER_ITEM_TYPE = "bucket_header"
private const val ACHIEVEMENT_ITEM_TYPE = "achievement"

@Composable
fun RomRetroAchievementsUi(
    modifier: Modifier,
    contentPadding: PaddingValues,
    retroAchievementsUiState: RomRetroAchievementsUiState,
    offlineAchievementsUiState: OfflineAchievementsUiState,
    onLogin: (username: String, password: String) -> Unit,
    onRetryLoad: () -> Unit,
    onViewAchievement: (RAAchievement) -> Unit,
    onSyncOfflineNow: () -> Unit,
) {
    Column(modifier = modifier.padding(contentPadding)) {
        OfflineAchievementsStatusUi(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            state = offlineAchievementsUiState,
            onSyncNow = onSyncOfflineNow,
        )

        Divider(Modifier.fillMaxWidth())

        when (retroAchievementsUiState) {
            is RomRetroAchievementsUiState.LoggedOut -> LoggedOut(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onLogin = onLogin,
            )
            is RomRetroAchievementsUiState.Loading -> Loading(Modifier.weight(1f).fillMaxWidth())
            is RomRetroAchievementsUiState.Ready -> {
                if (!retroAchievementsUiState.hasAchievements()) {
                    NoAchievements(Modifier.weight(1f).fillMaxWidth())
                } else {
                    Ready(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                        content = retroAchievementsUiState,
                        onViewAchievement = onViewAchievement,
                    )
                }
            }
            is RomRetroAchievementsUiState.LoginError -> LoginError(modifier = Modifier.weight(1f).fillMaxWidth(), onLogin = onLogin)
            is RomRetroAchievementsUiState.AchievementLoadError -> LoadError(modifier = Modifier.weight(1f).fillMaxWidth(), onRetry = onRetryLoad)
        }
    }
}

@Composable
private fun OfflineAchievementsStatusUi(
    modifier: Modifier,
    state: OfflineAchievementsUiState,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(id = R.string.offline_ra_settings_title),
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
        )

        val availabilityText = when (state.availability) {
            OfflineAchievementsUiState.Availability.ENABLED -> stringResource(id = R.string.offline_ra_status_enabled)
            OfflineAchievementsUiState.Availability.DISABLED_NOT_LOGGED_IN -> stringResource(id = R.string.offline_ra_status_disabled_not_logged_in)
            OfflineAchievementsUiState.Availability.DISABLED_NO_CACHE -> stringResource(id = R.string.offline_ra_status_disabled_no_cache)
        }
        Text(text = availabilityText, style = MaterialTheme.typography.body2)

        Text(
            text = stringResource(id = R.string.offline_ra_pending_softcore_unlocks, state.pendingSoftcoreUnlockCount),
            style = MaterialTheme.typography.body2,
        )
        Text(
            text = stringResource(id = R.string.offline_ra_pending_ledger_unlocks, state.pendingLedgerUnlockCount),
            style = MaterialTheme.typography.body2,
        )

        val integrityText = if (state.isLedgerIntegrityOk) {
            stringResource(id = R.string.offline_ra_ledger_integrity_ok)
        } else {
            stringResource(id = R.string.offline_ra_ledger_integrity_tampered)
        }
        Text(
            text = stringResource(id = R.string.offline_ra_ledger_integrity, integrityText),
            style = MaterialTheme.typography.body2,
        )

        if (state.isSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colors.secondary)
        }

        Button(
            onClick = onSyncNow,
            enabled = state.canSyncNow,
            colors = melonButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.offline_ra_sync_now_button).uppercase())
        }
    }
}

@Composable
private fun LoggedOut(
    modifier: Modifier,
    onLogin: (username: String, password: String) -> Unit,
) {
    var showLoginPopup by rememberSaveable {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(id = R.string.retro_achievements_login_description),
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = { showLoginPopup = true },
                colors = melonButtonColors(),
            ) {
                Text(
                    text = stringResource(id = R.string.login_with_retro_achievements).uppercase(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showLoginPopup) {
        RetroAchievementsLoginDialog(
            onDismiss = { showLoginPopup = false },
            onLogin = { username, password ->
                onLogin(username, password)
                showLoginPopup = false
            },
        )
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colors.secondary)
    }
}

@Composable
private fun NoAchievements(modifier: Modifier) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.focusable(),
            text = stringResource(id = R.string.retro_achievements_no_achievements),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Ready(
    modifier: Modifier,
    contentPadding: PaddingValues,
    content: RomRetroAchievementsUiState.Ready,
    onViewAchievement: (RAAchievement) -> Unit,
) {
    var selectedSetId by rememberSaveable {
        mutableLongStateOf(content.sets.first().setId)
    }
    var selectedTypeFilter by rememberSaveable {
        mutableStateOf(AchievementTypeFilter.All)
    }
    var selectedStateFilter by rememberSaveable {
        mutableStateOf(AchievementStateFilter.All)
    }
    val selectedSet = remember(selectedSetId) {
        content.sets.first { it.setId == selectedSetId }
    }
    val availableStateFilters = remember(selectedSet) {
        buildList {
            add(AchievementStateFilter.All)
            addAll(
                selectedSet.buckets
                    .map { AchievementStateFilter.fromBucket(it.bucket) }
                    .distinct()
                    .sortedBy { it.displayOrder },
            )
        }
    }
    LaunchedEffect(availableStateFilters) {
        if (selectedStateFilter !in availableStateFilters) {
            selectedStateFilter = AchievementStateFilter.All
        }
    }
    val filteredBuckets = remember(selectedSet, selectedTypeFilter, selectedStateFilter) {
        selectedSet.buckets
            .asSequence()
            .filter { selectedStateFilter.matches(it.bucket) }
            .map { bucket ->
                bucket.copy(
                    achievements = bucket.achievements.filter {
                        selectedTypeFilter.matches(it.actualAchievement().type)
                    },
                )
            }
            .filter { it.achievements.isNotEmpty() }
            .toList()
    }

    val layoutDirection = LocalLayoutDirection.current
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier.onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val indexOffset = when (keyEvent.key) {
                    Key.ButtonL2 -> -1
                    Key.ButtonR2 -> 1
                    else -> 0
                }.let {
                    // Flip offset direction for RTL layouts
                    if (layoutDirection == LayoutDirection.Ltr) it else -it
                }

                val selectedSetIndex = content.sets.indexOfFirst { it.setId == selectedSetId }
                if (indexOffset != 0 && selectedSetIndex + indexOffset in content.sets.indices) {
                    selectedSetId = content.sets[selectedSetIndex + indexOffset].setId
                    true
                } else {
                    false
                }
            } else {
                false
            }
        },
        state = listState,
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (content.sets.size > 1) {
            item(contentType = SETS_TABS_ITEM_TYPE) {
                AchievementsMultiSetTabRow(
                    sets = content.sets,
                    selectedSetId = selectedSetId,
                    onSetSelected = { selectedSetId = it },
                )
            }
        }

        item(contentType = HEADER_ITEM_TYPE) {
            Header(
                modifier = Modifier.fillMaxWidth().focusable(),
                achievementsSummary = selectedSet.setSummary,
            )
            Divider(Modifier.fillMaxWidth())
        }

        item(contentType = FILTERS_ITEM_TYPE) {
            AchievementFiltersRow(
                typeFilter = selectedTypeFilter,
                onTypeFilterChanged = { selectedTypeFilter = it },
                stateFilter = selectedStateFilter,
                availableStateFilters = availableStateFilters,
                onStateFilterChanged = { selectedStateFilter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (filteredBuckets.isEmpty()) {
            item(contentType = ACHIEVEMENT_ITEM_TYPE) {
                Text(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    text = stringResource(id = R.string.retro_achievements_filter_no_results),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                )
            }
        }

        filteredBuckets.forEachIndexed { index, bucket ->
            item(contentType = BUCKET_HEADER_ITEM_TYPE) {
                Text(
                    modifier = Modifier.padding(start = 16.dp, top = if (index == 0) 0.dp else 16.dp, end = 16.dp, bottom = 4.dp).fillMaxWidth(),
                    text = getBucketTitle(bucket.bucket),
                    style = MaterialTheme.typography.h6,
                )
                Divider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colors.onSurface,
                )
            }

            items(
                items = bucket.achievements,
                contentType = { ACHIEVEMENT_ITEM_TYPE },
            ) { userAchievement ->
                RomAchievementUi(
                    modifier = Modifier.fillMaxWidth(),
                    achievementModel = userAchievement,
                    isInOfflineLedger = content.pendingLedgerAchievementIds.contains(userAchievement.actualAchievement().id),
                    onViewAchievement = { onViewAchievement(userAchievement.actualAchievement()) },
                )
            }
        }
    }
}

@Composable
private fun getBucketTitle(bucket: AchievementBucketUiModel.Bucket): String {
    return when (bucket) {
        AchievementBucketUiModel.Bucket.PendingSubmissions -> stringResource(R.string.retro_achievements_pending_unlocks)
        AchievementBucketUiModel.Bucket.ActiveChallenges -> stringResource(R.string.retro_achievements_active_challenges)
        AchievementBucketUiModel.Bucket.RecentlyUnlocked -> stringResource(R.string.retro_achievements_recently_unlokced)
        AchievementBucketUiModel.Bucket.Unsynced -> stringResource(R.string.retro_achievements_unsynced)
        AchievementBucketUiModel.Bucket.AlmostThere -> stringResource(R.string.retro_achievements_almost_there)
        AchievementBucketUiModel.Bucket.Locked -> stringResource(R.string.retro_achievements_locked)
        AchievementBucketUiModel.Bucket.Unsupported -> stringResource(R.string.retro_achievements_unsupported)
        AchievementBucketUiModel.Bucket.Unofficial -> stringResource(R.string.retro_achievements_unofficial)
        AchievementBucketUiModel.Bucket.Unlocked -> stringResource(R.string.retro_achievements_unlocked)
    }
}

@Composable
private fun Header(
    modifier: Modifier,
    achievementsSummary: RomAchievementsSummary,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                appendInlineContent("icon-points")
                append(' ')
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(achievementsSummary.totalPoints.toString())
                }
                append(' ')
                append(stringResource(id = R.string.points_abbreviated))
                append(" (")
                if (achievementsSummary.forHardcoreMode) {
                    append(stringResource(id = R.string.ra_mode_hardcore))
                } else {
                    append(stringResource(id = R.string.ra_mode_softcore))
                }
                append(')')
            },
            inlineContent = mapOf(
                "icon-points" to InlineTextContent(Placeholder(MaterialTheme.typography.body1.fontSize, MaterialTheme.typography.body1.fontSize, PlaceholderVerticalAlign.Center)) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        painter = painterResource(id = R.drawable.ic_points),
                        contentDescription = null,
                    )
                }
            )
        )

        Text(
            text = buildAnnotatedString {
                appendInlineContent(id = "icon-completed", alternateText = stringResource(id = R.string.completed))
                append(' ')
                append(stringResource(id = R.string.completed_achievements, achievementsSummary.completedAchievements, achievementsSummary.totalAchievements, achievementsSummary.completedPercentage))
            },
            inlineContent = mapOf(
                "icon-completed" to InlineTextContent(Placeholder(MaterialTheme.typography.body1.fontSize, MaterialTheme.typography.body1.fontSize, PlaceholderVerticalAlign.Center)) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        painter = painterResource(id = R.drawable.ic_completed),
                        contentDescription = null,
                    )
                }
            )
        )

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            progress = achievementsSummary.completedAchievements / achievementsSummary.totalAchievements.toFloat(),
            color = MaterialTheme.colors.secondary,
        )
    }
}

@Composable
private fun LoginError(
    modifier: Modifier,
    onLogin: (username: String, password: String) -> Unit,
) {
    var showLoginPopup by remember {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(id = R.string.retro_achievements_login_error),
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = { showLoginPopup = true },
                colors = melonButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.login_with_retro_achievements).uppercase())
            }
        }
    }

    if (showLoginPopup) {
        RetroAchievementsLoginDialog(
            onDismiss = { showLoginPopup = false },
            onLogin = { username, password ->
                onLogin(username, password)
                showLoginPopup = false
            },
        )
    }
}

@Composable
private fun LoadError(
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(id = R.string.retro_achievements_load_error),
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onRetry,
                colors = melonButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.retry).uppercase())
            }
        }
    }
}

@MelonPreviewSet
@Composable
private fun PreviewContent() {
    MelonTheme {
        Ready(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            content = RomRetroAchievementsUiState.Ready(
                listOf(
                    AchievementSetUiModel(
                        setId = 1,
                        setTitle = null,
                        setType = RAAchievementSet.Type.Core,
                        setIcon = URL("http://example.com/icon.png"),
                        setSummary = RomAchievementsSummary(true, 50, 20, 85),
                        buckets = listOf(
                            AchievementBucketUiModel(
                                bucket = AchievementBucketUiModel.Bucket.Locked,
                                achievements = listOf(
                                    AchievementUiModel.UserAchievementUiModel(RAUserAchievement(mockRAAchievementPreview(id = 1), false, false)),
                                    AchievementUiModel.UserAchievementUiModel(RAUserAchievement(mockRAAchievementPreview(id = 2, title = "This is another amazing achievement", description = "But this one cannot be missed."), false, false)),
                                ),
                            )
                        ),
                    ),
                    AchievementSetUiModel(
                        setId = 2,
                        setTitle = "Special Challenge",
                        setType = RAAchievementSet.Type.Bonus,
                        setIcon = URL("http://example.com/icon.png"),
                        setSummary = RomAchievementsSummary(true, 20, 4, 12),
                        buckets = listOf(
                            AchievementBucketUiModel(
                                bucket = AchievementBucketUiModel.Bucket.Locked,
                                achievements = listOf(
                                    AchievementUiModel.UserAchievementUiModel(RAUserAchievement(mockRAAchievementPreview(id = 1), false, false)),
                                    AchievementUiModel.UserAchievementUiModel(RAUserAchievement(mockRAAchievementPreview(id = 2, title = "This is a subset achievement", description = "This is part of the special subset"), false, false)),
                                ),
                            )
                        ),
                    ),
                ),
            ),
            onViewAchievement = {},
        )
    }
}

@MelonPreviewSet
@Composable
private fun PreviewLoggedOut() {
    MelonTheme {
        LoggedOut(
            modifier = Modifier.fillMaxSize(),
            onLogin = { _, _ -> },
        )
    }
}

@MelonPreviewSet
@Composable
private fun PreviewLoginError() {
    MelonTheme {
        LoginError(
            modifier = Modifier.fillMaxSize(),
            onLogin = { _, _ -> },
        )
    }
}

@MelonPreviewSet
@Composable
private fun PreviewLoadError() {
    MelonTheme {
        LoadError(
            modifier = Modifier.fillMaxSize(),
            onRetry = { },
        )
    }
}
