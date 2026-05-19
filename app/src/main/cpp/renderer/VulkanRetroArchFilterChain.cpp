#include "renderer/VulkanRetroArchFilterChain.h"

#include <cstring>
#include <utility>

#include "Platform.h"
#include "VulkanContext.h"
#include "VulkanDispatch.h"

namespace MelonDSAndroid
{

VulkanRetroArchFilterChain::~VulkanRetroArchFilterChain()
{
    shutdown();
}

VulkanRetroArchFilterChain::VulkanRetroArchFilterChain(VulkanRetroArchFilterChain&& other) noexcept
{
    chain = other.chain;
    currentPresetPath = std::move(other.currentPresetPath);
    currentSourceWidth = other.currentSourceWidth;
    currentSourceHeight = other.currentSourceHeight;
    currentOutputWidth = other.currentOutputWidth;
    currentOutputHeight = other.currentOutputHeight;
    currentParameterOverrides = std::move(other.currentParameterOverrides);

    other.chain = nullptr;
    other.currentSourceWidth = 0;
    other.currentSourceHeight = 0;
    other.currentOutputWidth = 0;
    other.currentOutputHeight = 0;
}

VulkanRetroArchFilterChain& VulkanRetroArchFilterChain::operator=(VulkanRetroArchFilterChain&& other) noexcept
{
    if (this == &other)
        return *this;

    shutdown();
    chain = other.chain;
    currentPresetPath = std::move(other.currentPresetPath);
    currentSourceWidth = other.currentSourceWidth;
    currentSourceHeight = other.currentSourceHeight;
    currentOutputWidth = other.currentOutputWidth;
    currentOutputHeight = other.currentOutputHeight;
    currentParameterOverrides = std::move(other.currentParameterOverrides);

    other.chain = nullptr;
    other.currentSourceWidth = 0;
    other.currentSourceHeight = 0;
    other.currentOutputWidth = 0;
    other.currentOutputHeight = 0;
    return *this;
}

void VulkanRetroArchFilterChain::shutdown()
{
    if (chain != nullptr)
    {
        libra_error_t error = libra_vk_filter_chain_free(&chain);
        if (error != nullptr)
            logError("libra_vk_filter_chain_free", error);
        chain = nullptr;
    }

    currentPresetPath.clear();
    currentSourceWidth = 0;
    currentSourceHeight = 0;
    currentOutputWidth = 0;
    currentOutputHeight = 0;
    currentParameterOverrides.clear();
}

bool VulkanRetroArchFilterChain::configure(
    const std::string& presetPath,
    melonDS::u32 sourceWidth,
    melonDS::u32 sourceHeight,
    melonDS::u32 outputWidth,
    melonDS::u32 outputHeight,
    const std::vector<std::pair<std::string, float>>& parameterOverrides)
{
    if (presetPath.empty() || sourceWidth == 0 || sourceHeight == 0 || outputWidth == 0 || outputHeight == 0)
    {
        shutdown();
        return false;
    }

    if (chain != nullptr
        && currentPresetPath == presetPath
        && currentSourceWidth == sourceWidth
        && currentSourceHeight == sourceHeight
        && currentOutputWidth == outputWidth
        && currentOutputHeight == outputHeight
        && currentParameterOverrides == parameterOverrides)
    {
        return true;
    }

    shutdown();
    return createChain(presetPath, sourceWidth, sourceHeight, outputWidth, outputHeight, parameterOverrides);
}

bool VulkanRetroArchFilterChain::createChain(
    const std::string& presetPath,
    melonDS::u32 sourceWidth,
    melonDS::u32 sourceHeight,
    melonDS::u32 outputWidth,
    melonDS::u32 outputHeight,
    const std::vector<std::pair<std::string, float>>& parameterOverrides)
{
    melonDS::VulkanContext& context = melonDS::VulkanContext::Get();
    libra_device_vk_t deviceInfo{};
    deviceInfo.physical_device = context.GetPhysicalDevice();
    deviceInfo.instance = context.GetInstance();
    deviceInfo.device = context.GetDevice();
    deviceInfo.queue = context.GetQueue();
    deviceInfo.entry = vkGetInstanceProcAddr;

    libra_preset_ctx_t presetContext = nullptr;
    libra_error_t error = libra_preset_ctx_create(&presetContext);
    if (error != nullptr)
    {
        logError("libra_preset_ctx_create", error);
        return false;
    }
    (void)libra_preset_ctx_set_runtime(&presetContext, LIBRA_PRESET_CTX_RUNTIME_VULKAN);
    (void)libra_preset_ctx_set_core_name(&presetContext, "melonDS Android");
    (void)libra_preset_ctx_set_core_aspect_orientation(&presetContext, LIBRA_PRESET_CTX_ORIENTATION_HORIZONTAL);
    (void)libra_preset_ctx_set_view_aspect_orientation(&presetContext, LIBRA_PRESET_CTX_ORIENTATION_HORIZONTAL);
    (void)libra_preset_ctx_set_allow_rotation(&presetContext, false);

    libra_preset_opt_t presetOptions{};
    presetOptions.version = LIBRASHADER_CURRENT_ABI;
    presetOptions.original_aspect_uniforms = true;
    presetOptions.frametime_uniforms = true;

    libra_shader_preset_t preset = nullptr;
    error = libra_preset_create_with_options(presetPath.c_str(), &presetContext, &presetOptions, &preset);
    if (error != nullptr)
    {
        logError("libra_preset_create_with_options", error);
        if (presetContext != nullptr)
            (void)libra_preset_ctx_free(&presetContext);
        return false;
    }

    filter_chain_vk_opt_t options{};
    options.version = LIBRASHADER_CURRENT_ABI;
    options.frames_in_flight = 3;
    options.force_no_mipmaps = false;
    options.use_dynamic_rendering = false;
    options.disable_cache = false;

    error = libra_vk_filter_chain_create(&preset, deviceInfo, &options, &chain);
    if (error != nullptr)
    {
        logError("libra_vk_filter_chain_create", error);
        if (preset != nullptr)
            (void)libra_preset_free(&preset);
        return false;
    }

    for (const auto& [name, value] : parameterOverrides)
    {
        if (name.empty())
            continue;
        libra_error_t paramError = libra_vk_filter_chain_set_param(&chain, name.c_str(), value);
        if (paramError != nullptr)
            logError("libra_vk_filter_chain_set_param", paramError);
    }

    currentPresetPath = presetPath;
    currentSourceWidth = sourceWidth;
    currentSourceHeight = sourceHeight;
    currentOutputWidth = outputWidth;
    currentOutputHeight = outputHeight;
    currentParameterOverrides = parameterOverrides;
    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Info,
        "VulkanPresenter[RetroArch]: preset=%s source=%ux%u output=%ux%u sourceFormat=B8G8R8A8_UNORM outputFormat=R8G8B8A8_UNORM params=%zu",
        presetPath.c_str(),
        sourceWidth,
        sourceHeight,
        outputWidth,
        outputHeight,
        parameterOverrides.size());
    return true;
}

