package me.magnum.melonds.ui.emulator.render

import android.os.Build
import android.view.Choreographer
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ChoreographerVSyncFrameRenderer(
    private val frameRenderCoordinator: FrameRenderCoordinator,
) : ChoreographerFrameRenderer, Choreographer.VsyncCallback {

    private var isRendering = false

    override fun startRendering() {
        if (isRendering) {
            return
        }

        isRendering = true
        Choreographer.getInstance().postVsyncCallback(this)
    }

    override fun stopRendering() {
        isRendering = false
        Choreographer.getInstance().removeVsyncCallback(this)
    }

    override fun onVsync(data: Choreographer.FrameData) {
        if (!isRendering) {
            return
        }

        val frameDelta = data.preferredFrameTimeline.deadlineNanos - data.frameTimeNanos
        val frameDeadline = if (frameDelta > ChoreographerFrameRenderer.DEADLINE_FRAME_TIME_THRESHOLD) {
            data.preferredFrameTimeline.deadlineNanos
        } else {
            null
        }

        frameRenderCoordinator.renderFrame(frameDeadline)
        if (isRendering) {
            Choreographer.getInstance().postVsyncCallback(this)
        }
    }
}
