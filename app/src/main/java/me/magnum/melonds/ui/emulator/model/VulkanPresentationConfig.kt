package me.magnum.melonds.ui.emulator.model

import androidx.annotation.Keep
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.layout.BackgroundMode

@Keep
data class VulkanPresentationConfig(
    val topScreenRect: Rect?,
    val bottomScreenRect: Rect?,
    val topAlpha: Float,
    val bottomAlpha: Float,
    val topOnTop: Boolean,
    val bottomOnTop: Boolean,
    val backgroundMode: BackgroundMode,
    val videoFiltering: VideoFiltering,
)
