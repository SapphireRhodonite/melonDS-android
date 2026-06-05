package me.magnum.melonds.ui.emulator.ui.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.magnum.melonds.R
import me.magnum.melonds.ui.emulator.ui.AchievementInfo
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun LeaderboardAttemptResultUi(
    info: AchievementInfo.LeaderboardAttemptResult,
) {
    val valueLabel = stringResource(leaderboardAttemptValueLabel(info.leaderboard.format))
    val displayValue = info.currentValue.ifBlank { "--" }
    val displayText = stringResource(R.string.leaderboard_attempt_value, valueLabel, displayValue)

    LaunchedEffect(info.leaderboard.id, info.result) {
        delay(3.seconds)
        info.state.dismiss()
    }

    AchievementInfoUi(
        modifier = Modifier.padding(8.dp),
        iconData = info.gameIcon,
        state = info.state,
        accentColor = if (info.result == AchievementInfo.IndicatorResult.SUCCESS) RaSuccessColor else RaFailureColor,
    ) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(
                    if (info.result == AchievementInfo.IndicatorResult.SUCCESS) {
                        R.string.leaderboard_submission_success
                    } else {
                        R.string.leaderboard_attempt_failed
                    }
                ),
                style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = info.leaderboard.title,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
            )
            Text(
                text = displayText,
                style = MaterialTheme.typography.caption,
                maxLines = 1,
            )
        }
    }
}
