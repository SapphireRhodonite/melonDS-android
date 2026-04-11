# Vulkan SPIR-V Regeneration

This project keeps Vulkan shaders precompiled in-repo as C headers:

- `melonDS-android-lib/src/GPU3D_Vulkan_InterpSpansShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_BinCombinedShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_CalculateWorkOffsetsShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_SortWorkShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_TriRasterShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_TriRasterCompatShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_DepthBlendShaderData.h`
- `melonDS-android-lib/src/GPU3D_Vulkan_FinalPassShaderData.h`
- `melonDS-android-lib/src/android/renderer/VulkanCompositorShaderData.h`
- `melonDS-android-lib/src/android/renderer/VulkanSurfacePresenterVertexShaderData.h`
- `melonDS-android-lib/src/android/renderer/VulkanSurfacePresenterFragmentShaderData.h`

## Automatic Flow (recommended)

Vulkan shader headers are regenerated automatically during native/app builds:

- CMake target: `regenerate_vulkan_spirv_headers`
- Gradle task: `:app:regenerateVulkanSpirv`
- `preBuild` depends on `:app:regenerateVulkanSpirv`

This means a normal app build updates out-of-date `*ShaderData.h` automatically.

## Manual Commands

To force regeneration of Vulkan shader headers from `.comp`, `.vert`, and `.frag` sources:

```bash
./gradlew :app:regenerateVulkanSpirv
```

To verify that committed headers are in sync (without writing files), run:

```bash
./gradlew :app:checkVulkanSpirv
```

Requirements:

- `glslc` (preferred), or `glslangValidator`
- `xxd`

Compiler detection order in `scripts/regenerate_vulkan_spirv.sh`:

1. `glslc` in `PATH`
2. `$ANDROID_NDK_HOME/shader-tools/*/glslc` (or `$ANDROID_NDK_ROOT/...`)
3. `glslangValidator` in `PATH`
