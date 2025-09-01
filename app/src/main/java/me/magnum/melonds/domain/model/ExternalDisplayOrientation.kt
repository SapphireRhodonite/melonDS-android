package me.magnum.melonds.domain.model

enum class ExternalDisplayOrientation(val orientation: Float) {
    FOLLOW_SYSTEM(0f),
    PORTRAIT(90f),
    PORTRAIT_UPSIDE_DOWN(-90f)
}