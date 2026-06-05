package me.magnum.melonds.ui.emulator.ui.info

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.magnum.melonds.R
import me.magnum.melonds.ui.emulator.ui.AchievementInfo
import me.magnum.melonds.ui.theme.MelonTheme
import me.magnum.rcheevosapi.model.RAGameId
import me.magnum.rcheevosapi.model.RALeaderboard
import me.magnum.rcheevosapi.model.RASetId
import java.net.URL
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun LeaderboardAttemptUi(
    info: AchievementInfo.LeaderboardAttempt,
) {
    val alphaTransition = remember {
        Animatable(1f)
    }
    val valueLabel = stringResource(leaderboardAttemptValueLabel(info.leaderboard.format))
    val displayValue = info.currentValue.ifBlank { "--" }
    val displayText = stringResource(R.string.leaderboard_attempt_value, valueLabel, displayValue)

    LaunchedEffect(Unit) {
        delay(3.seconds)
        alphaTransition.animateTo(0.5f)
    }

    AchievementInfoUi(
        modifier = Modifier
            .padding(8.dp)
            .graphicsLayer { alpha = alphaTransition.value },
        iconData = info.gameIcon,
        state = info.state,
    ) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = info.leaderboard.title,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
            )
        }
    }
}

internal fun leaderboardAttemptValueLabel(format: String): Int {
    return when (format.uppercase(Locale.ROOT)) {
        "SCORE", "POINTS", "OTHER" -> R.string.leaderboard_attempt_score_label
        "TIME", "TIMESECS", "SECS", "SECS_AS_MINS", "MINUTES", "FRAMES", "MILLISECS" ->
            R.string.leaderboard_attempt_time_label
        else -> R.string.leaderboard_attempt_value_label
    }
}

@Preview
@Composable
private fun PreviewLeaderboardAttemptUi() {
    MelonTheme {
        LeaderboardAttemptUi(
            info = AchievementInfo.LeaderboardAttempt(
                leaderboard = RALeaderboard(
                    id = 0,
                    gameId = RAGameId(0),
                    setId = RASetId(0),
                    mem = "",
                    format = "",
                    lowerIsBetter = false,
                    title = "Fastest Boy in the West",
                    description = "",
                    hidden = false
                ),
                gameIcon = URL("https://example.com/icon.png"),
                currentValue = "0:25.74",
                state = AchievementInfoState { },
            )
        )
    }
}
