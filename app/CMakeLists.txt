project(melonDS-android-frontend)

cmake_minimum_required(VERSION 3.4.1)

add_compile_definitions(MELONDS_GL_HEADER=<GLES3/gl32.h>)
add_compile_definitions(EGL_EGLEXT_PROTOTYPES)
if (MELONDS_ANDROID_DEBUG_BUILD)
    add_compile_definitions(MELONDS_ANDROID_DEBUG_BUILD=1)
else()
    add_compile_definitions(MELONDS_ANDROID_DEBUG_BUILD=0)
endif()

set(CORE_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../melonDS-android-lib")
set(VULKAN_SPIRV_REGEN_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/regenerate_vulkan_spirv.sh")

set(VULKAN_SHADER_SOURCES
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_InterpSpansShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_BinCombinedShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_CalculateWorkOffsetsShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_SortWorkShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterBaseShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterCompatShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_DepthBlendShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_FinalPassShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_CaptureLineExportShader.comp"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsRasterShader.vert"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsRasterShader.frag"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsNoColorShader.frag"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsClearShader.frag"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsFinalShader.vert"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsEdgeShader.frag"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsFogShader.frag"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanCompositorShader.comp"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanSurfacePresenter.vert"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanSurfacePresenter.frag"
)

set(VULKAN_SHADER_HEADERS
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_InterpSpansShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_BinCombinedShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_CalculateWorkOffsetsShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_SortWorkShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterBaseShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_TriRasterCompatShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_DepthBlendShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_FinalPassShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_CaptureLineExportShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsRasterShaderVertexData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsRasterShaderFragmentData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsNoColorShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsClearShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsFinalShaderVertexData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsEdgeShaderData.h"
        "${CORE_LIB_DIR}/src/GPU3D_Vulkan_GraphicsFogShaderData.h"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanCompositorShaderData.h"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanSurfacePresenterVertexShaderData.h"
        "${CORE_LIB_DIR}/src/android/renderer/VulkanSurfacePresenterFragmentShaderData.h"
)

add_custom_command(
        OUTPUT ${VULKAN_SHADER_HEADERS}
        COMMAND bash "${VULKAN_SPIRV_REGEN_SCRIPT}"
        DEPENDS "${VULKAN_SPIRV_REGEN_SCRIPT}" ${VULKAN_SHADER_SOURCES}
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/.."
        COMMENT "Regenerating Vulkan SPIR-V headers"
        VERBATIM
)

add_custom_target(regenerate_vulkan_spirv_headers DEPENDS ${VULKAN_SHADER_HEADERS})

add_subdirectory(${CORE_LIB_DIR} ./melonDS-android-lib)
include_directories(${CORE_LIB_DIR}/src/android)

if (TARGET melonDS-lib)
    add_dependencies(melonDS-lib regenerate_vulkan_spirv_headers)
endif()

add_library(
        melonDS-android-frontend

        SHARED

        src/main/cpp/AndroidMelonEventMessenger.cpp
        src/main/cpp/EmulatorMessageQueueJNI.cpp
        src/main/cpp/MelonDSAndroidJNI.cpp
        src/main/cpp/MelonDSAndroidConfiguration.cpp
        src/main/cpp/MelonDSAndroidInterface.cpp
        src/main/cpp/MelonDSNandJNI.cpp
        src/main/cpp/NativeGlContext.cpp
        src/main/cpp/UriFileHandler.cpp
        src/main/cpp/JniEnvHandler.cpp
        src/main/cpp/MelonDSAndroidCameraHandler.cpp
        src/main/cpp/RetroAchievementsMapper.cpp
        src/main/cpp/RomIconBuilder.cpp
)

target_link_libraries(melonDS-android-frontend melonDS-lib)
find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(melonDS-android-frontend ${vulkan-lib})
target_link_libraries(melonDS-android-frontend ${jnigraphics-lib})
