package me.magnum.melonds.domain.model

import androidx.annotation.Keep

@Keep
enum class VideoFiltering {
    NONE,
    LINEAR,
    XBR2,
    HQ2X,
    HQ4X,
    QUILEZ,
    LCD,
    LCD_GRID_DSLITE,
    SCANLINES,
    CUSTOM;

    fun isSupportedByVulkan(): Boolean {
        return this == NONE || this == LINEAR
    }
}
