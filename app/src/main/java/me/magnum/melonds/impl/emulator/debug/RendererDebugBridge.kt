package me.magnum.melonds.impl.emulator.debug

import androidx.annotation.Keep

@Keep
internal object RendererDebugBridge {
    const val CAPTURE_WIDTH = 256
    const val CAPTURE_HEIGHT = 384

    external fun captureCurrentFrame(): IntArray?
    external fun captureCurrentPackedTopPrimary(): IntArray?
    external fun captureCurrentPackedBottomPrimary(): IntArray?
    external fun captureCurrent3dDimensions(): IntArray?
    external fun captureCurrent3dFrame(): IntArray?
    external fun captureCurrent3dCaptureFrame(): IntArray?
    external fun captureCurrent3dDepth(): IntArray?
    external fun captureCurrent3dAttributes(): IntArray?
    external fun captureCurrent3dCoverage(): IntArray?

    external fun dumpCurrentRendererSnapshot()
}

internal data class RendererParityReport(
    val totalPixels: Int,
    val mismatchedPixels: Int,
    val exactMatchPixels: Int,
    val pixelsOutsideTolerance: Int,
    val maxChannelDelta: Int,
    val meanChannelDelta: Double,
    val channelTolerance: Int,
) {
    val exactMatchRatio: Double
        get() = if (totalPixels == 0) 1.0 else exactMatchPixels.toDouble() / totalPixels.toDouble()
}

internal object RendererParityComparator {
    fun compareFrames(
        referencePixels: IntArray,
        candidatePixels: IntArray,
        channelTolerance: Int = 0,
    ): RendererParityReport {
        require(referencePixels.size == candidatePixels.size) {
            "Renderer frame sizes must match: ${referencePixels.size} != ${candidatePixels.size}"
        }
        require(channelTolerance >= 0) {
            "channelTolerance must be >= 0"
        }

        var mismatchedPixels = 0
        var exactMatchPixels = 0
        var pixelsOutsideTolerance = 0
        var maxChannelDelta = 0
        var totalChannelDelta = 0L

        for (pixelIndex in referencePixels.indices) {
            val referencePixel = referencePixels[pixelIndex]
            val candidatePixel = candidatePixels[pixelIndex]
            if (referencePixel == candidatePixel) {
                exactMatchPixels++
                continue
            }

            mismatchedPixels++
            var pixelExceededTolerance = false
            for (shift in 0..24 step 8) {
                val referenceChannel = (referencePixel ushr shift) and 0xFF
                val candidateChannel = (candidatePixel ushr shift) and 0xFF
                val channelDelta = kotlin.math.abs(referenceChannel - candidateChannel)
                totalChannelDelta += channelDelta.toLong()
                if (channelDelta > maxChannelDelta) {
                    maxChannelDelta = channelDelta
                }
                if (channelDelta > channelTolerance) {
                    pixelExceededTolerance = true
                }
            }

            if (pixelExceededTolerance) {
                pixelsOutsideTolerance++
            }
        }

        val comparedChannels = referencePixels.size * 4
        val meanChannelDelta = if (comparedChannels == 0) {
            0.0
        } else {
            totalChannelDelta.toDouble() / comparedChannels.toDouble()
        }

        return RendererParityReport(
            totalPixels = referencePixels.size,
            mismatchedPixels = mismatchedPixels,
            exactMatchPixels = exactMatchPixels,
            pixelsOutsideTolerance = pixelsOutsideTolerance,
            maxChannelDelta = maxChannelDelta,
            meanChannelDelta = meanChannelDelta,
            channelTolerance = channelTolerance,
        )
    }
}