bool VulkanRetroArchFilterChain::recordFrame(
    VkCommandBuffer commandBuffer,
    VkImage sourceImage,
    VkImage outputImage,
    melonDS::u64 frameCount,
    bool clearHistory,
    melonDS::u32 frametimeDeltaMs)
{
    if (chain == nullptr
        || commandBuffer == VK_NULL_HANDLE
        || sourceImage == VK_NULL_HANDLE
        || outputImage == VK_NULL_HANDLE
        || currentSourceWidth == 0
        || currentSourceHeight == 0
        || currentOutputWidth == 0
        || currentOutputHeight == 0)
    {
        return false;
    }

    libra_image_vk_t source{};
    source.handle = sourceImage;
    source.format = VK_FORMAT_B8G8R8A8_UNORM;
    source.width = currentSourceWidth;
    source.height = currentSourceHeight;

    libra_image_vk_t output{};
    output.handle = outputImage;
    output.format = VK_FORMAT_R8G8B8A8_UNORM;
    output.width = currentOutputWidth;
    output.height = currentOutputHeight;

    libra_viewport_t viewport{};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = currentOutputWidth;
    viewport.height = currentOutputHeight;

    frame_vk_opt_t options{};
    options.version = LIBRASHADER_CURRENT_ABI;
    options.clear_history = clearHistory;
    options.frame_direction = 1;
    options.rotation = 0;
    options.total_subframes = 1;
    options.current_subframe = 1;
    options.aspect_ratio = 4.0f / 3.0f;
    options.frames_per_second = 60.0f;
    options.frametime_delta = frametimeDeltaMs;

    libra_error_t error = libra_vk_filter_chain_frame(
        &chain,
        commandBuffer,
        static_cast<size_t>(frameCount),
        source,
        output,
        &viewport,
        nullptr,
        &options);
    if (error != nullptr)
    {
        logError("libra_vk_filter_chain_frame", error);
        return false;
    }

    return true;
}

void VulkanRetroArchFilterChain::logError(const char* context, libra_error_t error)
{
    char* message = nullptr;
    if (libra_error_write(error, &message) == 0 && message != nullptr)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "%s failed: %s", context, message);
        (void)libra_error_free_string(&message);
    }
    else
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "%s failed", context);
    }
    (void)libra_error_free(&error);
}

}
