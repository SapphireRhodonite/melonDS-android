package me.magnum.melonds.ui

import me.magnum.melonds.common.opengl.Shader
import me.magnum.melonds.common.opengl.ShaderFactory
import me.magnum.melonds.common.opengl.VideoFilterShaderProvider
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.ui.emulator.FrameRenderEventConsumer

abstract class ExternalRenderer : FrameRenderEventConsumer {
    abstract fun updateVideoFiltering(videoFiltering: VideoFiltering)
    abstract fun updateExternalDisplayOrientation(orientation: Float)

    protected fun grabCorrectShader(displayRotation: Float, videoFiltering: VideoFiltering): Shader {
        val thatShader = if(displayRotation == 0f) {
            VideoFilterShaderProvider.getShaderSource(videoFiltering)
        } else {
            VideoFilterShaderProvider.getRotationShaderSource(videoFiltering)
        }
        return ShaderFactory.createShaderProgram(thatShader)
    }

}