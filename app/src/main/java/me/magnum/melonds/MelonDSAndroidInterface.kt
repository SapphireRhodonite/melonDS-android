package me.magnum.melonds

import me.magnum.melonds.common.UriFileHandler

object MelonDSAndroidInterface {
    const val RENDERER_CAP_OPENGL = 1 shl 0
    const val RENDERER_CAP_VULKAN = 1 shl 1

    external fun setup(uriFileHandler: UriFileHandler)
    external fun getEmulatorGlContext(): Long
    external fun getRendererCapabilities(): Int
    external fun canInitializeVulkanRendererNative(): Boolean
    external fun setVulkanCompatibilityOverridesNative(
        disableTimelineSemaphores: Boolean,
        disableDynamicTextureIndexing: Boolean,
    )
    external fun cleanup()

    fun isVulkanRendererSupported(): Boolean {
        return runCatching {
            getRendererCapabilities() and RENDERER_CAP_VULKAN != 0
        }.getOrDefault(false)
    }

    fun canInitializeVulkanRenderer(): Boolean {
        return runCatching {
            canInitializeVulkanRendererNative()
        }.getOrDefault(false)
    }

    fun setVulkanCompatibilityOverrides(
        disableTimelineSemaphores: Boolean,
        disableDynamicTextureIndexing: Boolean,
    ) {
        runCatching {
            setVulkanCompatibilityOverridesNative(
                disableTimelineSemaphores,
                disableDynamicTextureIndexing,
            )
        }
    }
}
