package me.magnum.melonds.domain.model

import androidx.annotation.Keep

@Keep
enum class VideoFiltering {
    NONE,
    LINEAR,
    SHARP_2D,
    XBR2,
    HQ2X,
    HQ4X,
    QUILEZ,
    LCD,
    LCD_GRID_DSLITE,
    SCANLINES,
    RETROARCH,
    CUSTOM;

    fun isSupportedByVulkan(): Boolean {
        return this != CUSTOM
    }

    fun isSupportedByOpenGlSurface(): Boolean {
        return this != SHARP_2D && this != RETROARCH
    }
}
