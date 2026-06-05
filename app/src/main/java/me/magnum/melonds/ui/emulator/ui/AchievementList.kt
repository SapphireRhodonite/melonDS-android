package me.magnum.melonds.ui.emulator.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.magnum.melonds.R
import me.magnum.melonds.ui.common.achievements.ui.AchievementFiltersRow
import me.magnum.melonds.ui.common.achievements.ui.AchievementStateFilter
import me.magnum.melonds.ui.common.achievements.ui.AchievementTypeFilter
import me.magnum.melonds.ui.common.melonButtonColors
import me.magnum.melonds.ui.romdetails.model.AchievementBucketUiModel
import me.magnum.melonds.ui.romdetails.model.AchievementSetUiModel
import me.magnum.melonds.ui.romdetails.model.RomRetroAchievementsUiState
import me.magnum.melonds.ui.romdetails.ui.AchievementsMultiSetTabRow
import me.magnum.melonds.ui.romdetails.ui.RomAchievementUi
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RALeaderboard
import me.magnum.rcheevosapi.model.RALeaderboardRanking
import me.magnum.rcheevosapi.model.RALeaderboardRankingEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val DISMISS_DISTANCE_THRESHOLD = 150.dp
private val FLING_DISMISS_VELOCITY_THRESHOLD = 150.dp
private val LIST_CONTENT_PADDING = 40.dp

private val CONTENT_TYPE_ACHIEVEMENT = "achievement"
private val CONTENT_TYPE_BUCKET_HEADER = "bucket-header"
private val CONTENT_TYPE_FILTERS = "filters"
private val CONTENT_TYPE_LEADERBOARD = "leaderboard"
private val CONTENT_TYPE_LEADERBOARD_HEADER = "leaderboard-header"

