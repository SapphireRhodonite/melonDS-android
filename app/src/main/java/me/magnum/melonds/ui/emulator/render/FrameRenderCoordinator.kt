package me.magnum.melonds.ui.emulator.render

import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.ui.emulator.EmulatorSurfaceView
import me.magnum.melonds.ui.emulator.model.VulkanPresentationConfig

interface FrameRenderCoordinator {
    fun addSurface(surface: EmulatorSurfaceView)
    fun removeSurface(surface: EmulatorSurfaceView)
    fun updateSurfacePresentation(surface: EmulatorSurfaceView, config: VulkanPresentationConfig?, background: RuntimeBackground)
    fun renderFrame(frameDeadlineNanos: Long?)
    fun stop()
}
