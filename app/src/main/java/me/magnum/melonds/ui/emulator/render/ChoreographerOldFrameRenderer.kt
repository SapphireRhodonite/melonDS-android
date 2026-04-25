package me.magnum.melonds.ui.emulator.render

import android.view.Choreographer

private const val DEFAULT_FRAME_PERIOD_NS = 16666666L // 16.6ms (60 FPS)

class ChoreographerOldFrameRenderer(private val frameRenderCoordinator: FrameRenderCoordinator) : ChoreographerFrameRenderer, Choreographer.FrameCallback {

    private var isRendering = false
    private var framePeriodNs = DEFAULT_FRAME_PERIOD_NS

    private var framePeriodSampleCount = 0
    private var lastFrameTimeNs = 0L
    private val framePeriodSamples = LongArray(11)

    override fun startRendering() {
        if (isRendering) {
            return
        }

        isRendering = true
        // Reset frame period every time the rendering starts
        framePeriodNs = DEFAULT_FRAME_PERIOD_NS
        lastFrameTimeNs = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun stopRendering() {
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering) {
            return
        }

        if (lastFrameTimeNs > 0L) {
            framePeriodSamples[framePeriodSampleCount++] = frameTimeNanos - lastFrameTimeNs
            if (framePeriodSampleCount == framePeriodSamples.size) {
                // Calculate the median of intervals between frame times to determine the frame period
                framePeriodSamples.sort()
                val median = framePeriodSamples[framePeriodSamples.size / 2]
                framePeriodNs = median
                framePeriodSampleCount = 0
            }
        }
        lastFrameTimeNs = frameTimeNanos

        val frameDeadline = framePeriodNs.takeIf { it > ChoreographerFrameRenderer.DEADLINE_FRAME_TIME_THRESHOLD }?.plus(frameTimeNanos)
        frameRenderCoordinator.renderFrame(frameDeadline)
        if (isRendering) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