@Composable
fun AchievementList(
    modifier: Modifier,
    state: RomRetroAchievementsUiState,
    onViewAchievement: (RAAchievement) -> Unit,
    onViewLeaderboard: (RALeaderboard) -> Unit,
    onLoadLeaderboardRanking: suspend (RALeaderboard) -> Result<RALeaderboardRanking>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var offsetY by remember { mutableFloatStateOf(0f) }
    val listAtTop by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0 }
    }

    val (dismissDistanceThresholdPx, flingDismissVelocityThresholdPx) = with(LocalDensity.current) {
        DISMISS_DISTANCE_THRESHOLD.toPx() to FLING_DISMISS_VELOCITY_THRESHOLD.toPx()
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Only handle downward scrolling when at the top of the list
                return if (listAtTop) {
                    if (available.y > 0) {
                        // Scroll down
                        val delta = available.y
                        offsetY += delta

                        Offset(0f, delta)
                    } else if (available.y < 0 && offsetY > 0) {
                        // Scroll up, but only if the list was being dismissed (offsetY > 0)
                        val delta = max(available.y, -offsetY)
                        offsetY += delta

                        Offset(0f, delta)
                    } else {
                        Offset.Zero
                    }
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Handle drag release
                if (offsetY > 0f) {
                    coroutineScope.launch {
                        if (available.y > flingDismissVelocityThresholdPx) {
                            // High downwards flight velocity. Animate and dismiss
                            animate(initialValue = offsetY, targetValue = dismissDistanceThresholdPx, initialVelocity = available.y) { value, _ ->
                                offsetY = value
                            }
                            onDismiss()
                        } else if (offsetY > dismissDistanceThresholdPx) {
                            // User has scrolled being the dismiss threshold
                            onDismiss()
                        } else {
                            // Dismiss criteria not met. Animate back to original position
                            animate(initialValue = offsetY, targetValue = 0f) { value, _ ->
                                offsetY = value
                            }
                        }
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .alpha( (1f - (offsetY / dismissDistanceThresholdPx)).coerceIn(0f, 1f) ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            RomRetroAchievementsUiState.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colors.secondary,
                )
            }
            is RomRetroAchievementsUiState.Ready -> {
                // This box is just a helper that is able to capture focus above the achievement list. Focus to this component is intercepted and the event is used to scroll
                // the list up if the list can still be scrolled. This allows users to fully scroll to the top using keyboard navigation even if there are non-focusable
                // elements at the top of the list
                Box(Modifier.focusable())
                Content(
                    modifier = Modifier.widthIn(max = 640.dp).weight(1f),
                    sets = state.sets,
                    pendingLedgerAchievementIds = state.pendingLedgerAchievementIds,
                    onViewAchievement = onViewAchievement,
                    onViewLeaderboard = onViewLeaderboard,
                    onLoadLeaderboardRanking = onLoadLeaderboardRanking,
                    lazyListState = lazyListState,
                )
            }
            RomRetroAchievementsUiState.AchievementLoadError,
            is RomRetroAchievementsUiState.LoggedOut,
            RomRetroAchievementsUiState.LoginError -> {
                LoadError(
                    modifier = Modifier.widthIn(max = 640.dp).padding(32.dp),
                    onRetry = onRetry,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Content(
    modifier: Modifier,
    sets: List<AchievementSetUiModel>,
    pendingLedgerAchievementIds: Set<Long>,
    onViewAchievement: (RAAchievement) -> Unit,
    onViewLeaderboard: (RALeaderboard) -> Unit,
    onLoadLeaderboardRanking: suspend (RALeaderboard) -> Result<RALeaderboardRanking>,
    lazyListState: LazyListState,
) {
    if (sets.isEmpty()) {
        EmptyAchievements(modifier)
        return
    }

    var selectedLeaderboard by remember {
        mutableStateOf<RALeaderboard?>(null)
    }
    selectedLeaderboard?.let { leaderboard ->
        var reloadToken by remember(leaderboard.id) {
            mutableLongStateOf(0L)
        }
        var rankingState by remember(leaderboard.id) {
            mutableStateOf<LeaderboardRankingUiState>(LeaderboardRankingUiState.Loading)
        }

        LaunchedEffect(leaderboard.id, reloadToken) {
            rankingState = LeaderboardRankingUiState.Loading
            rankingState = onLoadLeaderboardRanking(leaderboard).fold(
                onSuccess = { LeaderboardRankingUiState.Loaded(it) },
                onFailure = { LeaderboardRankingUiState.Error },
            )
        }

        LeaderboardRankingContent(
            modifier = modifier,
            leaderboard = leaderboard,
            state = rankingState,
            onBack = { selectedLeaderboard = null },
            onRetry = { reloadToken++ },
            onViewLeaderboard = onViewLeaderboard,
        )
        return
    }

    var selectedSetId by rememberSaveable {
        mutableLongStateOf(sets.first().setId)
    }
    var selectedTypeFilter by rememberSaveable {
        mutableStateOf(AchievementTypeFilter.All)
    }
    var selectedStateFilter by rememberSaveable {
        mutableStateOf(AchievementStateFilter.All)
    }
    LaunchedEffect(sets) {
        if (sets.none { it.setId == selectedSetId }) {
            selectedSetId = sets.first().setId
        }
    }
    val selectedSet = remember(sets, selectedSetId) {
        sets.firstOrNull { it.setId == selectedSetId } ?: sets.first()
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
    val availableTypeFilters = remember(selectedSet) {
        buildList {
            add(AchievementTypeFilter.All)
            add(AchievementTypeFilter.Core)
            if (selectedSet.leaderboards.isNotEmpty()) {
                add(AchievementTypeFilter.Leaderboards)
            }
            add(AchievementTypeFilter.Unofficial)
        }
    }
    LaunchedEffect(availableTypeFilters) {
        if (selectedTypeFilter !in availableTypeFilters) {
            selectedTypeFilter = AchievementTypeFilter.All
        }
    }
    LaunchedEffect(availableStateFilters) {
        if (selectedStateFilter !in availableStateFilters) {
            selectedStateFilter = AchievementStateFilter.All
        }
    }
    val filteredBuckets = remember(selectedSet, selectedTypeFilter, selectedStateFilter) {
        if (selectedTypeFilter == AchievementTypeFilter.Leaderboards) {
            emptyList()
        } else {
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
    }
    val showLeaderboards = selectedSet.leaderboards.isNotEmpty() &&
            (selectedTypeFilter == AchievementTypeFilter.Leaderboards || (selectedTypeFilter == AchievementTypeFilter.All && selectedSet.buckets.isEmpty()))
    val backgroundColor = MaterialTheme.colors.background
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewSpec = remember(density) {
        val contentPadding = with(density) { LIST_CONTENT_PADDING.toPx() }
        AchievementListBringIntoViewSpec(contentPadding, contentPadding)
    }
    val scrollAmountByKeyboard = remember(density) {
        with(density) { 80.dp.toPx() }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        LazyColumn(
            modifier = modifier
                .focusProperties {
                    onExit = {
                        when (requestedFocusDirection) {
                            FocusDirection.Up -> {
                                if (lazyListState.canScrollBackward) {
                                    cancelFocusChange()
                                    coroutineScope.launch {
                                        lazyListState.animateScrollBy(-scrollAmountByKeyboard)
                                    }
                                } else if (lazyListState.firstVisibleItemIndex == 0) {
                                    // User is already at the top of the list. Prevent navigation out of the list
                                    cancelFocusChange()
                                }
                            }
                            FocusDirection.Down -> {
                                if (lazyListState.canScrollForward) {
                                    cancelFocusChange()
                                    coroutineScope.launch {
                                        lazyListState.animateScrollBy(scrollAmountByKeyboard)
                                    }
                                }
                            }
                        }
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val indexOffset = when (keyEvent.key) {
                            Key.ButtonL2 -> -1
                            Key.ButtonR2 -> 1
                            else -> 0
                        }.let {
                            // Flip offset direction for RTL layouts
                            if (layoutDirection == LayoutDirection.Ltr) it else -it
                        }

                        val selectedSetIndex = sets.indexOfFirst { it.setId == selectedSetId }
                        if (indexOffset != 0 && selectedSetIndex + indexOffset in sets.indices) {
                            selectedSetId = sets[selectedSetIndex + indexOffset].setId
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                .drawWithCache {
                    val fadeHeight = LIST_CONTENT_PADDING.value * this@drawWithCache.density
                    val topBrush = Brush.verticalGradient(
                        listOf(backgroundColor.copy(alpha = 0f), backgroundColor),
                        endY = fadeHeight,
                    )
                    val bottomBrush = Brush.verticalGradient(
                        listOf(backgroundColor, backgroundColor.copy(alpha = 0f)),
                        startY = size.height - fadeHeight,
                        endY = size.height
                    )

                    onDrawWithContent {
                        drawContent()
                        drawRect(
                            brush = topBrush,
                            blendMode = BlendMode.DstIn,
                            size = Size(size.width, fadeHeight),
                        )
                        drawRect(
                            brush = bottomBrush,
                            blendMode = BlendMode.DstIn,
                            topLeft = Offset(0f, size.height - fadeHeight),
                            size = Size(size.width, fadeHeight),
                        )
                    }
                },
            state = lazyListState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = LIST_CONTENT_PADDING),
        ) {
            if (sets.size > 1) {
                item {
                    AchievementsMultiSetTabRow(
                        sets = sets,
                        selectedSetId = selectedSetId,
                        onSetSelected = { selectedSetId = it },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item(contentType = CONTENT_TYPE_FILTERS) {
                AchievementFiltersRow(
                    typeFilter = selectedTypeFilter,
                    availableTypeFilters = availableTypeFilters,
                    onTypeFilterChanged = { selectedTypeFilter = it },
                    stateFilter = selectedStateFilter,
                    availableStateFilters = availableStateFilters,
                    onStateFilterChanged = { selectedStateFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    showStateFilter = selectedTypeFilter != AchievementTypeFilter.Leaderboards,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (filteredBuckets.isEmpty() && !showLeaderboards) {
                item(contentType = CONTENT_TYPE_ACHIEVEMENT) {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        text = stringResource(id = R.string.retro_achievements_filter_no_results),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }

            filteredBuckets.forEachIndexed { index, bucket ->
                item(contentType = CONTENT_TYPE_BUCKET_HEADER) {
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
                    contentType = { CONTENT_TYPE_ACHIEVEMENT },
                ) {
                    RomAchievementUi(
                        modifier = Modifier.fillMaxWidth(),
                        achievementModel = it,
                        isInOfflineLedger = pendingLedgerAchievementIds.contains(it.actualAchievement().id),
                        onViewAchievement = { onViewAchievement(it.actualAchievement()) },
                        badgeSize = 52.dp,
                    )
                }
            }

            if (showLeaderboards) {
                item(contentType = CONTENT_TYPE_LEADERBOARD_HEADER) {
                    Text(
                        modifier = Modifier
                            .padding(start = 16.dp, top = if (filteredBuckets.isEmpty()) 0.dp else 16.dp, end = 16.dp, bottom = 4.dp)
                            .fillMaxWidth(),
                        text = stringResource(R.string.retro_achievements_leaderboards),
                        style = MaterialTheme.typography.h6,
                    )
                    Divider(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        color = MaterialTheme.colors.onSurface,
                    )
                }

                items(
                    items = selectedSet.leaderboards,
                    key = { "leaderboard-${it.id}" },
                    contentType = { CONTENT_TYPE_LEADERBOARD },
                ) {
                    LeaderboardUi(
                        modifier = Modifier.fillMaxWidth(),
                        leaderboard = it,
                        onClick = { selectedLeaderboard = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderboardUi(
    modifier: Modifier,
    leaderboard: RALeaderboard,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Leaderboard,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colors.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = leaderboard.title,
                style = MaterialTheme.typography.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (leaderboard.description.isNotBlank()) {
                Text(
                    text = leaderboard.description,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = leaderboard.format,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.56f),
            )
            Text(
                text = stringResource(R.string.leaderboard_view_ranking),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.secondary,
            )
        }
    }
}

@Composable
private fun LeaderboardRankingContent(
    modifier: Modifier,
    leaderboard: RALeaderboard,
    state: LeaderboardRankingUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onViewLeaderboard: (RALeaderboard) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = LIST_CONTENT_PADDING),
    ) {
        item(contentType = CONTENT_TYPE_LEADERBOARD_HEADER) {
            LeaderboardRankingHeader(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leaderboard = leaderboard,
                state = state,
                onBack = onBack,
                onViewLeaderboard = onViewLeaderboard,
            )
            Divider(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
                color = MaterialTheme.colors.onSurface,
            )
        }

        when (state) {
            LeaderboardRankingUiState.Loading -> {
                item(contentType = CONTENT_TYPE_LEADERBOARD) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colors.secondary)
                    }
                }
            }
            LeaderboardRankingUiState.Error -> {
                item(contentType = CONTENT_TYPE_LEADERBOARD) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.leaderboard_ranking_load_failed),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
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
            is LeaderboardRankingUiState.Loaded -> {
                if (state.ranking.entries.isEmpty()) {
                    item(contentType = CONTENT_TYPE_LEADERBOARD) {
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                            text = stringResource(R.string.leaderboard_ranking_empty),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                } else {
                    items(
                        items = state.ranking.entries,
                        key = { "${leaderboard.id}-${it.rank}-${it.user}" },
                        contentType = { CONTENT_TYPE_LEADERBOARD },
                    ) {
                        LeaderboardRankingEntryUi(
                            modifier = Modifier.fillMaxWidth(),
                            entry = it,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRankingHeader(
    modifier: Modifier,
    leaderboard: RALeaderboard,
    state: LeaderboardRankingUiState,
    onBack: () -> Unit,
    onViewLeaderboard: (RALeaderboard) -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.navigate_back),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = leaderboard.title,
                    style = MaterialTheme.typography.h6,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state is LeaderboardRankingUiState.Loaded) {
                        stringResource(R.string.leaderboard_total_entries, state.ranking.totalEntries)
                    } else {
                        leaderboard.format
                    },
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.64f),
                )
            }
            IconButton(onClick = { onViewLeaderboard(leaderboard) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.leaderboard_open_on_ra),
                    tint = MaterialTheme.colors.secondary,
                )
            }
        }

        if (leaderboard.description.isNotBlank()) {
            Text(
                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 8.dp),
                text = leaderboard.description,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
            )
        }

        TextButton(
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            onClick = { onViewLeaderboard(leaderboard) },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colors.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.leaderboard_open_on_ra),
                color = MaterialTheme.colors.secondary,
            )
        }
    }
}

@Composable
private fun LeaderboardRankingEntryUi(
    modifier: Modifier,
    entry: RALeaderboardRankingEntry,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.width(56.dp),
            text = "#${entry.rank}",
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.secondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.user,
                style = MaterialTheme.typography.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.formattedScore,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

private sealed class LeaderboardRankingUiState {
    object Loading : LeaderboardRankingUiState()
    object Error : LeaderboardRankingUiState()
    data class Loaded(val ranking: RALeaderboardRanking) : LeaderboardRankingUiState()
}

@Composable
private fun EmptyAchievements(modifier: Modifier) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(id = R.string.retro_achievements_no_achievements),
            textAlign = TextAlign.Center,
        )
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
private fun LoadError(
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(32.dp),
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

/**
 * [BringIntoViewSpec] implementation that takes content padding into account. This means that focused items inside of a list are brought into view inside the useful list area
 * instead of leaving them on the edge, within the content padding area. The implementation was adapted from the default implementation of [BringIntoViewSpec].
 */
private class AchievementListBringIntoViewSpec(
    private val leadingPadding: Float,
    private val trailingPadding: Float,
) : BringIntoViewSpec {

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        val leadingEdge = offset
        return when {

            // If the item is already visible, no need to scroll.
            leadingEdge >= leadingPadding && trailingEdge <= containerSize - trailingPadding -> 0f

            // If the item is visible but larger than the parent, we don't scroll.
            leadingEdge < leadingPadding && trailingEdge > containerSize - trailingPadding -> 0f

            // Find the minimum scroll needed to make one of the edges coincide with the
            // parent's
            // edge.
            abs(leadingEdge + leadingPadding) < abs(trailingEdge - (containerSize - trailingPadding)) -> leadingEdge - leadingPadding
            else -> trailingEdge - containerSize + trailingPadding
        }
    }
}
