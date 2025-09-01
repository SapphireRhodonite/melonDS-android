package me.magnum.melonds.common.opengl

import me.magnum.melonds.domain.model.VideoFiltering

object VideoFilterShaderProvider {
    private val FILTERING_SHADER_MAP = mapOf(
        VideoFiltering.NONE to ShaderProgramSource.NoFilterShader,
        VideoFiltering.LINEAR to ShaderProgramSource.LinearShader,
        VideoFiltering.XBR2 to ShaderProgramSource.XbrShader,
        VideoFiltering.HQ2X to ShaderProgramSource.Hq2xShader,
        VideoFiltering.HQ4X to ShaderProgramSource.Hq4xShader,
        VideoFiltering.QUILEZ to ShaderProgramSource.QuilezShader,
        VideoFiltering.LCD to ShaderProgramSource.LcdShader,
        VideoFiltering.SCANLINES to ShaderProgramSource.ScanlinesShader,
    )

    private val ROTATION_FILTERING_SHADER_MAP = mapOf(
        VideoFiltering.NONE to ScreenRotationShaderProgramSource.NoFilterShader,
        VideoFiltering.LINEAR to ScreenRotationShaderProgramSource.LinearShader,
        VideoFiltering.XBR2 to ScreenRotationShaderProgramSource.XbrShader,
        VideoFiltering.HQ2X to ScreenRotationShaderProgramSource.Hq2xShader,
        VideoFiltering.HQ4X to ScreenRotationShaderProgramSource.Hq4xShader,
        VideoFiltering.QUILEZ to ScreenRotationShaderProgramSource.QuilezShader,
        VideoFiltering.LCD to ScreenRotationShaderProgramSource.LcdShader,
        VideoFiltering.SCANLINES to ScreenRotationShaderProgramSource.ScanlinesShader,
    )

    fun getShaderSource(filtering: VideoFiltering): ShaderProgramSource =
        FILTERING_SHADER_MAP[filtering] ?: ShaderProgramSource.NoFilterShader

    fun getRotationShaderSource(filtering: VideoFiltering): ScreenRotationShaderProgramSource =
        ROTATION_FILTERING_SHADER_MAP[filtering] ?: ScreenRotationShaderProgramSource.NoFilterShader

}