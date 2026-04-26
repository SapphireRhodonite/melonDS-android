package me.magnum.melonds.ui.emulator.model

import me.magnum.melonds.common.opengl.ShaderProgramSource
import me.magnum.melonds.domain.model.RetroArchShaderConfiguration
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.VideoRenderer

data class RuntimeRendererConfiguration(
    val renderer: VideoRenderer,
    val videoFiltering: VideoFiltering,
    val resolutionScaling: Int,
    val customShader: ShaderProgramSource?,
    val retroArchShader: RetroArchShaderConfiguration,
)
