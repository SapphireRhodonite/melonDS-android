package me.magnum.melonds.domain.model

data class RetroArchShaderConfiguration(
    val presetPath: String?,
    val sourceResolution: RetroArchShaderSourceResolution,
    val passCount: Int,
    val parameterOverrides: Map<String, Float>,
    val clearHistory: Boolean,
)
