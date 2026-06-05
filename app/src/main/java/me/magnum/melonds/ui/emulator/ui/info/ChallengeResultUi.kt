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
internal fun ChallengeResultUi(
    info: AchievementInfo.ChallengeResult,
) {
    val isSuccess = info.result == AchievementInfo.IndicatorResult.SUCCESS

    LaunchedEffect(info.result, info.achievement.id) {
        delay(3.seconds)
        info.state.dismiss()
    }

    AchievementInfoUi(
        modifier = Modifier.padding(8.dp),
        iconData = if (isSuccess) info.achievement.badgeUrlUnlocked else info.achievement.badgeUrlLocked,
        state = info.state,
        accentColor = if (isSuccess) RaSuccessColor else RaFailureColor,
    ) {
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(
                    if (isSuccess) R.string.challenge_completed else R.string.challenge_failed
                ),
                style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = info.achievement.getCleanTitle(),
                style = MaterialTheme.typography.caption,
                maxLines = 1,
            )
        }
    }
}
