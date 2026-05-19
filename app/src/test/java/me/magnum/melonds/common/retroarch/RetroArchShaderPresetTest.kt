package me.magnum.melonds.common.retroarch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroArchShaderPresetTest {
    @Test
    fun detectsRetroTilesStylePresetAsNativeDsSource() {
        val files = mapOf(
            "handheld/retro-tiles.slangp" to """
                shaders = 1
                shader0 = shaders/retro-tiles.slang
                scale_type0 = viewport
            """.trimIndent(),
            "handheld/shaders/retro-tiles.slang" to """
                #version 450
                layout(push_constant) uniform Push {
                    vec4 SourceSize;
                    vec4 OutputSize;
                } params;

                void main() {
                    vec2 tile = fract(params.SourceSize.xy * params.OutputSize.zw);
                }
            """.trimIndent(),
        )

        assertTrue(
            RetroArchShaderPreset.requiresNativeDsSource("handheld/retro-tiles.slangp") { path ->
                files[path]
            },
        )
    }

    @Test
    fun keepsGenericViewportPresetOnVulkanIrSource() {
        val files = mapOf(
            "crt/generic.slangp" to """
                shaders = 1
                shader0 = shaders/generic.slang
                scale_type0 = viewport
            """.trimIndent(),
            "crt/shaders/generic.slang" to """
                #version 450
                layout(location = 0) out vec4 FragColor;

                void main() {
                    FragColor = vec4(1.0);
                }
            """.trimIndent(),
        )

        assertFalse(
            RetroArchShaderPreset.requiresNativeDsSource("crt/generic.slangp") { path ->
                files[path]
            },
        )
    }

    @Test
    fun detectsLaterViewportPassWithOriginalSizeAsNativeDsSource() {
        val files = mapOf(
            "handheld/authentic_gbc.slangp" to """
                shaders = 2
                shader0 = shaders/to_lin.slang
                scale_type0 = source
                shader1 = shaders/authentic_gbc.slang
                scale_type1 = viewport
            """.trimIndent(),
            "handheld/shaders/to_lin.slang" to """
                #version 450
                void main() {}
            """.trimIndent(),
            "handheld/shaders/authentic_gbc.slang" to """
                #version 450
                layout(push_constant) uniform Push {
                    vec4 OriginalSize;
                    vec4 OutputSize;
                    float AUTH_GBC_SUBPX;
                } params;

                void main() {
                    vec2 tx_to_px = params.OutputSize.xy / params.OriginalSize.xy;
                    vec2 subpx = tx_to_px * params.AUTH_GBC_SUBPX;
                }
            """.trimIndent(),
        )

        assertTrue(
            RetroArchShaderPreset.requiresNativeDsSource("handheld/authentic_gbc.slangp") { path ->
                files[path]
            },
        )
    }

    @Test
    fun detectsLcdScanlinePresetUsingOriginalSizeAsNativeDsSource() {
        val files = mapOf(
            "handheld/lcd3x.slangp" to """
                shaders = 1
                shader0 = shaders/lcd3x.slang
                scale_type0 = viewport
            """.trimIndent(),
            "handheld/shaders/lcd3x.slang" to """
                #version 450
                layout(push_constant) uniform Push {
                    vec4 OriginalSize;
                    vec4 OutputSize;
                } params;

                void main() {
                    vec2 angle = params.OriginalSize.xy * 6.28318;
                    float scanlines = sin(angle.y);
                }
            """.trimIndent(),
        )

        assertTrue(
            RetroArchShaderPreset.requiresNativeDsSource("handheld/lcd3x.slangp") { path ->
                files[path]
            },
        )
    }

    @Test
    fun detectsSourceMotionBlurPlusViewportGridAsNativeDsSource() {
        val files = mapOf(
            "presets/lcd-grid-v2-dslite-color-motionblur.slangp" to """
                shaders = 3
                shader0 = ../motionblur/response-time.slang
                scale_type0 = source
                shader1 = ../handheld/lcd-grid-v2.slang
                scale_type1 = viewport
                shader2 = ../handheld/color.slang
                scale_type2 = source
            """.trimIndent(),
            "motionblur/response-time.slang" to """
                #version 450
                layout(set = 0, binding = 3) uniform sampler2D OriginalHistory1;
            """.trimIndent(),
            "handheld/lcd-grid-v2.slang" to """
                #version 450
                layout(push_constant) uniform Push {
                    vec4 SourceSize;
                    vec4 OutputSize;
                } params;

                void main() {
                    vec2 grid = fract(params.SourceSize.xy * params.OutputSize.zw);
                }
            """.trimIndent(),
            "handheld/color.slang" to """
                #version 450
                void main() {}
            """.trimIndent(),
        )

        assertTrue(
            RetroArchShaderPreset.requiresNativeDsSource("presets/lcd-grid-v2-dslite-color-motionblur.slangp") { path ->
                files[path]
            },
        )
    }

    @Test
    fun detectsSharpShimmerlessScalerAsNativeDsSourceWithoutViewportDeclaration() {
        val files = mapOf(
            "pixel-art-scaling/sharp-shimmerless.slangp" to """
                shaders = 1
                shader0 = shaders/sharp-shimmerless.slang
            """.trimIndent(),
            "pixel-art-scaling/shaders/sharp-shimmerless.slang" to """
                #version 450
                layout(push_constant) uniform Push {
                    vec4 SourceSize;
                    vec4 OriginalSize;
                    vec4 OutputSize;
                } params;

                void main() {
                    vec2 pixel_borders = floor(params.OutputSize.xy * params.SourceSize.zw);
                }
            """.trimIndent(),
        )

        assertTrue(
            RetroArchShaderPreset.requiresNativeDsSource("pixel-art-scaling/sharp-shimmerless.slangp") { path ->
                files[path]
            },
        )
    }
}
