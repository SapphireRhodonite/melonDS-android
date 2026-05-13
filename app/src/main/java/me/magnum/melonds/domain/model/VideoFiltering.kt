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
    SCANLINES,
    RETROARCH;

    fun isSupportedByVulkan(): Boolean {
        return true
    }

    fun isSupportedByOpenGlSurface(): Boolean {
        return this != RETROARCH
    }
}
