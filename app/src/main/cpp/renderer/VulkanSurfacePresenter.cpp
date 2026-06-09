#include "VulkanSurfacePresenter.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>
#include <vector>

#include "Platform.h"
#include "VulkanContext.h"
#include "VulkanDispatch.h"
#include "VulkanOutput.h"
#include "VulkanSurfacePresenterFragmentShaderData.h"
#include "VulkanSurfacePresenterVertexShaderData.h"

namespace MelonDSAndroid
{
bool isFastForwardActive();
bool areRendererDebugBgObjLogsEnabled();

namespace
{
constexpr u32 kMaxSurfaceVertexCount = 30;
constexpr VkDeviceSize kVertexBufferSize = static_cast<VkDeviceSize>(kMaxSurfaceVertexCount * 5u * sizeof(float));
constexpr u32 kDescriptorSetCapacity = 64;
constexpr u32 kDrawModeBackground = 0u;
constexpr u32 kDrawModeCompositeFrame = 1u;
constexpr u32 kDrawModeTopScreen = 2u;
constexpr u32 kDrawModeBottomScreen = 3u;
constexpr u32 kDrawModeFilteredCompositeTop = 4u;
constexpr u32 kDrawModeFilteredCompositeBottom = 5u;
constexpr u32 kDrawModeRetroArchCompositeFrame = 6u;
constexpr u32 kNativeScreenWidth = 256u;
constexpr u32 kNativeScreenHeight = 192u;
constexpr u32 kNativeAtlasHeight = 386u;
constexpr u32 kMaxRetroArchNativeDisplayScale = 8u;
constexpr size_t kPrewarmedRetroArchSurfaceCount = 2u;
constexpr std::array<VkFormat, 7> kPreferredSurfaceFormats = {
    VK_FORMAT_R8G8B8A8_UNORM,
    VK_FORMAT_B8G8R8A8_UNORM,
    VK_FORMAT_R8G8B8A8_SRGB,
    VK_FORMAT_B8G8R8A8_SRGB,
    VK_FORMAT_R5G6B5_UNORM_PACK16,
    VK_FORMAT_A1R5G5B5_UNORM_PACK16,
    VK_FORMAT_R5G5B5A1_UNORM_PACK16,
};

struct PresenterPushConstants
{
    u32 drawMode;
    u32 scale;
    u32 rendererWidth;
    u32 rendererHeight;
    u32 packedStride;
    u32 screenSwap;
    u32 filtering;
    u32 previousTopSourceValid;
    u32 previousBottomSourceValid;
    u32 captureSourceValid;
    u32 captureSourceScreenSwapValid;
    u32 captureSourceScreenSwap;
    u32 liveSourceScreenSwap;
    u32 class4VramStructuredPair;
    u32 class4NoAboveVramStructuredPair;
    u32 class4PreservePackedVramValid;
    u32 class4PreservePackedVramScreenSwap;
    u32 topStructuredHandoffNoCurrent3d;
    u32 bottomStructuredHandoffNoCurrent3d;
    u32 topStructuredHandoffSuppress3d;
    u32 bottomStructuredHandoffSuppress3d;
    float viewportWidth;
    float viewportHeight;
};

struct PrewarmedRetroArchChains
{
    VulkanRetroArchFilterChain topChain;
    VulkanRetroArchFilterChain bottomChain;
};

std::mutex gPrewarmedRetroArchMutex;
std::unordered_map<std::string, std::vector<PrewarmedRetroArchChains>> gPrewarmedRetroArchChains;

bool presenterRectsEqual(const VulkanPresenterRect& left, const VulkanPresenterRect& right)
{
    return left.enabled == right.enabled
        && left.x == right.x
        && left.y == right.y
        && left.width == right.width
        && left.height == right.height;
}

bool surfaceConfigsEqual(const VulkanSurfaceConfig& left, const VulkanSurfaceConfig& right)
{
    return presenterRectsEqual(left.topScreen, right.topScreen)
        && presenterRectsEqual(left.bottomScreen, right.bottomScreen)
        && presenterRectsEqual(left.hybridTopScreen, right.hybridTopScreen)
        && presenterRectsEqual(left.hybridBottomScreen, right.hybridBottomScreen)
        && left.topAlpha == right.topAlpha
        && left.bottomAlpha == right.bottomAlpha
        && left.topOnTop == right.topOnTop
        && left.bottomOnTop == right.bottomOnTop
        && left.hybridAlpha == right.hybridAlpha
        && left.hybridOnTop == right.hybridOnTop
        && left.backgroundMode == right.backgroundMode
        && left.filtering == right.filtering
        && left.retroShaderEnabled == right.retroShaderEnabled
        && left.retroShaderPresetPath == right.retroShaderPresetPath
        && left.retroShaderSourceResolution == right.retroShaderSourceResolution
        && left.retroShaderPassCount == right.retroShaderPassCount
        && left.retroShaderParameterOverrides == right.retroShaderParameterOverrides
        && left.retroShaderClearHistory == right.retroShaderClearHistory;
}

bool retroArchConfigEqual(const VulkanSurfaceConfig& left, const VulkanSurfaceConfig& right)
{
    return left.filtering == right.filtering
        && left.retroShaderEnabled == right.retroShaderEnabled
        && left.retroShaderPresetPath == right.retroShaderPresetPath
        && left.retroShaderSourceResolution == right.retroShaderSourceResolution
        && left.retroShaderPassCount == right.retroShaderPassCount
        && left.retroShaderParameterOverrides == right.retroShaderParameterOverrides
        && left.retroShaderClearHistory == right.retroShaderClearHistory;
}

std::string makeRetroArchConfigKey(
    const VulkanSurfaceConfig& config,
    u32 sourceScreenWidth,
    u32 sourceScreenHeight,
    u32 outputScreenWidth,
    u32 outputScreenHeight)
{
    std::string configKey = config.retroShaderPresetPath
        + "|"
        + std::to_string(sourceScreenWidth)
        + "x"
        + std::to_string(sourceScreenHeight)
        + ">"
        + std::to_string(outputScreenWidth)
        + "x"
        + std::to_string(outputScreenHeight);
    for (const auto& [name, value] : config.retroShaderParameterOverrides)
    {
        configKey += "|";
        configKey += name;
        configKey += "=";
        configKey += std::to_string(value);
    }
    return configKey;
}

bool takePrewarmedRetroArchChains(
    const std::string& configKey,
    VulkanRetroArchFilterChain& topChain,
    VulkanRetroArchFilterChain& bottomChain)
{
    std::scoped_lock lock(gPrewarmedRetroArchMutex);
    auto cached = gPrewarmedRetroArchChains.find(configKey);
    if (cached == gPrewarmedRetroArchChains.end() || cached->second.empty())
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanPresenter[RetroArch]: prewarmed chains missing key=%s cached=%zu",
            configKey.c_str(),
            gPrewarmedRetroArchChains.size());
        return false;
    }

    PrewarmedRetroArchChains chains = std::move(cached->second.back());
    cached->second.pop_back();
    const size_t remaining = cached->second.size();
    if (cached->second.empty())
        gPrewarmedRetroArchChains.erase(cached);

    topChain = std::move(chains.topChain);
    bottomChain = std::move(chains.bottomChain);
    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Info,
        "VulkanPresenter[RetroArch]: consumed prewarmed chains key=%s remaining=%zu",
        configKey.c_str(),
        remaining);
    return true;
}

bool isPreferredAndroidSurfaceFormat(VkFormat format)
{
    switch (format)
    {
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
        case VK_FORMAT_B8G8R8A8_SRGB:
        case VK_FORMAT_R5G6B5_UNORM_PACK16:
        case VK_FORMAT_A1R5G5B5_UNORM_PACK16:
        case VK_FORMAT_R5G5B5A1_UNORM_PACK16:
            return true;
        default:
            return false;
    }
}

bool isProblematicAndroidSurfaceFormat(VkFormat format)
{
    // Some Android gralloc stacks, including Adreno 650-class devices, report
    // A8B8G8R8 formats but fail allocation later with "No map for format 0x38".
    // Prefer the widely supported RGBA/BGRA/565 paths for presentation surfaces.
    return format == VK_FORMAT_A8B8G8R8_UNORM_PACK32
        || format == VK_FORMAT_A8B8G8R8_SRGB_PACK32;
}

VkSurfaceFormatKHR chooseSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats)
{
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED)
    {
        return VkSurfaceFormatKHR{
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .colorSpace = formats.front().colorSpace,
        };
    }

    for (const VkFormat preferredFormat : kPreferredSurfaceFormats)
    {
        for (const VkSurfaceFormatKHR& format : formats)
        {
            if (format.format == preferredFormat && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return format;
        }
    }

    for (const VkFormat preferredFormat : kPreferredSurfaceFormats)
    {
        for (const VkSurfaceFormatKHR& format : formats)
        {
            if (format.format == preferredFormat)
                return format;
        }
    }

    for (const VkSurfaceFormatKHR& format : formats)
    {
        if (isPreferredAndroidSurfaceFormat(format.format) && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
            return format;
    }

    for (const VkSurfaceFormatKHR& format : formats)
    {
        if (isPreferredAndroidSurfaceFormat(format.format))
            return format;
    }

    return formats.front();
}

VkPresentModeKHR choosePresentMode(const std::vector<VkPresentModeKHR>& presentModes)
{
    constexpr std::array<VkPresentModeKHR, 4> preferredPresentModes = {
        VK_PRESENT_MODE_IMMEDIATE_KHR,
        VK_PRESENT_MODE_MAILBOX_KHR,
        VK_PRESENT_MODE_FIFO_RELAXED_KHR,
        VK_PRESENT_MODE_FIFO_KHR,
    };

    for (const VkPresentModeKHR preferredPresentMode : preferredPresentModes)
    {
        for (const VkPresentModeKHR presentMode : presentModes)
        {
            if (presentMode == preferredPresentMode)
                return presentMode;
        }
    }

    for (const VkPresentModeKHR presentMode : presentModes)
    {
        if (presentMode == VK_PRESENT_MODE_FIFO_KHR)
            return presentMode;
    }

    return presentModes.front();
}

std::vector<VkSurfaceFormatKHR> rankSurfaceFormats(const std::vector<VkSurfaceFormatKHR>& formats)
{
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED)
    {
        // VK_FORMAT_UNDEFINED means "application chooses"; materialize concrete safe formats
        // instead of feeding UNDEFINED to vkCreateSwapchainKHR on older Adreno stacks.
        std::vector<VkSurfaceFormatKHR> ranked;
        ranked.reserve(kPreferredSurfaceFormats.size());
        for (const VkFormat preferredFormat : kPreferredSurfaceFormats)
        {
            ranked.push_back(VkSurfaceFormatKHR{
                .format = preferredFormat,
                .colorSpace = formats.front().colorSpace,
            });
        }
        return ranked;
    }

    std::vector<VkSurfaceFormatKHR> supportedFormats;
    supportedFormats.reserve(formats.size());
    for (const VkSurfaceFormatKHR& format : formats)
    {
        if (!isProblematicAndroidSurfaceFormat(format.format))
            supportedFormats.push_back(format);
    }
    const std::vector<VkSurfaceFormatKHR>& candidateFormats = supportedFormats.empty() ? formats : supportedFormats;

    std::vector<VkSurfaceFormatKHR> ranked;
    ranked.reserve(candidateFormats.size());
    std::vector<bool> consumed(candidateFormats.size(), false);

    auto consumeMatching = [&](auto&& predicate) {
        for (size_t i = 0; i < candidateFormats.size(); i++)
        {
            if (consumed[i])
                continue;

            if (predicate(candidateFormats[i]))
            {
                ranked.push_back(candidateFormats[i]);
                consumed[i] = true;
            }
        }
    };

    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return format.format == VK_FORMAT_R8G8B8A8_UNORM && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return format.format == VK_FORMAT_B8G8R8A8_UNORM && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return format.format == VK_FORMAT_R8G8B8A8_SRGB && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return format.format == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return format.format == VK_FORMAT_R5G6B5_UNORM_PACK16 && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    consumeMatching([](const VkSurfaceFormatKHR& format) {
        return isPreferredAndroidSurfaceFormat(format.format);
    });
    consumeMatching([](const VkSurfaceFormatKHR&) { return true; });

    if (ranked.empty() && !candidateFormats.empty())
        ranked.push_back(candidateFormats.front());

    return ranked;
}

std::vector<VkPresentModeKHR> rankPresentModes(const std::vector<VkPresentModeKHR>& presentModes)
{
    std::vector<VkPresentModeKHR> ranked;
    ranked.reserve(presentModes.size());
    std::vector<bool> consumed(presentModes.size(), false);

    auto consumeValue = [&](VkPresentModeKHR mode) {
        for (size_t i = 0; i < presentModes.size(); i++)
        {
            if (consumed[i] || presentModes[i] != mode)
                continue;

            ranked.push_back(mode);
            consumed[i] = true;
        }
    };

    consumeValue(VK_PRESENT_MODE_IMMEDIATE_KHR);
    consumeValue(VK_PRESENT_MODE_MAILBOX_KHR);
    consumeValue(VK_PRESENT_MODE_FIFO_RELAXED_KHR);
    consumeValue(VK_PRESENT_MODE_FIFO_KHR);

    for (size_t i = 0; i < presentModes.size(); i++)
    {
        if (!consumed[i])
            ranked.push_back(presentModes[i]);
    }

    if (ranked.empty() && !presentModes.empty())
        ranked.push_back(presentModes.front());

    return ranked;
}

u64 makeSwapchainConfigKey(int surfaceId, VkFormat format, VkColorSpaceKHR colorSpace, VkPresentModeKHR presentMode)
{
    const u64 surfacePart = static_cast<u64>(static_cast<u32>(surfaceId) & 0xFFFFFu) << 44u;
    const u64 presentModePart = static_cast<u64>(static_cast<u32>(presentMode) & 0xFFu) << 36u;
    const u64 colorSpacePart = static_cast<u64>(static_cast<u32>(colorSpace) & 0xFFu) << 28u;
    const u64 formatPart = static_cast<u64>(static_cast<u32>(format) & 0x0FFFFFFFu);
    return surfacePart | presentModePart | colorSpacePart | formatPart;
}
}

VulkanSurfacePresenter::~VulkanSurfacePresenter()
{
    shutdown();
}

bool VulkanSurfacePresenter::init()
{
    if (initialized)
        return true;

    if (!melonDS::VulkanContext::Get().Acquire())
        return false;

    contextAcquired = true;
    instance = melonDS::VulkanContext::Get().GetInstance();
    physicalDevice = melonDS::VulkanContext::Get().GetPhysicalDevice();
    device = melonDS::VulkanContext::Get().GetDevice();
    queue = melonDS::VulkanContext::Get().GetQueue();
    queueFamilyIndex = melonDS::VulkanContext::Get().GetQueueFamilyIndex();
    useTimelineSemaphores = melonDS::VulkanContext::Get().SupportsTimelineSemaphores();
    waitSemaphores = useTimelineSemaphores ? melonDS::VulkanContext::Get().GetWaitSemaphores() : nullptr;
    resetQueryPool = melonDS::VulkanContext::Get().GetResetQueryPool();
    timestampPeriodNs = melonDS::VulkanContext::Get().GetTimestampPeriod();
    timestampQueriesSupported = melonDS::VulkanContext::Get().SupportsTimestamps();

    if (instance == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE)
    {
        shutdown();
        return false;
    }

    if (!createCommonResources())
    {
        shutdown();
        return false;
    }

    if (!createSyncObjects())
    {
        shutdown();
        return false;
    }

    initialized = true;
    return true;
}

void VulkanSurfacePresenter::shutdown()
{
    if (device != VK_NULL_HANDLE)
    {
        std::scoped_lock queueLock(melonDS::VulkanContext::Get().GetQueueLock());
        vkQueueWaitIdle(queue);
    }

    clearPrewarmedRetroArchFilters();

    while (!surfaces.empty())
    {
        detachSurface(surfaces.begin()->first);
    }

    destroySyncObjects();
    destroyCommonResources();

    if (contextAcquired)
    {
        melonDS::VulkanContext::Get().Release();
        contextAcquired = false;
    }

    initialized = false;
    nextSurfaceId = 1;
    instance = VK_NULL_HANDLE;
    physicalDevice = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    queueFamilyIndex = 0;
    useTimelineSemaphores = false;
    timelineValue = 0;
    timelineSemaphore = VK_NULL_HANDLE;
    waitSemaphores = nullptr;
    resetQueryPool = nullptr;
    timestampPeriodNs = 0.0f;
    timestampQueriesSupported = false;
}

bool VulkanSurfacePresenter::prewarmRetroArchFilter(
    const VulkanSurfaceConfig& config,
    u32 outputScreenWidth,
    u32 outputScreenHeight)
{
    if (config.filtering != VulkanFilterMode::RetroArch
        || !config.retroShaderEnabled
        || config.retroShaderPresetPath.empty())
    {
        return true;
    }

    if (outputScreenWidth == 0 || outputScreenHeight == 0)
        return false;

    const bool nativeSourcePreset = config.retroShaderSourceResolution == RetroArchSourceResolution::Native;
    const u32 sourceScreenWidth = nativeSourcePreset ? kNativeScreenWidth : outputScreenWidth;
    const u32 sourceScreenHeight = nativeSourcePreset ? kNativeScreenHeight : outputScreenHeight;
    const std::string configKey = makeRetroArchConfigKey(
        config,
        sourceScreenWidth,
        sourceScreenHeight,
        outputScreenWidth,
        outputScreenHeight);

    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Info,
        "VulkanPresenter[RetroArch]: prewarm start key=%s",
        configKey.c_str());

    {
        std::scoped_lock lock(gPrewarmedRetroArchMutex);
        auto cached = gPrewarmedRetroArchChains.find(configKey);
        if (cached != gPrewarmedRetroArchChains.end()
            && cached->second.size() >= kPrewarmedRetroArchSurfaceCount)
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Info,
                "VulkanPresenter[RetroArch]: prewarm already cached key=%s count=%zu",
                configKey.c_str(),
                cached->second.size());
            return true;
        }
    }

    std::vector<PrewarmedRetroArchChains> chainSets;
    chainSets.reserve(kPrewarmedRetroArchSurfaceCount);
    for (size_t chainSetIndex = 0; chainSetIndex < kPrewarmedRetroArchSurfaceCount; chainSetIndex++)
    {
        PrewarmedRetroArchChains chains{};
        if (!chains.topChain.configure(
                config.retroShaderPresetPath,
                sourceScreenWidth,
                sourceScreenHeight,
                outputScreenWidth,
                outputScreenHeight,
                config.retroShaderParameterOverrides)
            || !chains.bottomChain.configure(
                config.retroShaderPresetPath,
                sourceScreenWidth,
                sourceScreenHeight,
                outputScreenWidth,
                outputScreenHeight,
                config.retroShaderParameterOverrides))
        {
            chains.topChain.shutdown();
            chains.bottomChain.shutdown();
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "VulkanPresenter[RetroArch]: prewarm failed key=%s chainSet=%zu",
                configKey.c_str(),
                chainSetIndex);
            return false;
        }
        chainSets.emplace_back(std::move(chains));
    }

    {
        std::scoped_lock lock(gPrewarmedRetroArchMutex);
        gPrewarmedRetroArchChains.clear();
        gPrewarmedRetroArchChains.emplace(configKey, std::move(chainSets));
    }

    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Info,
        "VulkanPresenter[RetroArch]: prewarmed preset=%s source=%ux%u output=%ux%u params=%zu chainSets=%zu",
        config.retroShaderPresetPath.c_str(),
        sourceScreenWidth,
        sourceScreenHeight,
        outputScreenWidth,
        outputScreenHeight,
        config.retroShaderParameterOverrides.size(),
        kPrewarmedRetroArchSurfaceCount);
    return true;
}

void VulkanSurfacePresenter::clearPrewarmedRetroArchFilters()
{
    std::scoped_lock lock(gPrewarmedRetroArchMutex);
    gPrewarmedRetroArchChains.clear();
}

bool VulkanSurfacePresenter::createSyncObjects()
{
    if (!useTimelineSemaphores)
        return true;

    if (waitSemaphores == nullptr)
    {
        useTimelineSemaphores = false;
        return true;
    }

    VkSemaphoreTypeCreateInfo semaphoreTypeInfo{};
    semaphoreTypeInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    semaphoreTypeInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    semaphoreTypeInfo.initialValue = 0;

    VkSemaphoreCreateInfo semaphoreCreateInfo{};
    semaphoreCreateInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreCreateInfo.pNext = &semaphoreTypeInfo;

    if (vkCreateSemaphore(device, &semaphoreCreateInfo, nullptr, &timelineSemaphore) != VK_SUCCESS)
    {
        useTimelineSemaphores = false;
        timelineSemaphore = VK_NULL_HANDLE;
        return true;
    }

    timelineValue = 0;
    return true;
}

void VulkanSurfacePresenter::destroySyncObjects()
{
    if (timelineSemaphore != VK_NULL_HANDLE)
    {
        vkDestroySemaphore(device, timelineSemaphore, nullptr);
        timelineSemaphore = VK_NULL_HANDLE;
    }
    timelineValue = 0;
}

bool VulkanSurfacePresenter::createCommonResources()
{
    VkDescriptorSetLayoutBinding sampledTextureBinding{};
    sampledTextureBinding.binding = 0;
    sampledTextureBinding.descriptorCount = 1;
    sampledTextureBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sampledTextureBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding rendererImageBinding{};
    rendererImageBinding.binding = 1;
    rendererImageBinding.descriptorCount = 1;
    rendererImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rendererImageBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding topPackedBinding{};
    topPackedBinding.binding = 2;
    topPackedBinding.descriptorCount = 1;
    topPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    topPackedBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding bottomPackedBinding{};
    bottomPackedBinding.binding = 3;
    bottomPackedBinding.descriptorCount = 1;
    bottomPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bottomPackedBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding previousTopRendererImageBinding{};
    previousTopRendererImageBinding.binding = 4;
    previousTopRendererImageBinding.descriptorCount = 1;
    previousTopRendererImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    previousTopRendererImageBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding capture3dBinding{};
    capture3dBinding.binding = 5;
    capture3dBinding.descriptorCount = 1;
    capture3dBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    capture3dBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding previousBottomRendererImageBinding{};
    previousBottomRendererImageBinding.binding = 6;
    previousBottomRendererImageBinding.descriptorCount = 1;
    previousBottomRendererImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    previousBottomRendererImageBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    const std::array<VkDescriptorSetLayoutBinding, 7> bindings = {
        sampledTextureBinding,
        rendererImageBinding,
        topPackedBinding,
        bottomPackedBinding,
        previousTopRendererImageBinding,
        capture3dBinding,
        previousBottomRendererImageBinding,
    };

    VkDescriptorSetLayoutCreateInfo descriptorSetLayoutInfo{};
    descriptorSetLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorSetLayoutInfo.bindingCount = static_cast<u32>(bindings.size());
    descriptorSetLayoutInfo.pBindings = bindings.data();

    if (vkCreateDescriptorSetLayout(device, &descriptorSetLayoutInfo, nullptr, &descriptorSetLayout) != VK_SUCCESS)
        return false;

    std::array<VkDescriptorPoolSize, 3> poolSizes{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = kDescriptorSetCapacity;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = kDescriptorSetCapacity * 3u;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[2].descriptorCount = kDescriptorSetCapacity * 3u;

    VkDescriptorPoolCreateInfo descriptorPoolInfo{};
    descriptorPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    descriptorPoolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    descriptorPoolInfo.maxSets = kDescriptorSetCapacity;
    descriptorPoolInfo.poolSizeCount = static_cast<u32>(poolSizes.size());
    descriptorPoolInfo.pPoolSizes = poolSizes.data();

    if (vkCreateDescriptorPool(device, &descriptorPoolInfo, nullptr, &descriptorPool) != VK_SUCCESS)
        return false;

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(PresenterPushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;

    if (vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &pipelineLayout) != VK_SUCCESS)
        return false;

    auto createShaderModule = [&](const unsigned char* data, size_t length, VkShaderModule* shaderModule) -> bool {
        std::vector<u32> shaderWords((length + sizeof(u32) - 1u) / sizeof(u32));
        std::memcpy(shaderWords.data(), data, length);

        VkShaderModuleCreateInfo shaderModuleInfo{};
        shaderModuleInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        shaderModuleInfo.codeSize = length;
        shaderModuleInfo.pCode = shaderWords.data();

        return vkCreateShaderModule(device, &shaderModuleInfo, nullptr, shaderModule) == VK_SUCCESS;
    };

    if (!createShaderModule(
            melonDS_android_vulkan_surface_presenter_vert_spv,
            melonDS_android_vulkan_surface_presenter_vert_spv_len,
            &vertexShaderModule))
        return false;

    if (!createShaderModule(
            melonDS_android_vulkan_surface_presenter_frag_spv,
            melonDS_android_vulkan_surface_presenter_frag_spv_len,
            &fragmentShaderModule))
        return false;

    auto createSampler = [&](VkFilter filter, VkSampler* sampler) -> bool {
        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = filter;
        samplerInfo.minFilter = filter;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 1.0f;
        return vkCreateSampler(device, &samplerInfo, nullptr, sampler) == VK_SUCCESS;
    };

    if (!createSampler(VK_FILTER_NEAREST, &nearestSampler))
        return false;
    if (!createSampler(VK_FILTER_LINEAR, &linearSampler))
        return false;

    return true;
}

void VulkanSurfacePresenter::destroyCommonResources()
{
    if (nearestSampler != VK_NULL_HANDLE)
    {
        vkDestroySampler(device, nearestSampler, nullptr);
        nearestSampler = VK_NULL_HANDLE;
    }

    if (linearSampler != VK_NULL_HANDLE)
    {
        vkDestroySampler(device, linearSampler, nullptr);
        linearSampler = VK_NULL_HANDLE;
    }

    if (vertexShaderModule != VK_NULL_HANDLE)
    {
        vkDestroyShaderModule(device, vertexShaderModule, nullptr);
        vertexShaderModule = VK_NULL_HANDLE;
    }

    if (fragmentShaderModule != VK_NULL_HANDLE)
    {
        vkDestroyShaderModule(device, fragmentShaderModule, nullptr);
        fragmentShaderModule = VK_NULL_HANDLE;
    }

    if (pipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        pipelineLayout = VK_NULL_HANDLE;
    }

    if (descriptorPool != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorPool(device, descriptorPool, nullptr);
        descriptorPool = VK_NULL_HANDLE;
    }

    if (descriptorSetLayout != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);
        descriptorSetLayout = VK_NULL_HANDLE;
    }
}

bool VulkanSurfacePresenter::createTimestampQueryPool(VkQueryPool& queryPool)
{
    if (!timestampQueriesSupported)
        return true;

    VkQueryPoolCreateInfo queryPoolCreateInfo{};
    queryPoolCreateInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryPoolCreateInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryPoolCreateInfo.queryCount = 2;

    if (vkCreateQueryPool(device, &queryPoolCreateInfo, nullptr, &queryPool) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Warn, "VulkanSurfacePresenter: failed to create timestamp query pool");
        queryPool = VK_NULL_HANDLE;
    }

    return true;
}

void VulkanSurfacePresenter::destroyTimestampQueryPool(VkQueryPool& queryPool)
{
    if (queryPool != VK_NULL_HANDLE)
    {
        vkDestroyQueryPool(device, queryPool, nullptr);
        queryPool = VK_NULL_HANDLE;
    }
}

int VulkanSurfacePresenter::attachSurface(ANativeWindow* window, u32 width, u32 height)
{
    if (!initialized || window == nullptr)
        return 0;

    SurfaceState surfaceState{};
    surfaceState.id = nextSurfaceId++;
    surfaceState.window = window;
    surfaceState.requestedWidth = width;
    surfaceState.requestedHeight = height;

    VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo{};
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.window = window;

    if (vkCreateAndroidSurfaceKHR(instance, &surfaceCreateInfo, nullptr, &surfaceState.surface) != VK_SUCCESS)
    {
        ANativeWindow_release(window);
        return 0;
    }

    VkBool32 supportsPresentation = VK_FALSE;
    if (vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamilyIndex, surfaceState.surface, &supportsPresentation) != VK_SUCCESS
        || supportsPresentation == VK_FALSE)
    {
        vkDestroySurfaceKHR(instance, surfaceState.surface, nullptr);
        ANativeWindow_release(window);
        return 0;
    }

    if (!createSurfaceStateResources(surfaceState))
    {
        if (surfaceState.surface != VK_NULL_HANDLE)
            vkDestroySurfaceKHR(instance, surfaceState.surface, nullptr);
        ANativeWindow_release(window);
        return 0;
    }

    const int surfaceId = surfaceState.id;
    surfaces.emplace(surfaceId, std::move(surfaceState));
    return surfaceId;
}

bool VulkanSurfacePresenter::resizeSurface(int surfaceId, u32 width, u32 height)
{
    auto iterator = surfaces.find(surfaceId);
    if (iterator == surfaces.end())
        return false;

    SurfaceState& surfaceState = iterator->second;
    surfaceState.requestedWidth = width;
    surfaceState.requestedHeight = height;
    surfaceState.swapchainDirty = true;
    surfaceState.vertexBufferDirty = true;
    return true;
}

bool VulkanSurfacePresenter::configureSurface(int surfaceId, const VulkanSurfaceConfig& config, const VulkanBackgroundImage& backgroundImage)
{
    auto iterator = surfaces.find(surfaceId);
    if (iterator == surfaces.end())
        return false;

    SurfaceState& surfaceState = iterator->second;
    if (surfaceState.configured
        && backgroundImage.pixels == nullptr
        && surfaceState.background.imageView == VK_NULL_HANDLE
        && surfaceConfigsEqual(surfaceState.config, config))
    {
        return true;
    }

    if (waitForSurfaceIdle(surfaceState) != VK_SUCCESS)
        return false;

    const bool retroArchConfigChanged =
        surfaceState.configured && !retroArchConfigEqual(surfaceState.config, config);
    if (retroArchConfigChanged)
        destroyRetroArchResources(surfaceState);

    surfaceState.config = config;
    surfaceState.configured = true;
    surfaceState.vertexBufferDirty = true;
    surfaceState.backgroundDescriptorDirty = true;

    if (backgroundImage.pixels != nullptr && backgroundImage.width > 0 && backgroundImage.height > 0)
    {
        return ensureBackgroundTexture(surfaceState, backgroundImage);
    }

    destroyBackgroundTexture(surfaceState);
    return true;
}

void VulkanSurfacePresenter::detachSurface(int surfaceId)
{
    auto iterator = surfaces.find(surfaceId);
    if (iterator == surfaces.end())
        return;

    SurfaceState surfaceState = std::move(iterator->second);
    surfaces.erase(iterator);

    (void)waitForSurfaceIdle(surfaceState);
    destroySurfaceStateResources(surfaceState);
}

bool VulkanSurfacePresenter::presentFrame(Frame* frame, VulkanOutput& output, const VulkanCompositionInputs& inputs, u64 timeoutNs)
{
    if (!initialized)
        return false;

    if (surfaces.empty())
        return true;

    const bool fastForwardActive = MelonDSAndroid::isFastForwardActive();

    if (frame == nullptr || !output.waitForFrame(frame, timeoutNs))
    {
        frameWaitFailures++;
        return false;
    }

    const bool hasRequiredDirectHandles =
        inputs.sourceImage != VK_NULL_HANDLE
        && inputs.sourceImageView != VK_NULL_HANDLE
        && inputs.topPackedBuffer != VK_NULL_HANDLE
        && inputs.bottomPackedBuffer != VK_NULL_HANDLE;
    const bool hasDualScreenSurface = std::any_of(
        surfaces.begin(),
        surfaces.end(),
        [](const auto& entry) {
            const SurfaceState& surfaceState = entry.second;
            if (!surfaceState.configured)
                return false;

            const auto rectEnabled = [](const VulkanPresenterRect& rect) {
                return rect.enabled && rect.width > 0 && rect.height > 0;
            };

            int screenRectCount = 0;
            screenRectCount += rectEnabled(surfaceState.config.topScreen) ? 1 : 0;
            screenRectCount += rectEnabled(surfaceState.config.bottomScreen) ? 1 : 0;
            screenRectCount += rectEnabled(surfaceState.config.hybridTopScreen) ? 1 : 0;
            screenRectCount += rectEnabled(surfaceState.config.hybridBottomScreen) ? 1 : 0;
            return screenRectCount > 1;
        });
    const bool postProcessFilterRequested = std::any_of(
        surfaces.begin(),
        surfaces.end(),
        [](const auto& entry) {
            const SurfaceState& surfaceState = entry.second;
            return surfaceState.configured
                && IsVulkanPostProcessFilter(surfaceState.config.filtering);
        });
    const bool directPresentRequested = !inputs.needsReadback
        && !inputs.validationMode
        && !postProcessFilterRequested
        && surfaces.size() == 1
        && !hasDualScreenSurface
        && hasRequiredDirectHandles
        && !inputs.capture3dSourceValid
        && !inputs.previousTopSourceValid
        && !inputs.previousBottomSourceValid;

    if (!directPresentRequested)
    {
        if (inputs.needsReadback)
            fallbackReasonNeedsReadback++;
        if (inputs.validationMode)
            fallbackReasonValidationMode++;
        if (!hasRequiredDirectHandles)
            fallbackReasonMissingHandles++;
        if (surfaces.size() > 1 || hasDualScreenSurface)
            fallbackReasonSurfaceCount++;
    }

    VkImage frameImage = VK_NULL_HANDLE;
    VkImageView frameImageView = VK_NULL_HANDLE;
    if (!directPresentRequested)
    {
        if (!output.composeAndSubmitFrame(frame, inputs))
        {
            composeSubmitFailures++;
            return false;
        }
        const bool highResolutionRealtimeFallbackPresent =
            !fastForwardActive
            && inputs.scale > 1
            && !directPresentRequested;
        const u64 composeWaitTimeoutNs = highResolutionRealtimeFallbackPresent
            ? UINT64_MAX
            : timeoutNs;
        if (!output.waitForFrame(frame, composeWaitTimeoutNs))
        {
            composeWaitFailures++;
            return false;
        }

        frameImage = output.getFrameImage(frame);
        frameImageView = output.getFrameImageView(frame);
        if (frameImage == VK_NULL_HANDLE || frameImageView == VK_NULL_HANDLE)
        {
            missingFrameImageFailures++;
            return false;
        }
    }

    const u64 totalStartNs = PerfNowNs();
    const u64 deadlineNs = timeoutNs == UINT64_MAX ? UINT64_MAX : (totalStartNs + timeoutNs);
    u64 descriptorCpuNs = 0;
    u64 vertexCpuNs = 0;
    u64 acquireCpuNs = 0;
    u64 recordCpuNs = 0;
    u64 submitCpuNs = 0;
    u64 presentCpuNs = 0;
    u64 framePresentTimelineValue = 0;
    bool presentedAnySurface = false;
    bool sawConfiguredSurface = false;
    for (auto& [surfaceId, surfaceState] : surfaces)
    {
        (void)surfaceId;

        if (!surfaceState.configured)
            continue;
        sawConfiguredSurface = true;

        const bool directPresent = directPresentRequested;
        VkImage sampledImage = directPresent ? inputs.sourceImage : frameImage;
        VkImageView sampledImageView = directPresent ? inputs.sourceImageView : frameImageView;

        if (!ensureSwapchain(surfaceState))
        {
            swapchainUnavailableFrames++;
            continue;
        }

        const u64 remainingBudgetNs = [&]() -> u64 {
            if (fastForwardActive)
                return 0;
            if (deadlineNs == UINT64_MAX)
                return UINT64_MAX;

            const u64 nowNs = PerfNowNs();
            if (nowNs >= deadlineNs)
                return 0;
            return deadlineNs - nowNs;
        }();

        const VkResult waitResult = waitForSurfaceIdle(surfaceState, remainingBudgetNs);
        if (waitResult == VK_TIMEOUT)
        {
            skippedSurfaceWaits++;
            presentSkippedForDeadline++;
            continue;
        }
        if (waitResult != VK_SUCCESS)
        {
            surfaceWaitFailures++;
            continue;
        }

        bool retroArchApplied = false;
        VulkanFilterMode effectiveFiltering = surfaceState.config.filtering;
        if (surfaceState.config.filtering == VulkanFilterMode::RetroArch && !directPresent)
        {
            VkImage retroImage = VK_NULL_HANDLE;
            VkImageView retroImageView = VK_NULL_HANDLE;
            if (runRetroArchFilter(surfaceState, sampledImage, sampledImageView, frame->width, frame->height, retroImage, retroImageView))
            {
                sampledImage = retroImage;
                sampledImageView = retroImageView;
                retroArchApplied = true;
            }
            else
            {
                sampledImage = frameImage;
                sampledImageView = frameImageView;
                effectiveFiltering = VulkanFilterMode::Nearest;
            }
        }

        const u64 descriptorStartNs = PerfNowNs();
        if (!updateDescriptorSets(surfaceState, sampledImageView, inputs, effectiveFiltering, directPresent))
        {
            descriptorUpdateFailures++;
            continue;
        }
        descriptorCpuNs += PerfNowNs() - descriptorStartNs;

        std::vector<DrawCall> drawCalls;
        const u64 vertexStartNs = PerfNowNs();
        if (!updateVertexBuffer(
                surfaceState,
                surfaceState.config,
                surfaceState.background.imageView != VK_NULL_HANDLE ? &surfaceState.background : nullptr,
                directPresent,
                retroArchApplied,
                drawCalls))
        {
            vertexUpdateFailures++;
            continue;
        }
        vertexCpuNs += PerfNowNs() - vertexStartNs;

        u32 imageIndex = 0;
        const u64 acquireBudgetNs = [&]() -> u64 {
            if (fastForwardActive)
                return 0;
            if (deadlineNs == UINT64_MAX)
                return UINT64_MAX;

            const u64 nowNs = PerfNowNs();
            if (nowNs >= deadlineNs)
                return 0;
            return deadlineNs - nowNs;
        }();
        const u64 acquireStartNs = PerfNowNs();
        VkResult acquireResult = vkAcquireNextImageKHR(
            device,
            surfaceState.swapchain,
            acquireBudgetNs,
            surfaceState.imageAvailableSemaphore,
            VK_NULL_HANDLE,
            &imageIndex
        );
        acquireCpuNs += PerfNowNs() - acquireStartNs;

        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR)
        {
            surfaceState.swapchainDirty = true;
            if (!ensureSwapchain(surfaceState))
                continue;

            acquireResult = vkAcquireNextImageKHR(
                device,
                surfaceState.swapchain,
                acquireBudgetNs,
                surfaceState.imageAvailableSemaphore,
                VK_NULL_HANDLE,
                &imageIndex
            );
        }

        if (acquireResult == VK_TIMEOUT)
        {
            acquireTimeouts++;
            presentSkippedForDeadline++;
            continue;
        }

        if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR)
        {
            acquireFailures++;
            recoverSwapchain(surfaceState, "vkAcquireNextImageKHR");
            continue;
        }

        const u64 recordStartNs = PerfNowNs();
        if (!recordSurfaceCommands(surfaceState, surfaceState.framebuffers[imageIndex], inputs, sampledImage, directPresent, drawCalls))
        {
            recordFailures++;
            recoverSwapchain(surfaceState, "recordSurfaceCommands");
            continue;
        }
        recordCpuNs += PerfNowNs() - recordStartNs;

        const u64 submitStartNs = PerfNowNs();
        u64 surfacePresentCpuNs = 0;
        u64 surfacePresentTimelineValue = 0;
        if (!submitSurfaceCommands(surfaceState, imageIndex, surfacePresentCpuNs, surfacePresentTimelineValue))
        {
            submitFailures++;
            recoverSwapchain(surfaceState, "submitSurfaceCommands");
            continue;
        }
        submitCpuNs += PerfNowNs() - submitStartNs;
        presentCpuNs += surfacePresentCpuNs;
        framePresentTimelineValue = std::max(framePresentTimelineValue, surfacePresentTimelineValue);

        presentedAnySurface = true;
        lastPresentedDirect = directPresent;
        lastPresentMode = surfaceState.presentMode;
        lastSwapchainImageCount = static_cast<u32>(surfaceState.swapchainImages.size());
        if (directPresent)
            directPresentedFrames++;
        else
            fallbackPresentedFrames++;
    }

    if (presentedAnySurface)
    {
        frame->presentTimelineValue = framePresentTimelineValue;
        descriptorCpuWindow.Add(descriptorCpuNs);
        vertexCpuWindow.Add(vertexCpuNs);
        acquireCpuWindow.Add(acquireCpuNs);
        recordCpuWindow.Add(recordCpuNs);
        submitCpuWindow.Add(submitCpuNs);
        presentCpuWindow.Add(presentCpuNs);
        frameWallCpuWindow.Add(PerfNowNs() - totalStartNs);
        presentedFrames++;
        logPerformanceIfNeeded();
    }
    else if (!sawConfiguredSurface)
    {
        noConfiguredSurfaceFrames++;
    }

    return presentedAnySurface;
}

bool VulkanSurfacePresenter::waitForFrameConsumption(Frame* frame, u64 timeoutNs)
{
    if (!initialized || frame == nullptr || frame->presentTimelineValue == 0)
        return true;

    if (!useTimelineSemaphores || waitSemaphores == nullptr || timelineSemaphore == VK_NULL_HANDLE)
    {
        frame->presentTimelineValue = 0;
        return true;
    }

    VkSemaphoreWaitInfo waitInfo{};
    waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    waitInfo.semaphoreCount = 1;
    waitInfo.pSemaphores = &timelineSemaphore;
    waitInfo.pValues = &frame->presentTimelineValue;

    const bool waitOk = waitSemaphores(device, &waitInfo, timeoutNs) == VK_SUCCESS;
    if (waitOk)
        frame->presentTimelineValue = 0;
    return waitOk;
}

VulkanPresenterPacingStats VulkanSurfacePresenter::takePacingStatsSnapshotAndReset()
{
    VulkanPresenterPacingStats stats{};
    stats.AcquireTimeouts = acquireTimeouts;
    stats.PresentSkippedForDeadline = presentSkippedForDeadline;
    stats.SurfaceWaitTimeouts = skippedSurfaceWaits;
    stats.FrameWaitFailures = frameWaitFailures;
    stats.ComposeSubmitFailures = composeSubmitFailures;
    stats.ComposeWaitFailures = composeWaitFailures;
    stats.MissingFrameImageFailures = missingFrameImageFailures;
    stats.NoConfiguredSurfaceFrames = noConfiguredSurfaceFrames;
    stats.SwapchainUnavailableFrames = swapchainUnavailableFrames;
    stats.SurfaceWaitFailures = surfaceWaitFailures;
    stats.DescriptorUpdateFailures = descriptorUpdateFailures;
    stats.VertexUpdateFailures = vertexUpdateFailures;
    stats.AcquireFailures = acquireFailures;
    stats.RecordFailures = recordFailures;
    stats.SubmitFailures = submitFailures;
    stats.PresentedFrames = presentedFrames;
    stats.DirectPresentedFrames = directPresentedFrames;
    stats.FallbackPresentedFrames = fallbackPresentedFrames;
    stats.SwapchainRecoveries = swapchainRecoveries;
    stats.SwapchainImageCount = lastSwapchainImageCount;
    stats.PresentMode = lastPresentMode;

    acquireTimeouts = 0;
    presentSkippedForDeadline = 0;
    skippedSurfaceWaits = 0;
    frameWaitFailures = 0;
    composeSubmitFailures = 0;
    composeWaitFailures = 0;
    missingFrameImageFailures = 0;
    noConfiguredSurfaceFrames = 0;
    swapchainUnavailableFrames = 0;
    surfaceWaitFailures = 0;
    descriptorUpdateFailures = 0;
    vertexUpdateFailures = 0;
    acquireFailures = 0;
    recordFailures = 0;
    submitFailures = 0;
    swapchainRecoveries = 0;
    presentedFrames = 0;
    directPresentedFrames = 0;
    fallbackPresentedFrames = 0;

    return stats;
}

void VulkanSurfacePresenter::invalidateDescriptorCaches()
{
    for (auto& [surfaceId, surfaceState] : surfaces)
    {
        (void)surfaceId;
        surfaceState.screenDescriptorCache = {};
        surfaceState.backgroundDescriptorCache = {};
        surfaceState.backgroundDescriptorDirty = true;
    }
}

bool VulkanSurfacePresenter::createSurfaceStateResources(SurfaceState& surfaceState)
{
    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = queueFamilyIndex;

    if (vkCreateCommandPool(device, &commandPoolInfo, nullptr, &surfaceState.commandPool) != VK_SUCCESS)
        return false;

    VkCommandBufferAllocateInfo commandBufferInfo{};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = surfaceState.commandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = 1;

    if (vkAllocateCommandBuffers(device, &commandBufferInfo, &surfaceState.commandBuffer) != VK_SUCCESS)
        return false;

    if (!createInFlightFence(surfaceState, true))
        return false;

    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

    if (vkCreateSemaphore(device, &semaphoreInfo, nullptr, &surfaceState.imageAvailableSemaphore) != VK_SUCCESS
        || vkCreateSemaphore(device, &semaphoreInfo, nullptr, &surfaceState.renderFinishedSemaphore) != VK_SUCCESS)
        return false;

    VkDescriptorSetLayout layouts[] = {
        descriptorSetLayout,
        descriptorSetLayout,
    };
    VkDescriptorSetAllocateInfo descriptorSetAllocateInfo{};
    descriptorSetAllocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    descriptorSetAllocateInfo.descriptorPool = descriptorPool;
    descriptorSetAllocateInfo.descriptorSetCount = 2;
    descriptorSetAllocateInfo.pSetLayouts = layouts;

    VkDescriptorSet descriptorSets[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    if (vkAllocateDescriptorSets(device, &descriptorSetAllocateInfo, descriptorSets) != VK_SUCCESS)
        return false;

    surfaceState.screenDescriptorSet = descriptorSets[0];
    surfaceState.backgroundDescriptorSet = descriptorSets[1];

    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = kVertexBufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device, &bufferInfo, nullptr, &surfaceState.vertexBuffer) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memoryRequirements{};
    vkGetBufferMemoryRequirements(device, surfaceState.vertexBuffer, &memoryRequirements);

    VkMemoryAllocateInfo memoryInfo{};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.allocationSize = memoryRequirements.size;
    memoryInfo.memoryTypeIndex = findMemoryType(
        memoryRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    if (memoryInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(device, &memoryInfo, nullptr, &surfaceState.vertexMemory) != VK_SUCCESS
        || vkBindBufferMemory(device, surfaceState.vertexBuffer, surfaceState.vertexMemory, 0) != VK_SUCCESS)
        return false;

    surfaceState.vertexBufferSize = kVertexBufferSize;
    if (vkMapMemory(device, surfaceState.vertexMemory, 0, surfaceState.vertexBufferSize, 0, &surfaceState.mappedVertexMemory) != VK_SUCCESS)
        return false;

    (void)createTimestampQueryPool(surfaceState.timestampQueryPool);
    return true;
}

void VulkanSurfacePresenter::destroySurfaceStateResources(SurfaceState& surfaceState)
{
    destroyRetroArchResources(surfaceState);
    destroyBackgroundTexture(surfaceState);
    destroySwapchain(surfaceState);
    destroyInFlightFence(surfaceState);

    if (surfaceState.mappedVertexMemory != nullptr)
    {
        vkUnmapMemory(device, surfaceState.vertexMemory);
        surfaceState.mappedVertexMemory = nullptr;
    }

    if (surfaceState.vertexBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, surfaceState.vertexBuffer, nullptr);
    if (surfaceState.vertexMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, surfaceState.vertexMemory, nullptr);

    if (surfaceState.screenDescriptorSet != VK_NULL_HANDLE)
        vkFreeDescriptorSets(device, descriptorPool, 1, &surfaceState.screenDescriptorSet);
    if (surfaceState.backgroundDescriptorSet != VK_NULL_HANDLE)
        vkFreeDescriptorSets(device, descriptorPool, 1, &surfaceState.backgroundDescriptorSet);

    if (surfaceState.imageAvailableSemaphore != VK_NULL_HANDLE)
        vkDestroySemaphore(device, surfaceState.imageAvailableSemaphore, nullptr);
    if (surfaceState.renderFinishedSemaphore != VK_NULL_HANDLE)
        vkDestroySemaphore(device, surfaceState.renderFinishedSemaphore, nullptr);

    if (surfaceState.commandBuffer != VK_NULL_HANDLE && surfaceState.commandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(device, surfaceState.commandPool, 1, &surfaceState.commandBuffer);
    if (surfaceState.commandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(device, surfaceState.commandPool, nullptr);
    destroyTimestampQueryPool(surfaceState.timestampQueryPool);

    if (surfaceState.surface != VK_NULL_HANDLE)
        vkDestroySurfaceKHR(instance, surfaceState.surface, nullptr);

    if (surfaceState.window != nullptr)
        ANativeWindow_release(surfaceState.window);
}

bool VulkanSurfacePresenter::ensureSwapchain(SurfaceState& surfaceState)
{
    if (!surfaceState.swapchainDirty && surfaceState.swapchain != VK_NULL_HANDLE)
        return true;

    if (surfaceState.swapchain != VK_NULL_HANDLE && waitForSurfaceIdle(surfaceState) != VK_SUCCESS)
        return false;

    VkSurfaceCapabilitiesKHR capabilities{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surfaceState.surface, &capabilities) != VK_SUCCESS)
        return false;

    u32 formatCount = 0;
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceState.surface, &formatCount, nullptr) != VK_SUCCESS
        || formatCount == 0)
        return false;

    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    if (vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceState.surface, &formatCount, formats.data()) != VK_SUCCESS)
        return false;

    u32 presentModeCount = 0;
    if (vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surfaceState.surface, &presentModeCount, nullptr) != VK_SUCCESS
        || presentModeCount == 0)
        return false;

    std::vector<VkPresentModeKHR> presentModes(presentModeCount);
    if (vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surfaceState.surface, &presentModeCount, presentModes.data()) != VK_SUCCESS)
        return false;

    std::vector<VkSurfaceFormatKHR> rankedFormats = rankSurfaceFormats(formats);
    std::vector<VkPresentModeKHR> rankedPresentModes = rankPresentModes(presentModes);

    u32 width = surfaceState.requestedWidth > 0 ? surfaceState.requestedWidth : static_cast<u32>(std::max(1, ANativeWindow_getWidth(surfaceState.window)));
    u32 height = surfaceState.requestedHeight > 0 ? surfaceState.requestedHeight : static_cast<u32>(std::max(1, ANativeWindow_getHeight(surfaceState.window)));

    VkExtent2D extent{};
    if (capabilities.currentExtent.width != UINT32_MAX)
    {
        extent = capabilities.currentExtent;
    }
    else
    {
        extent.width = std::clamp(width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
        extent.height = std::clamp(height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }

    destroySwapchain(surfaceState);

    if (surfaceState.hasCachedSwapchainSelection)
    {
        auto cachedFormat = std::find_if(
            rankedFormats.begin(),
            rankedFormats.end(),
            [&](const VkSurfaceFormatKHR& format) {
                return format.format == surfaceState.cachedSurfaceFormat.format
                    && format.colorSpace == surfaceState.cachedSurfaceFormat.colorSpace;
            });
        if (cachedFormat != rankedFormats.end() && cachedFormat != rankedFormats.begin())
        {
            VkSurfaceFormatKHR cached = *cachedFormat;
            rankedFormats.erase(cachedFormat);
            rankedFormats.insert(rankedFormats.begin(), cached);
        }

        auto cachedPresentMode = std::find(
            rankedPresentModes.begin(),
            rankedPresentModes.end(),
            surfaceState.cachedPresentMode);
        if (cachedPresentMode != rankedPresentModes.end() && cachedPresentMode != rankedPresentModes.begin())
        {
            VkPresentModeKHR cached = *cachedPresentMode;
            rankedPresentModes.erase(cachedPresentMode);
            rankedPresentModes.insert(rankedPresentModes.begin(), cached);
        }
    }

    u32 imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0)
        imageCount = std::min(imageCount, capabilities.maxImageCount);

    VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(formats);
    VkPresentModeKHR presentMode = choosePresentMode(presentModes);
    auto failSwapchainConfig = [&](const char* stage, VkResult result) -> bool {
        const u64 configKey = makeSwapchainConfigKey(
            surfaceState.id,
            surfaceFormat.format,
            surfaceFormat.colorSpace,
            presentMode
        );
        failedSwapchainConfigs.insert(configKey);
        if (loggedFailedSwapchainConfigs.insert(configKey).second)
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "VulkanSurfacePresenter: rejected swapchain config after %s surface=%d format=%d colorspace=%d presentMode=%d result=%d",
                stage != nullptr ? stage : "unknown",
                surfaceState.id,
                static_cast<int>(surfaceFormat.format),
                static_cast<int>(surfaceFormat.colorSpace),
                static_cast<int>(presentMode),
                static_cast<int>(result)
            );
        }

        destroySwapchain(surfaceState);
        surfaceState.swapchainDirty = true;
        return false;
    };
    const VkSurfaceTransformFlagBitsKHR preTransform =
        (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            : capabilities.currentTransform;
    bool swapchainCreated = false;

    for (const VkPresentModeKHR candidatePresentMode : rankedPresentModes)
    {
        for (const VkSurfaceFormatKHR candidateFormat : rankedFormats)
        {
            const u64 configKey = makeSwapchainConfigKey(
                surfaceState.id,
                candidateFormat.format,
                candidateFormat.colorSpace,
                candidatePresentMode
            );
            if (failedSwapchainConfigs.find(configKey) != failedSwapchainConfigs.end())
                continue;

            VkSwapchainCreateInfoKHR swapchainInfo{};
            swapchainInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
            swapchainInfo.surface = surfaceState.surface;
            swapchainInfo.minImageCount = imageCount;
            swapchainInfo.imageFormat = candidateFormat.format;
            swapchainInfo.imageColorSpace = candidateFormat.colorSpace;
            swapchainInfo.imageExtent = extent;
            swapchainInfo.imageArrayLayers = 1;
            swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
            swapchainInfo.preTransform = preTransform;
            swapchainInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            swapchainInfo.presentMode = candidatePresentMode;
            swapchainInfo.clipped = VK_TRUE;
            swapchainInfo.oldSwapchain = VK_NULL_HANDLE;

            const VkResult swapchainResult = vkCreateSwapchainKHR(device, &swapchainInfo, nullptr, &surfaceState.swapchain);
            if (swapchainResult == VK_SUCCESS)
            {
                surfaceFormat = candidateFormat;
                presentMode = candidatePresentMode;
                swapchainCreated = true;
                break;
            }

            failedSwapchainConfigs.insert(configKey);
            if (loggedFailedSwapchainConfigs.insert(configKey).second)
            {
                melonDS::Platform::Log(
                    melonDS::Platform::LogLevel::Warn,
                    "VulkanSurfacePresenter: rejected swapchain config surface=%d format=%d colorspace=%d presentMode=%d result=%d",
                    surfaceState.id,
                    static_cast<int>(candidateFormat.format),
                    static_cast<int>(candidateFormat.colorSpace),
                    static_cast<int>(candidatePresentMode),
                    static_cast<int>(swapchainResult)
                );
            }
        }

        if (swapchainCreated)
            break;
    }

    if (!swapchainCreated)
        return false;

    VkAttachmentDescription attachmentDescription{};
    attachmentDescription.format = surfaceFormat.format;
    attachmentDescription.samples = VK_SAMPLE_COUNT_1_BIT;
    attachmentDescription.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachmentDescription.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachmentDescription.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachmentDescription.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference attachmentReference{};
    attachmentReference.attachment = 0;
    attachmentReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpassDescription{};
    subpassDescription.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpassDescription.colorAttachmentCount = 1;
    subpassDescription.pColorAttachments = &attachmentReference;

    VkSubpassDependency subpassDependency{};
    subpassDependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    subpassDependency.dstSubpass = 0;
    subpassDependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    subpassDependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    subpassDependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &attachmentDescription;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpassDescription;
    renderPassInfo.dependencyCount = 1;
    renderPassInfo.pDependencies = &subpassDependency;

    const VkResult createRenderPassResult = vkCreateRenderPass(device, &renderPassInfo, nullptr, &surfaceState.renderPass);
    if (createRenderPassResult != VK_SUCCESS)
        return failSwapchainConfig("vkCreateRenderPass", createRenderPassResult);

    const bool swapchainSelectionChanged =
        !surfaceState.hasCachedSwapchainSelection
        || surfaceState.cachedSurfaceFormat.format != surfaceFormat.format
        || surfaceState.cachedSurfaceFormat.colorSpace != surfaceFormat.colorSpace
        || surfaceState.cachedPresentMode != presentMode
        || surfaceState.extent.width != extent.width
        || surfaceState.extent.height != extent.height;
    if (swapchainSelectionChanged && MelonDSAndroid::areRendererDebugBgObjLogsEnabled())
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Info,
            "VulkanSurfacePresenter: creating swapchain for surface %d format=%d colorspace=%d presentMode=%d extent=%ux%u images=%u preTransform=%d currentTransform=%d",
            surfaceState.id,
            static_cast<int>(surfaceFormat.format),
            static_cast<int>(surfaceFormat.colorSpace),
            static_cast<int>(presentMode),
            extent.width,
            extent.height,
            imageCount,
            static_cast<int>(preTransform),
            static_cast<int>(capabilities.currentTransform)
        );
    }

    u32 swapchainImageCount = 0;
    const VkResult getSwapchainImageCountResult = vkGetSwapchainImagesKHR(
        device,
        surfaceState.swapchain,
        &swapchainImageCount,
        nullptr
    );
    if (getSwapchainImageCountResult != VK_SUCCESS || swapchainImageCount == 0)
    {
        const VkResult failureResult = getSwapchainImageCountResult != VK_SUCCESS
            ? getSwapchainImageCountResult
            : VK_ERROR_FORMAT_NOT_SUPPORTED;
        return failSwapchainConfig("vkGetSwapchainImagesKHR(count)", failureResult);
    }

    surfaceState.swapchainImages.resize(swapchainImageCount);
    const VkResult getSwapchainImagesResult = vkGetSwapchainImagesKHR(
        device,
        surfaceState.swapchain,
        &swapchainImageCount,
        surfaceState.swapchainImages.data()
    );
    if (getSwapchainImagesResult != VK_SUCCESS)
        return failSwapchainConfig("vkGetSwapchainImagesKHR(images)", getSwapchainImagesResult);

    surfaceState.swapchainImageViews.resize(swapchainImageCount);
    surfaceState.framebuffers.resize(swapchainImageCount);

    for (u32 i = 0; i < swapchainImageCount; i++)
    {
        VkImageViewCreateInfo imageViewInfo{};
        imageViewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        imageViewInfo.image = surfaceState.swapchainImages[i];
        imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        imageViewInfo.format = surfaceFormat.format;
        imageViewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        imageViewInfo.subresourceRange.levelCount = 1;
        imageViewInfo.subresourceRange.layerCount = 1;

        const VkResult createImageViewResult = vkCreateImageView(device, &imageViewInfo, nullptr, &surfaceState.swapchainImageViews[i]);
        if (createImageViewResult != VK_SUCCESS)
            return failSwapchainConfig("vkCreateImageView", createImageViewResult);
    }

    VkPipelineShaderStageCreateInfo shaderStages[2]{};
    shaderStages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    shaderStages[0].module = vertexShaderModule;
    shaderStages[0].pName = "main";
    shaderStages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    shaderStages[1].module = fragmentShaderModule;
    shaderStages[1].pName = "main";

    VkVertexInputBindingDescription bindingDescription{};
    bindingDescription.binding = 0;
    bindingDescription.stride = sizeof(SurfaceVertex);
    bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    std::array<VkVertexInputAttributeDescription, 3> attributeDescriptions{};
    attributeDescriptions[0].location = 0;
    attributeDescriptions[0].binding = 0;
    attributeDescriptions[0].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[0].offset = offsetof(SurfaceVertex, x);
    attributeDescriptions[1].location = 1;
    attributeDescriptions[1].binding = 0;
    attributeDescriptions[1].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[1].offset = offsetof(SurfaceVertex, u);
    attributeDescriptions[2].location = 2;
    attributeDescriptions[2].binding = 0;
    attributeDescriptions[2].format = VK_FORMAT_R32_SFLOAT;
    attributeDescriptions[2].offset = offsetof(SurfaceVertex, alpha);

    VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
    vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInputInfo.vertexBindingDescriptionCount = 1;
    vertexInputInfo.pVertexBindingDescriptions = &bindingDescription;
    vertexInputInfo.vertexAttributeDescriptionCount = static_cast<u32>(attributeDescriptions.size());
    vertexInputInfo.pVertexAttributeDescriptions = attributeDescriptions.data();

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterizer{};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo multisampling{};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState colorBlendAttachment{};
    colorBlendAttachment.colorWriteMask =
        VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    colorBlendAttachment.blendEnable = VK_TRUE;
    colorBlendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    colorBlendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    colorBlendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
    colorBlendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    colorBlendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    colorBlendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;

    VkPipelineColorBlendStateCreateInfo colorBlending{};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorBlendAttachment;

    std::array<VkDynamicState, 2> dynamicStates = {
        VK_DYNAMIC_STATE_VIEWPORT,
        VK_DYNAMIC_STATE_SCISSOR,
    };
    VkPipelineDynamicStateCreateInfo dynamicState{};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = static_cast<u32>(dynamicStates.size());
    dynamicState.pDynamicStates = dynamicStates.data();

    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = shaderStages;
    pipelineInfo.pVertexInputState = &vertexInputInfo;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = pipelineLayout;
    pipelineInfo.renderPass = surfaceState.renderPass;
    pipelineInfo.subpass = 0;

    const VkResult createPipelineResult = vkCreateGraphicsPipelines(
        device,
        VK_NULL_HANDLE,
        1,
        &pipelineInfo,
        nullptr,
        &surfaceState.pipeline
    );
    if (createPipelineResult != VK_SUCCESS)
        return failSwapchainConfig("vkCreateGraphicsPipelines", createPipelineResult);

    for (u32 i = 0; i < swapchainImageCount; i++)
    {
        VkImageView attachments[] = {surfaceState.swapchainImageViews[i]};

        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = surfaceState.renderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = attachments;
        framebufferInfo.width = extent.width;
        framebufferInfo.height = extent.height;
        framebufferInfo.layers = 1;

        const VkResult createFramebufferResult = vkCreateFramebuffer(
            device,
            &framebufferInfo,
            nullptr,
            &surfaceState.framebuffers[i]
        );
        if (createFramebufferResult != VK_SUCCESS)
            return failSwapchainConfig("vkCreateFramebuffer", createFramebufferResult);
    }

    const bool extentChanged = surfaceState.extent.width != extent.width || surfaceState.extent.height != extent.height;
    surfaceState.swapchainFormat = surfaceFormat.format;
    surfaceState.colorSpace = surfaceFormat.colorSpace;
    surfaceState.presentMode = presentMode;
    surfaceState.hasCachedSwapchainSelection = true;
    surfaceState.cachedSurfaceFormat = surfaceFormat;
    surfaceState.cachedPresentMode = presentMode;
    surfaceState.extent = extent;
    surfaceState.swapchainDirty = false;
    if (extentChanged)
        surfaceState.vertexBufferDirty = true;
    return true;
}

void VulkanSurfacePresenter::destroySwapchain(SurfaceState& surfaceState)
{
    for (VkFramebuffer framebuffer : surfaceState.framebuffers)
    {
        if (framebuffer != VK_NULL_HANDLE)
            vkDestroyFramebuffer(device, framebuffer, nullptr);
    }
    surfaceState.framebuffers.clear();

    for (VkImageView imageView : surfaceState.swapchainImageViews)
    {
        if (imageView != VK_NULL_HANDLE)
            vkDestroyImageView(device, imageView, nullptr);
    }
    surfaceState.swapchainImageViews.clear();
    surfaceState.swapchainImages.clear();

    if (surfaceState.pipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(device, surfaceState.pipeline, nullptr);
        surfaceState.pipeline = VK_NULL_HANDLE;
    }

    if (surfaceState.renderPass != VK_NULL_HANDLE)
    {
        vkDestroyRenderPass(device, surfaceState.renderPass, nullptr);
        surfaceState.renderPass = VK_NULL_HANDLE;
    }

    if (surfaceState.swapchain != VK_NULL_HANDLE)
    {
        vkDestroySwapchainKHR(device, surfaceState.swapchain, nullptr);
        surfaceState.swapchain = VK_NULL_HANDLE;
    }
}

void VulkanSurfacePresenter::recoverSwapchain(SurfaceState& surfaceState, const char* reason)
{
    swapchainRecoveries++;
    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Error,
        "VulkanSurfacePresenter: recovering swapchain for surface %d after %s",
        surfaceState.id,
        reason != nullptr ? reason : "unknown error"
    );

    (void)waitForSurfaceIdle(surfaceState);

    destroySwapchain(surfaceState);
    surfaceState.swapchainDirty = true;
}

bool VulkanSurfacePresenter::createInFlightFence(SurfaceState& surfaceState, bool signaled)
{
    destroyInFlightFence(surfaceState);

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = signaled ? VK_FENCE_CREATE_SIGNALED_BIT : 0;

    return vkCreateFence(device, &fenceInfo, nullptr, &surfaceState.inFlightFence) == VK_SUCCESS;
}

void VulkanSurfacePresenter::destroyInFlightFence(SurfaceState& surfaceState)
{
    if (surfaceState.inFlightFence != VK_NULL_HANDLE)
    {
        vkDestroyFence(device, surfaceState.inFlightFence, nullptr);
        surfaceState.inFlightFence = VK_NULL_HANDLE;
    }
}

VkResult VulkanSurfacePresenter::waitForSurfaceIdle(SurfaceState& surfaceState, u64 timeoutNs)
{
    if (surfaceState.inFlightFence == VK_NULL_HANDLE)
        return VK_SUCCESS;

    const u64 waitStartNs = PerfNowNs();
    const VkResult waitResult = vkWaitForFences(device, 1, &surfaceState.inFlightFence, VK_TRUE, timeoutNs);
    if (waitResult == VK_SUCCESS)
    {
        waitCpuWindow.Add(PerfNowNs() - waitStartNs);
        consumeSurfaceGpuTiming(surfaceState);
    }

    return waitResult;
}

bool VulkanSurfacePresenter::resetSurfaceInFlightFence(SurfaceState& surfaceState)
{
    if (surfaceState.inFlightFence == VK_NULL_HANDLE)
        return false;

    if (vkResetFences(device, 1, &surfaceState.inFlightFence) == VK_SUCCESS)
        return true;

    return createInFlightFence(surfaceState, true);
}

void VulkanSurfacePresenter::consumeSurfaceGpuTiming(SurfaceState& surfaceState)
{
    if (!surfaceState.timestampPending || surfaceState.timestampQueryPool == VK_NULL_HANDLE || timestampPeriodNs <= 0.0f)
        return;

    u64 timestamps[2]{};
    const VkResult queryResult = vkGetQueryPoolResults(
        device,
        surfaceState.timestampQueryPool,
        0,
        2,
        sizeof(timestamps),
        timestamps,
        sizeof(u64),
        VK_QUERY_RESULT_64_BIT
    );
    if (queryResult == VK_SUCCESS && timestamps[1] >= timestamps[0])
    {
        const u64 gpuTimeNs = static_cast<u64>(static_cast<double>(timestamps[1] - timestamps[0]) * static_cast<double>(timestampPeriodNs));
        presentGpuWindow.Add(gpuTimeNs);
    }

    surfaceState.timestampPending = false;
}

void VulkanSurfacePresenter::logPerformanceIfNeeded()
{
    if (!areRendererDebugBgObjLogsEnabled())
        return;

    if (!frameWallCpuWindow.Ready())
        return;

    const PerfSampleWindow<120>::Summary frameWallSummary = frameWallCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary descriptorSummary = descriptorCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary vertexSummary = vertexCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary waitSummary = waitCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary acquireSummary = acquireCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary recordSummary = recordCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary submitSummary = submitCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary presentSummary = presentCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary gpuSummary = presentGpuWindow.SummarizeAndReset();

    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Warn,
        "VulkanPerf[Presenter]: mode=%s frame wall avg=%.3fms p95=%.3fms max=%.3fms wait avg=%.3fms p95=%.3fms max=%.3fms acquire avg=%.3fms p95=%.3fms max=%.3fms desc avg=%.3fms vertex avg=%.3fms record avg=%.3fms submit avg=%.3fms present avg=%.3fms gpu avg=%.3fms p95=%.3fms max=%.3fms presented=%llu direct=%llu fallback=%llu skippedWait=%llu acquireTimeouts=%llu deadlineSkipped=%llu recoveries=%llu presentMode=%d swapchainImages=%u reasons(needsReadback=%llu validation=%llu missingHandles=%llu surfaceCount=%llu) fail(frameWait=%llu composeSubmit=%llu composeWait=%llu missingImage=%llu noConfigured=%llu swapchain=%llu surfaceWait=%llu descriptor=%llu vertex=%llu acquire=%llu record=%llu submit=%llu)",
        lastPresentedDirect ? "direct" : "fallback",
        PerfNsToMs(frameWallSummary.MeanNs),
        PerfNsToMs(frameWallSummary.P95Ns),
        PerfNsToMs(frameWallSummary.MaxNs),
        PerfNsToMs(waitSummary.MeanNs),
        PerfNsToMs(waitSummary.P95Ns),
        PerfNsToMs(waitSummary.MaxNs),
        PerfNsToMs(acquireSummary.MeanNs),
        PerfNsToMs(acquireSummary.P95Ns),
        PerfNsToMs(acquireSummary.MaxNs),
        PerfNsToMs(descriptorSummary.MeanNs),
        PerfNsToMs(vertexSummary.MeanNs),
        PerfNsToMs(recordSummary.MeanNs),
        PerfNsToMs(submitSummary.MeanNs),
        PerfNsToMs(presentSummary.MeanNs),
        PerfNsToMs(gpuSummary.MeanNs),
        PerfNsToMs(gpuSummary.P95Ns),
        PerfNsToMs(gpuSummary.MaxNs),
        static_cast<unsigned long long>(presentedFrames),
        static_cast<unsigned long long>(directPresentedFrames),
        static_cast<unsigned long long>(fallbackPresentedFrames),
        static_cast<unsigned long long>(skippedSurfaceWaits),
        static_cast<unsigned long long>(acquireTimeouts),
        static_cast<unsigned long long>(presentSkippedForDeadline),
        static_cast<unsigned long long>(swapchainRecoveries),
        static_cast<int>(lastPresentMode),
        lastSwapchainImageCount,
        static_cast<unsigned long long>(fallbackReasonNeedsReadback),
        static_cast<unsigned long long>(fallbackReasonValidationMode),
        static_cast<unsigned long long>(fallbackReasonMissingHandles),
        static_cast<unsigned long long>(fallbackReasonSurfaceCount),
        static_cast<unsigned long long>(frameWaitFailures),
        static_cast<unsigned long long>(composeSubmitFailures),
        static_cast<unsigned long long>(composeWaitFailures),
        static_cast<unsigned long long>(missingFrameImageFailures),
        static_cast<unsigned long long>(noConfiguredSurfaceFrames),
        static_cast<unsigned long long>(swapchainUnavailableFrames),
        static_cast<unsigned long long>(surfaceWaitFailures),
        static_cast<unsigned long long>(descriptorUpdateFailures),
        static_cast<unsigned long long>(vertexUpdateFailures),
        static_cast<unsigned long long>(acquireFailures),
        static_cast<unsigned long long>(recordFailures),
        static_cast<unsigned long long>(submitFailures)
    );

    fallbackReasonNeedsReadback = 0;
    fallbackReasonValidationMode = 0;
    fallbackReasonMissingHandles = 0;
    fallbackReasonSurfaceCount = 0;
    frameWaitFailures = 0;
    composeSubmitFailures = 0;
    composeWaitFailures = 0;
    missingFrameImageFailures = 0;
    noConfiguredSurfaceFrames = 0;
    swapchainUnavailableFrames = 0;
    surfaceWaitFailures = 0;
    descriptorUpdateFailures = 0;
    vertexUpdateFailures = 0;
    acquireFailures = 0;
    recordFailures = 0;
    submitFailures = 0;
}

bool VulkanSurfacePresenter::ensureBackgroundTexture(SurfaceState& surfaceState, const VulkanBackgroundImage& backgroundImage)
{
    destroyBackgroundTexture(surfaceState);
    const bool created = createTextureFromPixels(surfaceState.background, backgroundImage);
    surfaceState.vertexBufferDirty = true;
    surfaceState.backgroundDescriptorDirty = true;
    return created;
}

void VulkanSurfacePresenter::destroyBackgroundTexture(SurfaceState& surfaceState)
{
    if (surfaceState.background.imageView != VK_NULL_HANDLE)
        vkDestroyImageView(device, surfaceState.background.imageView, nullptr);
    if (surfaceState.background.image != VK_NULL_HANDLE)
        vkDestroyImage(device, surfaceState.background.image, nullptr);
    if (surfaceState.background.memory != VK_NULL_HANDLE)
        vkFreeMemory(device, surfaceState.background.memory, nullptr);

    surfaceState.background = BackgroundResource{};
    surfaceState.vertexBufferDirty = true;
    surfaceState.backgroundDescriptorDirty = true;
    surfaceState.backgroundDescriptorCache.ready = false;
}

bool VulkanSurfacePresenter::createRetroArchImage(RetroArchImageResource& resource, u32 width, u32 height)
{
    destroyRetroArchImage(resource);
    if (width == 0 || height == 0)
        return false;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT
        | VK_IMAGE_USAGE_TRANSFER_DST_BIT
        | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
        | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(device, &imageInfo, nullptr, &resource.image) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memoryRequirements{};
    vkGetImageMemoryRequirements(device, resource.image, &memoryRequirements);

    VkMemoryAllocateInfo memoryInfo{};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.allocationSize = memoryRequirements.size;
    memoryInfo.memoryTypeIndex = findMemoryType(memoryRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(device, &memoryInfo, nullptr, &resource.memory) != VK_SUCCESS
        || vkBindImageMemory(device, resource.image, resource.memory, 0) != VK_SUCCESS)
    {
        destroyRetroArchImage(resource);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = resource.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(device, &viewInfo, nullptr, &resource.imageView) != VK_SUCCESS)
    {
        destroyRetroArchImage(resource);
        return false;
    }

    resource.width = width;
    resource.height = height;
    resource.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    return true;
}

void VulkanSurfacePresenter::destroyRetroArchImage(RetroArchImageResource& resource)
{
    if (resource.imageView != VK_NULL_HANDLE)
        vkDestroyImageView(device, resource.imageView, nullptr);
    if (resource.image != VK_NULL_HANDLE)
        vkDestroyImage(device, resource.image, nullptr);
    if (resource.memory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.memory, nullptr);
    resource = {};
}

VulkanSurfacePresenter::RetroArchSizing VulkanSurfacePresenter::calculateRetroArchSizing(
    const SurfaceState& surfaceState,
    u32 atlasWidth,
    u32 atlasHeight) const
{
    RetroArchSizing sizing{};
    sizing.nativeDisplayMode = surfaceState.config.retroShaderSourceResolution == RetroArchSourceResolution::Native;
    sizing.inputScale = std::max(1u, atlasWidth / kNativeScreenWidth);
    sizing.inputScreenWidth = kNativeScreenWidth * sizing.inputScale;
    sizing.inputScreenHeight = kNativeScreenHeight * sizing.inputScale;
    sizing.inputBottomOffsetY = atlasHeight > sizing.inputScreenHeight
        ? atlasHeight - sizing.inputScreenHeight
        : 0u;

    auto includeRect = [&](const VulkanPresenterRect& rect, float alpha) {
        if (!rect.enabled || rect.width <= 0 || rect.height <= 0 || alpha <= 0.0f)
            return;

        sizing.maxLayoutWidth = std::max(sizing.maxLayoutWidth, static_cast<u32>(rect.width));
        sizing.maxLayoutHeight = std::max(sizing.maxLayoutHeight, static_cast<u32>(rect.height));
    };

    includeRect(surfaceState.config.topScreen, surfaceState.config.topAlpha);
    includeRect(surfaceState.config.bottomScreen, surfaceState.config.bottomAlpha);
    includeRect(surfaceState.config.hybridTopScreen, surfaceState.config.hybridAlpha);
    includeRect(surfaceState.config.hybridBottomScreen, surfaceState.config.hybridAlpha);

    auto ceilDiv = [](u32 value, u32 divisor) -> u32 {
        if (value == 0 || divisor == 0)
            return 0;
        return (value + divisor - 1u) / divisor;
    };

    u32 requestedOutputScale = sizing.inputScale;
    if (sizing.nativeDisplayMode)
    {
        const u32 layoutScale = std::max(
            ceilDiv(sizing.maxLayoutWidth, kNativeScreenWidth),
            ceilDiv(sizing.maxLayoutHeight, kNativeScreenHeight));
        requestedOutputScale = std::max(sizing.inputScale, std::max(1u, layoutScale));
    }

    const u32 maxAllowedScale = std::max(sizing.inputScale, kMaxRetroArchNativeDisplayScale);
    sizing.requestedOutputScale = requestedOutputScale;
    sizing.outputScale = std::min(requestedOutputScale, maxAllowedScale);
    sizing.clamped = sizing.outputScale != requestedOutputScale;

    sizing.sourceScreenWidth = sizing.nativeDisplayMode ? kNativeScreenWidth : sizing.inputScreenWidth;
    sizing.sourceScreenHeight = sizing.nativeDisplayMode ? kNativeScreenHeight : sizing.inputScreenHeight;
    sizing.outputScreenWidth = kNativeScreenWidth * sizing.outputScale;
    sizing.outputScreenHeight = kNativeScreenHeight * sizing.outputScale;
    sizing.outputAtlasWidth = sizing.outputScreenWidth;
    sizing.outputAtlasHeight = kNativeAtlasHeight * sizing.outputScale;
    sizing.outputBottomOffsetY = sizing.outputAtlasHeight > sizing.outputScreenHeight
        ? sizing.outputAtlasHeight - sizing.outputScreenHeight
        : 0u;

    return sizing;
}

void VulkanSurfacePresenter::logRetroArchSizingIfNeeded(
    SurfaceState& surfaceState,
    const RetroArchSizing& sizing,
    u32 atlasWidth,
    u32 atlasHeight)
{
    RetroArchResources& retro = surfaceState.retroArch;
    std::string logKey = surfaceState.config.retroShaderPresetPath
        + "|mode="
        + (sizing.nativeDisplayMode ? "native_display" : "vulkan_ir")
        + "|source="
        + std::to_string(sizing.sourceScreenWidth)
        + "x"
        + std::to_string(sizing.sourceScreenHeight)
        + "|inputAtlas="
        + std::to_string(atlasWidth)
        + "x"
        + std::to_string(atlasHeight)
        + "|output="
        + std::to_string(sizing.outputScreenWidth)
        + "x"
        + std::to_string(sizing.outputScreenHeight)
        + "|outputAtlas="
        + std::to_string(sizing.outputAtlasWidth)
        + "x"
        + std::to_string(sizing.outputAtlasHeight)
        + "|scale="
        + std::to_string(sizing.outputScale);
    if (retro.lastSizingLogKey == logKey)
        return;

    retro.lastSizingLogKey = std::move(logKey);
    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Info,
        "VulkanPresenter[RetroArchSizing]: preset=%s mode=%s source=%ux%u inputAtlas=%ux%u inputScreen=%ux%u outputScreen=%ux%u outputAtlas=%ux%u layoutMax=%ux%u scale=%u requestedScale=%u clamped=%d surface=%ux%u passes=%u",
        surfaceState.config.retroShaderPresetPath.c_str(),
        sizing.nativeDisplayMode ? "native_display" : "vulkan_ir",
        sizing.sourceScreenWidth,
        sizing.sourceScreenHeight,
        atlasWidth,
        atlasHeight,
        sizing.inputScreenWidth,
        sizing.inputScreenHeight,
        sizing.outputScreenWidth,
        sizing.outputScreenHeight,
        sizing.outputAtlasWidth,
        sizing.outputAtlasHeight,
        sizing.maxLayoutWidth,
        sizing.maxLayoutHeight,
        sizing.outputScale,
        sizing.requestedOutputScale,
        sizing.clamped ? 1 : 0,
        surfaceState.extent.width,
        surfaceState.extent.height,
        surfaceState.config.retroShaderPassCount);
}

bool VulkanSurfacePresenter::ensureRetroArchResources(
    SurfaceState& surfaceState,
    u32 sourceScreenWidth,
    u32 sourceScreenHeight,
    u32 outputScreenWidth,
    u32 outputScreenHeight,
    u32 outputAtlasWidth,
    u32 outputAtlasHeight)
{
    RetroArchResources& retro = surfaceState.retroArch;
    const bool sizeMatches =
        retro.topInput.width == sourceScreenWidth && retro.topInput.height == sourceScreenHeight
        && retro.bottomInput.width == sourceScreenWidth && retro.bottomInput.height == sourceScreenHeight
        && retro.topOutput.width == outputScreenWidth && retro.topOutput.height == outputScreenHeight
        && retro.bottomOutput.width == outputScreenWidth && retro.bottomOutput.height == outputScreenHeight
        && retro.atlasOutput.width == outputAtlasWidth && retro.atlasOutput.height == outputAtlasHeight;

    if (retro.initialized && sizeMatches)
        return true;

    destroyRetroArchResources(surfaceState);

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamilyIndex;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &retro.commandPool) != VK_SUCCESS)
        return false;

    VkCommandBufferAllocateInfo commandBufferInfo{};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = retro.commandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &commandBufferInfo, &retro.commandBuffer) != VK_SUCCESS)
        return false;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &retro.fence) != VK_SUCCESS)
        return false;

    if (!createRetroArchImage(retro.topInput, sourceScreenWidth, sourceScreenHeight)
        || !createRetroArchImage(retro.bottomInput, sourceScreenWidth, sourceScreenHeight)
        || !createRetroArchImage(retro.topOutput, outputScreenWidth, outputScreenHeight)
        || !createRetroArchImage(retro.bottomOutput, outputScreenWidth, outputScreenHeight)
        || !createRetroArchImage(retro.atlasOutput, outputAtlasWidth, outputAtlasHeight))
    {
        destroyRetroArchResources(surfaceState);
        return false;
    }

    retro.initialized = true;
    return true;
}

void VulkanSurfacePresenter::destroyRetroArchResources(SurfaceState& surfaceState)
{
    RetroArchResources& retro = surfaceState.retroArch;
    retro.topChain.shutdown();
    retro.bottomChain.shutdown();
    destroyRetroArchImage(retro.topInput);
    destroyRetroArchImage(retro.bottomInput);
    destroyRetroArchImage(retro.topOutput);
    destroyRetroArchImage(retro.bottomOutput);
    destroyRetroArchImage(retro.atlasOutput);

    if (retro.commandBuffer != VK_NULL_HANDLE && retro.commandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(device, retro.commandPool, 1, &retro.commandBuffer);
    if (retro.fence != VK_NULL_HANDLE)
        vkDestroyFence(device, retro.fence, nullptr);
    if (retro.commandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(device, retro.commandPool, nullptr);
    retro = {};
}

bool VulkanSurfacePresenter::runRetroArchFilter(
    SurfaceState& surfaceState,
    VkImage sourceAtlasImage,
    VkImageView sourceAtlasImageView,
    u32 atlasWidth,
    u32 atlasHeight,
    VkImage& outputImage,
    VkImageView& outputImageView)
{
    (void)sourceAtlasImageView;
    outputImage = VK_NULL_HANDLE;
    outputImageView = VK_NULL_HANDLE;

    if (sourceAtlasImage == VK_NULL_HANDLE
        || atlasWidth == 0
        || atlasHeight == 0
        || !surfaceState.config.retroShaderEnabled
        || surfaceState.config.retroShaderPresetPath.empty())
    {
        return false;
    }

    const RetroArchSizing sizing = calculateRetroArchSizing(surfaceState, atlasWidth, atlasHeight);
    if (sizing.sourceScreenWidth == 0
        || sizing.sourceScreenHeight == 0
        || sizing.inputScreenWidth == 0
        || sizing.inputScreenHeight == 0
        || sizing.outputScreenWidth == 0
        || sizing.outputScreenHeight == 0
        || sizing.outputAtlasWidth == 0
        || sizing.outputAtlasHeight == 0
        || sizing.inputScreenWidth > atlasWidth
        || sizing.inputScreenHeight > atlasHeight)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanPresenter[RetroArchSizing]: invalid dimensions preset=%s mode=%s source=%ux%u inputAtlas=%ux%u inputScreen=%ux%u outputScreen=%ux%u outputAtlas=%ux%u surface=%ux%u",
            surfaceState.config.retroShaderPresetPath.c_str(),
            sizing.nativeDisplayMode ? "native_display" : "vulkan_ir",
            sizing.sourceScreenWidth,
            sizing.sourceScreenHeight,
            atlasWidth,
            atlasHeight,
            sizing.inputScreenWidth,
            sizing.inputScreenHeight,
            sizing.outputScreenWidth,
            sizing.outputScreenHeight,
            sizing.outputAtlasWidth,
            sizing.outputAtlasHeight,
            surfaceState.extent.width,
            surfaceState.extent.height);
        return false;
    }

    if (!ensureRetroArchResources(
            surfaceState,
            sizing.sourceScreenWidth,
            sizing.sourceScreenHeight,
            sizing.outputScreenWidth,
            sizing.outputScreenHeight,
            sizing.outputAtlasWidth,
            sizing.outputAtlasHeight))
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanPresenter[RetroArchSizing]: resource allocation failed preset=%s mode=%s source=%ux%u inputAtlas=%ux%u inputScreen=%ux%u outputScreen=%ux%u outputAtlas=%ux%u surface=%ux%u",
            surfaceState.config.retroShaderPresetPath.c_str(),
            sizing.nativeDisplayMode ? "native_display" : "vulkan_ir",
            sizing.sourceScreenWidth,
            sizing.sourceScreenHeight,
            atlasWidth,
            atlasHeight,
            sizing.inputScreenWidth,
            sizing.inputScreenHeight,
            sizing.outputScreenWidth,
            sizing.outputScreenHeight,
            sizing.outputAtlasWidth,
            sizing.outputAtlasHeight,
            surfaceState.extent.width,
            surfaceState.extent.height);
        return false;
    }

    RetroArchResources& retro = surfaceState.retroArch;
    logRetroArchSizingIfNeeded(surfaceState, sizing, atlasWidth, atlasHeight);
    std::string configKey = makeRetroArchConfigKey(
        surfaceState.config,
        sizing.sourceScreenWidth,
        sizing.sourceScreenHeight,
        sizing.outputScreenWidth,
        sizing.outputScreenHeight);
    if (retro.failedConfigKey == configKey)
        return false;

    const bool chainConfigMatches =
        retro.topChain.getPresetPath() == surfaceState.config.retroShaderPresetPath
        && retro.topChain.getSourceWidth() == sizing.sourceScreenWidth
        && retro.topChain.getSourceHeight() == sizing.sourceScreenHeight
        && retro.topChain.getOutputWidth() == sizing.outputScreenWidth
        && retro.topChain.getOutputHeight() == sizing.outputScreenHeight
        && retro.topChain.getParameterOverrides() == surfaceState.config.retroShaderParameterOverrides
        && retro.bottomChain.getPresetPath() == surfaceState.config.retroShaderPresetPath
        && retro.bottomChain.getSourceWidth() == sizing.sourceScreenWidth
        && retro.bottomChain.getSourceHeight() == sizing.sourceScreenHeight
        && retro.bottomChain.getOutputWidth() == sizing.outputScreenWidth
        && retro.bottomChain.getOutputHeight() == sizing.outputScreenHeight
        && retro.bottomChain.getParameterOverrides() == surfaceState.config.retroShaderParameterOverrides;
    if (!chainConfigMatches)
    {
        retro.topChain.shutdown();
        retro.bottomChain.shutdown();
    }

    const bool consumedPrewarmedChains = !chainConfigMatches
        && takePrewarmedRetroArchChains(configKey, retro.topChain, retro.bottomChain);
    if (!consumedPrewarmedChains
        && (!retro.topChain.configure(
                surfaceState.config.retroShaderPresetPath,
                sizing.sourceScreenWidth,
                sizing.sourceScreenHeight,
                sizing.outputScreenWidth,
                sizing.outputScreenHeight,
                surfaceState.config.retroShaderParameterOverrides)
            || !retro.bottomChain.configure(
                surfaceState.config.retroShaderPresetPath,
                sizing.sourceScreenWidth,
                sizing.sourceScreenHeight,
                sizing.outputScreenWidth,
                sizing.outputScreenHeight,
                surfaceState.config.retroShaderParameterOverrides)))
    {
        retro.topChain.shutdown();
        retro.bottomChain.shutdown();
        retro.failedConfigKey = std::move(configKey);
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanPresenter[RetroArch]: preset failed to compile/load; preset=%s mode=%s source=%ux%u output=%ux%u outputAtlas=%ux%u passes=%u params=%zu; presenting unfiltered until config changes",
            surfaceState.config.retroShaderPresetPath.c_str(),
            sizing.nativeDisplayMode ? "native_display" : "vulkan_ir",
            sizing.sourceScreenWidth,
            sizing.sourceScreenHeight,
            sizing.outputScreenWidth,
            sizing.outputScreenHeight,
            sizing.outputAtlasWidth,
            sizing.outputAtlasHeight,
            surfaceState.config.retroShaderPassCount,
            surfaceState.config.retroShaderParameterOverrides.size()
        );
        return false;
    }
    retro.failedConfigKey.clear();

    auto submitAndWait = [&]() -> bool {
        if (vkEndCommandBuffer(retro.commandBuffer) != VK_SUCCESS)
            return false;

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &retro.commandBuffer;
        {
            std::scoped_lock queueLock(melonDS::VulkanContext::Get().GetQueueLock());
            if (vkQueueSubmit(queue, 1, &submitInfo, retro.fence) != VK_SUCCESS)
                return false;
        }
        const VkResult waitResult = vkWaitForFences(device, 1, &retro.fence, VK_TRUE, UINT64_MAX);
        return waitResult == VK_SUCCESS;
    };

    auto begin = [&]() -> bool {
        vkResetFences(device, 1, &retro.fence);
        if (vkResetCommandBuffer(retro.commandBuffer, 0) != VK_SUCCESS)
            return false;
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        return vkBeginCommandBuffer(retro.commandBuffer, &beginInfo) == VK_SUCCESS;
    };

    auto imageBarrier = [&](RetroArchImageResource& resource, VkImageLayout newLayout, VkAccessFlags dstAccess, VkPipelineStageFlags dstStage) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        barrier.dstAccessMask = dstAccess;
        barrier.oldLayout = resource.layout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = resource.image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            retro.commandBuffer,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            dstStage,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &barrier);
        resource.layout = newLayout;
    };

    auto sourceAtlasBarrier = [&](VkImageLayout oldLayout, VkImageLayout newLayout, VkAccessFlags srcAccess, VkAccessFlags dstAccess, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = srcAccess;
        barrier.dstAccessMask = dstAccess;
        barrier.oldLayout = oldLayout;
        barrier.newLayout = newLayout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = sourceAtlasImage;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(retro.commandBuffer, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    };

    auto copyScreenInput = [&](RetroArchImageResource& dst, u32 srcY) {
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[0] = {0, static_cast<int32_t>(srcY), 0};
        blit.srcOffsets[1] = {
            static_cast<int32_t>(sizing.inputScreenWidth),
            static_cast<int32_t>(srcY + sizing.inputScreenHeight),
            1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[0] = {0, 0, 0};
        blit.dstOffsets[1] = {
            static_cast<int32_t>(sizing.sourceScreenWidth),
            static_cast<int32_t>(sizing.sourceScreenHeight),
            1};
        vkCmdBlitImage(
            retro.commandBuffer,
            sourceAtlasImage,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            dst.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &blit,
            VK_FILTER_LINEAR);
    };

    retro.frameCount++;
    const bool clearHistory = surfaceState.config.retroShaderClearHistory || retro.frameCount <= 1;

    if (!begin())
        return false;

    sourceAtlasBarrier(
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT);

    imageBarrier(retro.topInput, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    imageBarrier(retro.bottomInput, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    copyScreenInput(retro.topInput, 0);
    copyScreenInput(retro.bottomInput, sizing.inputBottomOffsetY);

    sourceAtlasBarrier(
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_MEMORY_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);

    imageBarrier(retro.topInput, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    imageBarrier(retro.bottomInput, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    imageBarrier(retro.topOutput, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    imageBarrier(retro.bottomOutput, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

    if (!retro.topChain.recordFrame(retro.commandBuffer, retro.topInput.image, retro.topOutput.image, retro.frameCount, clearHistory, 16)
        || !retro.bottomChain.recordFrame(retro.commandBuffer, retro.bottomInput.image, retro.bottomOutput.image, retro.frameCount, clearHistory, 16))
        return false;

    imageBarrier(retro.atlasOutput, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    imageBarrier(retro.topOutput, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    imageBarrier(retro.bottomOutput, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    auto copyFilteredScreen = [&](RetroArchImageResource& src, u32 dstY) {
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[0] = {0, 0, 0};
        blit.srcOffsets[1] = {
            static_cast<int32_t>(sizing.outputScreenWidth),
            static_cast<int32_t>(sizing.outputScreenHeight),
            1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[0] = {0, static_cast<int32_t>(dstY), 0};
        blit.dstOffsets[1] = {
            static_cast<int32_t>(sizing.outputScreenWidth),
            static_cast<int32_t>(dstY + sizing.outputScreenHeight),
            1};
        vkCmdBlitImage(
            retro.commandBuffer,
            src.image,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            retro.atlasOutput.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &blit,
            VK_FILTER_NEAREST);
    };
    copyFilteredScreen(retro.topOutput, 0);
    copyFilteredScreen(retro.bottomOutput, sizing.outputBottomOffsetY);
    imageBarrier(retro.atlasOutput, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    imageBarrier(retro.topOutput, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    imageBarrier(retro.bottomOutput, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

    if (!submitAndWait())
        return false;

    outputImage = retro.atlasOutput.image;
    outputImageView = retro.atlasOutput.imageView;
    return true;
}

bool VulkanSurfacePresenter::createTextureFromPixels(BackgroundResource& resource, const VulkanBackgroundImage& backgroundImage)
{
    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    VkCommandPool uploadCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer uploadCommandBuffer = VK_NULL_HANDLE;
    VkFence uploadFence = VK_NULL_HANDLE;

    const VkDeviceSize uploadSize = static_cast<VkDeviceSize>(backgroundImage.width) * static_cast<VkDeviceSize>(backgroundImage.height) * 4;

    VkBufferCreateInfo stagingBufferInfo{};
    stagingBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferInfo.size = uploadSize;
    stagingBufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    stagingBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device, &stagingBufferInfo, nullptr, &stagingBuffer) != VK_SUCCESS)
        return false;

    VkMemoryRequirements stagingRequirements{};
    vkGetBufferMemoryRequirements(device, stagingBuffer, &stagingRequirements);

    VkMemoryAllocateInfo stagingMemoryInfo{};
    stagingMemoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingMemoryInfo.allocationSize = stagingRequirements.size;
    stagingMemoryInfo.memoryTypeIndex = findMemoryType(
        stagingRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    if (stagingMemoryInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(device, &stagingMemoryInfo, nullptr, &stagingMemory) != VK_SUCCESS
        || vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0) != VK_SUCCESS)
        return false;

    void* mappedMemory = nullptr;
    if (vkMapMemory(device, stagingMemory, 0, uploadSize, 0, &mappedMemory) != VK_SUCCESS)
        return false;
    std::memcpy(mappedMemory, backgroundImage.pixels, static_cast<size_t>(uploadSize));
    vkUnmapMemory(device, stagingMemory);

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent.width = backgroundImage.width;
    imageInfo.extent.height = backgroundImage.height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(device, &imageInfo, nullptr, &resource.image) != VK_SUCCESS)
        return false;

    VkMemoryRequirements imageRequirements{};
    vkGetImageMemoryRequirements(device, resource.image, &imageRequirements);

    VkMemoryAllocateInfo imageMemoryInfo{};
    imageMemoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageMemoryInfo.allocationSize = imageRequirements.size;
    imageMemoryInfo.memoryTypeIndex = findMemoryType(imageRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (imageMemoryInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(device, &imageMemoryInfo, nullptr, &resource.memory) != VK_SUCCESS
        || vkBindImageMemory(device, resource.image, resource.memory, 0) != VK_SUCCESS)
        return false;

    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.queueFamilyIndex = queueFamilyIndex;

    if (vkCreateCommandPool(device, &commandPoolInfo, nullptr, &uploadCommandPool) != VK_SUCCESS)
        return false;

    VkCommandBufferAllocateInfo commandBufferInfo{};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = uploadCommandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = 1;

    if (vkAllocateCommandBuffers(device, &commandBufferInfo, &uploadCommandBuffer) != VK_SUCCESS)
        return false;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

    if (vkCreateFence(device, &fenceInfo, nullptr, &uploadFence) != VK_SUCCESS)
        return false;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    if (vkBeginCommandBuffer(uploadCommandBuffer, &beginInfo) != VK_SUCCESS)
        return false;

    VkImageMemoryBarrier toTransferBarrier{};
    toTransferBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferBarrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toTransferBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransferBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferBarrier.image = resource.image;
    toTransferBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toTransferBarrier.subresourceRange.levelCount = 1;
    toTransferBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        uploadCommandBuffer,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &toTransferBarrier
    );

    VkBufferImageCopy imageCopy{};
    imageCopy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageCopy.imageSubresource.layerCount = 1;
    imageCopy.imageExtent.width = backgroundImage.width;
    imageCopy.imageExtent.height = backgroundImage.height;
    imageCopy.imageExtent.depth = 1;

    vkCmdCopyBufferToImage(
        uploadCommandBuffer,
        stagingBuffer,
        resource.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &imageCopy
    );

    VkImageMemoryBarrier toSampledBarrier{};
    toSampledBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toSampledBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toSampledBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toSampledBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toSampledBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toSampledBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toSampledBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toSampledBarrier.image = resource.image;
    toSampledBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toSampledBarrier.subresourceRange.levelCount = 1;
    toSampledBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        uploadCommandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &toSampledBarrier
    );

    if (vkEndCommandBuffer(uploadCommandBuffer) != VK_SUCCESS)
        return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &uploadCommandBuffer;

    {
        std::scoped_lock queueLock(melonDS::VulkanContext::Get().GetQueueLock());
        if (vkQueueSubmit(queue, 1, &submitInfo, uploadFence) != VK_SUCCESS)
            return false;
    }

    if (vkWaitForFences(device, 1, &uploadFence, VK_TRUE, UINT64_MAX) != VK_SUCCESS)
        return false;

    VkImageViewCreateInfo imageViewInfo{};
    imageViewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    imageViewInfo.image = resource.image;
    imageViewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    imageViewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageViewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageViewInfo.subresourceRange.levelCount = 1;
    imageViewInfo.subresourceRange.layerCount = 1;

    const bool createdImageView = vkCreateImageView(device, &imageViewInfo, nullptr, &resource.imageView) == VK_SUCCESS;

    if (uploadFence != VK_NULL_HANDLE)
        vkDestroyFence(device, uploadFence, nullptr);
    if (uploadCommandBuffer != VK_NULL_HANDLE && uploadCommandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(device, uploadCommandPool, 1, &uploadCommandBuffer);
    if (uploadCommandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(device, uploadCommandPool, nullptr);
    if (stagingBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, stagingBuffer, nullptr);
    if (stagingMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, stagingMemory, nullptr);

    if (!createdImageView)
        return false;

    resource.width = backgroundImage.width;
    resource.height = backgroundImage.height;
    return true;
}

bool VulkanSurfacePresenter::updateDescriptorSets(
    SurfaceState& surfaceState,
    VkImageView frameImageView,
    const VulkanCompositionInputs& inputs,
    VulkanFilterMode filtering,
    bool directPresent)
{
    (void)directPresent;

    DescriptorSetCacheState& screenCache = surfaceState.screenDescriptorCache;
    VkDescriptorImageInfo screenImageInfo{};
    screenImageInfo.sampler = (filtering == VulkanFilterMode::Linear || filtering == VulkanFilterMode::Quilez)
        ? linearSampler
        : nearestSampler;
    screenImageInfo.imageView = frameImageView;
    screenImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorImageInfo rendererImageInfo{};
    rendererImageInfo.imageView = inputs.sourceImageView;
    rendererImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo previousTopRendererImageInfo{};
    previousTopRendererImageInfo.imageView = inputs.previousTopSourceImageView;
    previousTopRendererImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo previousBottomRendererImageInfo{};
    previousBottomRendererImageInfo.imageView = inputs.previousBottomSourceImageView;
    previousBottomRendererImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorBufferInfo topPackedBufferInfo{};
    topPackedBufferInfo.buffer = inputs.topPackedBuffer;
    topPackedBufferInfo.offset = 0;
    topPackedBufferInfo.range = inputs.packedBufferSize;

    VkDescriptorBufferInfo bottomPackedBufferInfo{};
    bottomPackedBufferInfo.buffer = inputs.bottomPackedBuffer;
    bottomPackedBufferInfo.offset = 0;
    bottomPackedBufferInfo.range = inputs.packedBufferSize;

    VkDescriptorBufferInfo capture3dBufferInfo{};
    capture3dBufferInfo.buffer = inputs.capture3dBuffer;
    capture3dBufferInfo.offset = 0;
    capture3dBufferInfo.range = inputs.capture3dBufferSize;

    const bool screenInputShapeChanged =
        screenCache.scale != inputs.scale
        || screenCache.rendererWidth != inputs.rendererWidth
        || screenCache.rendererHeight != inputs.rendererHeight;

    if (!screenCache.ready
        || screenInputShapeChanged
        || screenCache.sampledImageView != frameImageView
        || screenCache.sampledImageLayout != screenImageInfo.imageLayout
        || screenCache.sampledSampler != screenImageInfo.sampler
        || screenCache.rendererImageView != inputs.sourceImageView
        || screenCache.previousTopRendererImageView != inputs.previousTopSourceImageView
        || screenCache.previousBottomRendererImageView != inputs.previousBottomSourceImageView
        || screenCache.topPackedBuffer != inputs.topPackedBuffer
        || screenCache.bottomPackedBuffer != inputs.bottomPackedBuffer
        || screenCache.capture3dBuffer != inputs.capture3dBuffer)
    {
        std::array<VkWriteDescriptorSet, 7> screenWrites{};
        u32 screenWriteCount = 0;
        auto appendScreenImageWrite = [&](u32 binding, const VkDescriptorImageInfo* info, VkDescriptorType descriptorType) {
            VkWriteDescriptorSet& write = screenWrites[screenWriteCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = surfaceState.screenDescriptorSet;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = descriptorType;
            write.pImageInfo = info;
        };
        auto appendScreenBufferWrite = [&](u32 binding, const VkDescriptorBufferInfo* info) {
            VkWriteDescriptorSet& write = screenWrites[screenWriteCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = surfaceState.screenDescriptorSet;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = info;
        };

        if (!screenCache.ready
            || screenInputShapeChanged
            || screenCache.sampledImageView != frameImageView
            || screenCache.sampledImageLayout != screenImageInfo.imageLayout
            || screenCache.sampledSampler != screenImageInfo.sampler)
        {
            appendScreenImageWrite(0, &screenImageInfo, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.rendererImageView != inputs.sourceImageView)
        {
            appendScreenImageWrite(1, &rendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.previousTopRendererImageView != inputs.previousTopSourceImageView)
        {
            appendScreenImageWrite(4, &previousTopRendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.previousBottomRendererImageView != inputs.previousBottomSourceImageView)
        {
            appendScreenImageWrite(6, &previousBottomRendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.topPackedBuffer != inputs.topPackedBuffer)
        {
            appendScreenBufferWrite(2, &topPackedBufferInfo);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.bottomPackedBuffer != inputs.bottomPackedBuffer)
        {
            appendScreenBufferWrite(3, &bottomPackedBufferInfo);
        }
        if (!screenCache.ready || screenInputShapeChanged || screenCache.capture3dBuffer != inputs.capture3dBuffer)
        {
            appendScreenBufferWrite(5, &capture3dBufferInfo);
        }

        if (screenWriteCount > 0)
            vkUpdateDescriptorSets(device, screenWriteCount, screenWrites.data(), 0, nullptr);

        screenCache.ready = true;
        screenCache.sampledImageView = frameImageView;
        screenCache.sampledImageLayout = screenImageInfo.imageLayout;
        screenCache.sampledSampler = screenImageInfo.sampler;
        screenCache.rendererImageView = inputs.sourceImageView;
        screenCache.previousTopRendererImageView = inputs.previousTopSourceImageView;
        screenCache.previousBottomRendererImageView = inputs.previousBottomSourceImageView;
        screenCache.topPackedBuffer = inputs.topPackedBuffer;
        screenCache.bottomPackedBuffer = inputs.bottomPackedBuffer;
        screenCache.capture3dBuffer = inputs.capture3dBuffer;
        screenCache.scale = inputs.scale;
        screenCache.rendererWidth = inputs.rendererWidth;
        screenCache.rendererHeight = inputs.rendererHeight;
    }

    DescriptorSetCacheState& backgroundCache = surfaceState.backgroundDescriptorCache;
    if (surfaceState.background.imageView != VK_NULL_HANDLE
        && (!backgroundCache.ready
            || surfaceState.backgroundDescriptorDirty
            || backgroundCache.sampledImageView != surfaceState.background.imageView
            || backgroundCache.sampledImageLayout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            || backgroundCache.sampledSampler != linearSampler
            || backgroundCache.rendererImageView != inputs.sourceImageView
            || backgroundCache.previousTopRendererImageView != inputs.previousTopSourceImageView
            || backgroundCache.previousBottomRendererImageView != inputs.previousBottomSourceImageView
            || backgroundCache.topPackedBuffer != inputs.topPackedBuffer
            || backgroundCache.bottomPackedBuffer != inputs.bottomPackedBuffer
            || backgroundCache.capture3dBuffer != inputs.capture3dBuffer))
    {
        VkDescriptorImageInfo backgroundImageInfo{};
        backgroundImageInfo.sampler = linearSampler;
        backgroundImageInfo.imageView = surfaceState.background.imageView;
        backgroundImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        std::array<VkWriteDescriptorSet, 7> backgroundWrites{};
        u32 backgroundWriteCount = 0;
        auto appendBackgroundImageWrite = [&](u32 binding, const VkDescriptorImageInfo* info, VkDescriptorType descriptorType) {
            VkWriteDescriptorSet& write = backgroundWrites[backgroundWriteCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = surfaceState.backgroundDescriptorSet;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = descriptorType;
            write.pImageInfo = info;
        };
        auto appendBackgroundBufferWrite = [&](u32 binding, const VkDescriptorBufferInfo* info) {
            VkWriteDescriptorSet& write = backgroundWrites[backgroundWriteCount++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = surfaceState.backgroundDescriptorSet;
            write.dstBinding = binding;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = info;
        };

        if (!backgroundCache.ready
            || surfaceState.backgroundDescriptorDirty
            || backgroundCache.sampledImageView != surfaceState.background.imageView
            || backgroundCache.sampledImageLayout != backgroundImageInfo.imageLayout
            || backgroundCache.sampledSampler != backgroundImageInfo.sampler)
        {
            appendBackgroundImageWrite(0, &backgroundImageInfo, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        }
        if (!backgroundCache.ready || surfaceState.backgroundDescriptorDirty || backgroundCache.rendererImageView != inputs.sourceImageView)
        {
            appendBackgroundImageWrite(1, &rendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!backgroundCache.ready
            || surfaceState.backgroundDescriptorDirty
            || backgroundCache.previousTopRendererImageView != inputs.previousTopSourceImageView)
        {
            appendBackgroundImageWrite(4, &previousTopRendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!backgroundCache.ready
            || surfaceState.backgroundDescriptorDirty
            || backgroundCache.previousBottomRendererImageView != inputs.previousBottomSourceImageView)
        {
            appendBackgroundImageWrite(6, &previousBottomRendererImageInfo, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        }
        if (!backgroundCache.ready || surfaceState.backgroundDescriptorDirty || backgroundCache.topPackedBuffer != inputs.topPackedBuffer)
        {
            appendBackgroundBufferWrite(2, &topPackedBufferInfo);
        }
        if (!backgroundCache.ready || surfaceState.backgroundDescriptorDirty || backgroundCache.bottomPackedBuffer != inputs.bottomPackedBuffer)
        {
            appendBackgroundBufferWrite(3, &bottomPackedBufferInfo);
        }
        if (!backgroundCache.ready || surfaceState.backgroundDescriptorDirty || backgroundCache.capture3dBuffer != inputs.capture3dBuffer)
        {
            appendBackgroundBufferWrite(5, &capture3dBufferInfo);
        }

        if (backgroundWriteCount > 0)
            vkUpdateDescriptorSets(device, backgroundWriteCount, backgroundWrites.data(), 0, nullptr);

        backgroundCache.ready = true;
        backgroundCache.sampledImageView = surfaceState.background.imageView;
        backgroundCache.sampledImageLayout = backgroundImageInfo.imageLayout;
        backgroundCache.sampledSampler = backgroundImageInfo.sampler;
        backgroundCache.rendererImageView = inputs.sourceImageView;
        backgroundCache.previousTopRendererImageView = inputs.previousTopSourceImageView;
        backgroundCache.previousBottomRendererImageView = inputs.previousBottomSourceImageView;
        backgroundCache.topPackedBuffer = inputs.topPackedBuffer;
        backgroundCache.bottomPackedBuffer = inputs.bottomPackedBuffer;
        backgroundCache.capture3dBuffer = inputs.capture3dBuffer;
        surfaceState.backgroundDescriptorDirty = false;
    }

    return true;
}

bool VulkanSurfacePresenter::updateVertexBuffer(
    SurfaceState& surfaceState,
    const VulkanSurfaceConfig& config,
    const BackgroundResource* backgroundResource,
    bool directPresent,
    bool retroArchApplied,
    std::vector<DrawCall>& drawCalls)
{
    if (!surfaceState.vertexBufferDirty
        && surfaceState.cachedDirectPresent == directPresent
        && surfaceState.cachedRetroArchApplied == retroArchApplied)
    {
        drawCalls = surfaceState.cachedDrawCalls;
        return true;
    }

    const float surfaceWidth = static_cast<float>(std::max(1u, surfaceState.extent.width));
    const float surfaceHeight = static_cast<float>(std::max(1u, surfaceState.extent.height));

    auto screenXToNdc = [&](int x) -> float {
        return (static_cast<float>(x) / surfaceWidth) * 2.0f - 1.0f;
    };
    auto screenYToNdc = [&](int y) -> float {
        return 1.0f - (static_cast<float>(y) / surfaceHeight) * 2.0f;
    };

    std::vector<SurfaceVertex> vertices;
    vertices.reserve(kMaxSurfaceVertexCount);

    auto appendQuad = [&](float left, float right, float top, float bottom, float alpha, u32 drawMode, VkDescriptorSet descriptorSet) {
        const u32 firstVertex = static_cast<u32>(vertices.size());
        vertices.push_back(SurfaceVertex{left, bottom, 0.0f, 1.0f, alpha});
        vertices.push_back(SurfaceVertex{left, top, 0.0f, 0.0f, alpha});
        vertices.push_back(SurfaceVertex{right, top, 1.0f, 0.0f, alpha});
        vertices.push_back(SurfaceVertex{left, bottom, 0.0f, 1.0f, alpha});
        vertices.push_back(SurfaceVertex{right, top, 1.0f, 0.0f, alpha});
        vertices.push_back(SurfaceVertex{right, bottom, 1.0f, 1.0f, alpha});

        drawCalls.push_back(DrawCall{
            .descriptorSet = descriptorSet,
            .firstVertex = firstVertex,
            .vertexCount = 6,
            .drawMode = drawMode,
            .viewportWidth = 0.0f,
            .viewportHeight = 0.0f,
        });
    };

    if (backgroundResource != nullptr && backgroundResource->width > 0 && backgroundResource->height > 0)
    {
        const float backgroundAspectRatio = static_cast<float>(backgroundResource->width) / static_cast<float>(backgroundResource->height);
        const float screenAspectRatio = surfaceWidth / surfaceHeight;

        float left = -1.0f;
        float right = 1.0f;
        float top = 1.0f;
        float bottom = -1.0f;

        switch (config.backgroundMode)
        {
            case VulkanPresenterBackgroundMode::Stretch:
                break;
            case VulkanPresenterBackgroundMode::FitCenter:
            case VulkanPresenterBackgroundMode::FitLeft:
            case VulkanPresenterBackgroundMode::FitRight:
            {
                if (screenAspectRatio > backgroundAspectRatio)
                {
                    const float scaleFactor = surfaceWidth / static_cast<float>(backgroundResource->width);
                    const float relativeWidth = surfaceHeight / (static_cast<float>(backgroundResource->height) * scaleFactor) * 2.0f;
                    if (config.backgroundMode == VulkanPresenterBackgroundMode::FitLeft)
                    {
                        left = -1.0f;
                        right = -1.0f + relativeWidth;
                    }
                    else if (config.backgroundMode == VulkanPresenterBackgroundMode::FitRight)
                    {
                        left = 1.0f - relativeWidth;
                        right = 1.0f;
                    }
                    else
                    {
                        left = -(relativeWidth / 2.0f);
                        right = relativeWidth / 2.0f;
                    }
                }
                else
                {
                    const float scaleFactor = surfaceHeight / static_cast<float>(backgroundResource->height);
                    const float relativeHeight = surfaceWidth / (static_cast<float>(backgroundResource->width) * scaleFactor) * 2.0f;
                    top = relativeHeight / 2.0f;
                    bottom = -(relativeHeight / 2.0f);
                }
                break;
            }
            case VulkanPresenterBackgroundMode::FitTop:
            case VulkanPresenterBackgroundMode::FitBottom:
            {
                if (screenAspectRatio > backgroundAspectRatio)
                {
                    const float scaleFactor = surfaceWidth / static_cast<float>(backgroundResource->width);
                    const float relativeWidth = surfaceHeight / (static_cast<float>(backgroundResource->height) * scaleFactor) * 2.0f;
                    left = -(relativeWidth / 2.0f);
                    right = relativeWidth / 2.0f;
                }
                else
                {
                    const float scaleFactor = surfaceHeight / static_cast<float>(backgroundResource->height);
                    const float relativeHeight = surfaceWidth / (static_cast<float>(backgroundResource->width) * scaleFactor) * 2.0f;
                    if (config.backgroundMode == VulkanPresenterBackgroundMode::FitTop)
                    {
                        top = 1.0f;
                        bottom = 1.0f - relativeHeight;
                    }
                    else
                    {
                        top = -1.0f + relativeHeight;
                        bottom = -1.0f;
                    }
                }
                break;
            }
        }

        appendQuad(left, right, top, bottom, 1.0f, kDrawModeBackground, surfaceState.backgroundDescriptorSet);
    }

    auto appendScreen = [&](const VulkanPresenterRect& rect, bool topScreen, float alpha) {
        if (!rect.enabled || rect.width <= 0 || rect.height <= 0)
            return;

        const float left = screenXToNdc(rect.x);
        const float right = screenXToNdc(rect.x + rect.width);
        const float top = screenYToNdc(rect.y);
        const float bottom = screenYToNdc(rect.y + rect.height);
        const float uvTop = directPresent ? 0.0f : (topScreen ? 0.0f : (0.5f + (1.0f / 386.0f)));
        const float uvBottom = directPresent ? 1.0f : (topScreen ? (0.5f - (1.0f / 386.0f)) : 1.0f);
        const u32 drawMode = directPresent
            ? (topScreen ? kDrawModeTopScreen : kDrawModeBottomScreen)
            : (retroArchApplied
                ? kDrawModeRetroArchCompositeFrame
                : (config.filtering != VulkanFilterMode::RetroArch && IsVulkanPostProcessFilter(config.filtering)
                ? (topScreen ? kDrawModeFilteredCompositeTop : kDrawModeFilteredCompositeBottom)
                : kDrawModeCompositeFrame));
        const float topVertexUv = directPresent ? uvBottom : uvTop;
        const float bottomVertexUv = directPresent ? uvTop : uvBottom;

        const u32 firstVertex = static_cast<u32>(vertices.size());
        vertices.push_back(SurfaceVertex{left, bottom, 0.0f, bottomVertexUv, alpha});
        vertices.push_back(SurfaceVertex{left, top, 0.0f, topVertexUv, alpha});
        vertices.push_back(SurfaceVertex{right, top, 1.0f, topVertexUv, alpha});
        vertices.push_back(SurfaceVertex{left, bottom, 0.0f, bottomVertexUv, alpha});
        vertices.push_back(SurfaceVertex{right, top, 1.0f, topVertexUv, alpha});
        vertices.push_back(SurfaceVertex{right, bottom, 1.0f, bottomVertexUv, alpha});

        drawCalls.push_back(DrawCall{
            .descriptorSet = surfaceState.screenDescriptorSet,
            .firstVertex = firstVertex,
            .vertexCount = 6,
            .drawMode = drawMode,
            .viewportWidth = static_cast<float>(rect.width),
            .viewportHeight = static_cast<float>(rect.height),
        });
    };

    struct PendingScreen
    {
        VulkanPresenterRect rect;
        bool topScreen = false;
        float alpha = 1.0f;
    };

    std::vector<PendingScreen> underScreens;
    std::vector<PendingScreen> overScreens;
    const auto enqueueScreen = [&](const VulkanPresenterRect& rect, bool topScreen, float alpha, bool onTop) {
        if (onTop)
            overScreens.push_back(PendingScreen{rect, topScreen, alpha});
        else
            underScreens.push_back(PendingScreen{rect, topScreen, alpha});
    };

    if (config.bottomOnTop)
    {
        enqueueScreen(config.topScreen, true, config.topAlpha, false);
        enqueueScreen(config.bottomScreen, false, config.bottomAlpha, true);
    }
    else
    {
        enqueueScreen(config.bottomScreen, false, config.bottomAlpha, false);
        enqueueScreen(config.topScreen, true, config.topAlpha, true);
    }
    enqueueScreen(config.hybridTopScreen, true, config.hybridAlpha, config.hybridOnTop);
    enqueueScreen(config.hybridBottomScreen, false, config.hybridAlpha, config.hybridOnTop);

    for (const PendingScreen& screen : underScreens)
        appendScreen(screen.rect, screen.topScreen, screen.alpha);
    for (const PendingScreen& screen : overScreens)
        appendScreen(screen.rect, screen.topScreen, screen.alpha);

    if (vertices.size() > kMaxSurfaceVertexCount)
        return false;

    if (surfaceState.mappedVertexMemory == nullptr)
        return false;

    if (!vertices.empty())
        std::memcpy(surfaceState.mappedVertexMemory, vertices.data(), vertices.size() * sizeof(SurfaceVertex));

    if (MelonDSAndroid::areRendererDebugBgObjLogsEnabled())
    {
        const auto logDrawVertices = [&](const DrawCall& drawCall) {
            if (drawCall.vertexCount < 6 || drawCall.firstVertex + 5 >= vertices.size())
                return;

            const SurfaceVertex& v0 = vertices[drawCall.firstVertex + 0];
            const SurfaceVertex& v1 = vertices[drawCall.firstVertex + 1];
            const SurfaceVertex& v5 = vertices[drawCall.firstVertex + 5];
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Info,
                "VulkanPresenter[Vertices]: surface=%d direct=%d drawMode=%u first=%u count=%u posLTRB=(%.3f,%.3f,%.3f,%.3f) uvTopBottom=(%.3f,%.3f)",
                surfaceState.id,
                directPresent ? 1 : 0,
                drawCall.drawMode,
                drawCall.firstVertex,
                drawCall.vertexCount,
                v0.x,
                v5.x,
                v1.y,
                v0.y,
                v1.v,
                v0.v
            );
        };

        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Info,
            "VulkanPresenter[Config]: surface=%d extent=%ux%u direct=%d topRect=(%d,%d,%d,%d,%d) bottomRect=(%d,%d,%d,%d,%d) hybridTopRect=(%d,%d,%d,%d,%d) hybridBottomRect=(%d,%d,%d,%d,%d) bottomOnTop=%d hybridOnTop=%d drawCalls=%zu",
            surfaceState.id,
            surfaceState.extent.width,
            surfaceState.extent.height,
            directPresent ? 1 : 0,
            config.topScreen.enabled ? 1 : 0,
            config.topScreen.x,
            config.topScreen.y,
            config.topScreen.width,
            config.topScreen.height,
            config.bottomScreen.enabled ? 1 : 0,
            config.bottomScreen.x,
            config.bottomScreen.y,
            config.bottomScreen.width,
            config.bottomScreen.height,
            config.hybridTopScreen.enabled ? 1 : 0,
            config.hybridTopScreen.x,
            config.hybridTopScreen.y,
            config.hybridTopScreen.width,
            config.hybridTopScreen.height,
            config.hybridBottomScreen.enabled ? 1 : 0,
            config.hybridBottomScreen.x,
            config.hybridBottomScreen.y,
            config.hybridBottomScreen.width,
            config.hybridBottomScreen.height,
            config.bottomOnTop ? 1 : 0,
            config.hybridOnTop ? 1 : 0,
            drawCalls.size()
        );

        for (const DrawCall& drawCall : drawCalls)
            logDrawVertices(drawCall);
    }

    surfaceState.cachedDrawCalls = drawCalls;
    surfaceState.cachedDirectPresent = directPresent;
    surfaceState.cachedRetroArchApplied = retroArchApplied;
    surfaceState.vertexBufferDirty = false;

    return true;
}

bool VulkanSurfacePresenter::recordSurfaceCommands(
    SurfaceState& surfaceState,
    VkFramebuffer framebuffer,
    const VulkanCompositionInputs& inputs,
    VkImage sampledImage,
    bool directPresent,
    const std::vector<DrawCall>& drawCalls)
{
    (void)directPresent;

    if (surfaceState.timestampQueryPool != VK_NULL_HANDLE && resetQueryPool != nullptr)
        resetQueryPool(device, surfaceState.timestampQueryPool, 0, 2);

    if (vkResetCommandBuffer(surfaceState.commandBuffer, 0) != VK_SUCCESS)
        return false;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    if (vkBeginCommandBuffer(surfaceState.commandBuffer, &beginInfo) != VK_SUCCESS)
        return false;

    if (surfaceState.timestampQueryPool != VK_NULL_HANDLE)
        vkCmdWriteTimestamp(surfaceState.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, surfaceState.timestampQueryPool, 0);

    std::array<VkImageMemoryBarrier, 4> sourceBarriers{};
    u32 sourceBarrierCount = 0;

    auto appendImageBarrier = [&](VkImage image) {
        if (image == VK_NULL_HANDLE)
            return;

        VkImageMemoryBarrier& barrier = sourceBarriers[sourceBarrierCount++];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.layerCount = 1;
    };

    appendImageBarrier(sampledImage);
    if (inputs.sourceImage != sampledImage && sourceBarrierCount < sourceBarriers.size())
        appendImageBarrier(inputs.sourceImage);
    if (inputs.previousTopSourceImage != sampledImage
        && inputs.previousTopSourceImage != inputs.sourceImage
        && sourceBarrierCount < sourceBarriers.size())
    {
        appendImageBarrier(inputs.previousTopSourceImage);
    }
    if (inputs.previousBottomSourceImage != sampledImage
        && inputs.previousBottomSourceImage != inputs.sourceImage
        && sourceBarrierCount < sourceBarriers.size())
    {
        appendImageBarrier(inputs.previousBottomSourceImage);
    }

    if (sourceBarrierCount > 0)
    {
        vkCmdPipelineBarrier(
            surfaceState.commandBuffer,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            sourceBarrierCount,
            sourceBarriers.data()
        );
    }

    std::array<VkBufferMemoryBarrier, 3> bufferBarriers{};
    bufferBarriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufferBarriers[0].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    bufferBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufferBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[0].buffer = inputs.topPackedBuffer;
    bufferBarriers[0].offset = 0;
    bufferBarriers[0].size = inputs.packedBufferSize;

    bufferBarriers[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufferBarriers[1].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    bufferBarriers[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufferBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[1].buffer = inputs.bottomPackedBuffer;
    bufferBarriers[1].offset = 0;
    bufferBarriers[1].size = inputs.packedBufferSize;

    bufferBarriers[2].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufferBarriers[2].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    bufferBarriers[2].dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
    bufferBarriers[2].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[2].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufferBarriers[2].buffer = surfaceState.vertexBuffer;
    bufferBarriers[2].offset = 0;
    bufferBarriers[2].size = surfaceState.vertexBufferSize;

    vkCmdPipelineBarrier(
        surfaceState.commandBuffer,
        VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
        0,
        0,
        nullptr,
        static_cast<u32>(bufferBarriers.size()),
        bufferBarriers.data(),
        0,
        nullptr
    );

    VkClearValue clearValue{};
    clearValue.color.float32[0] = 0.0f;
    clearValue.color.float32[1] = 0.0f;
    clearValue.color.float32[2] = 0.0f;
    clearValue.color.float32[3] = 1.0f;

    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = surfaceState.renderPass;
    renderPassInfo.framebuffer = framebuffer;
    renderPassInfo.renderArea.extent = surfaceState.extent;
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearValue;

    vkCmdBeginRenderPass(surfaceState.commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(surfaceState.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, surfaceState.pipeline);

    VkViewport viewport{};
    viewport.x = 0.0f;
    viewport.y = static_cast<float>(surfaceState.extent.height);
    viewport.width = static_cast<float>(surfaceState.extent.width);
    viewport.height = -static_cast<float>(surfaceState.extent.height);
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(surfaceState.commandBuffer, 0, 1, &viewport);

    VkRect2D scissor{};
    scissor.extent = surfaceState.extent;
    vkCmdSetScissor(surfaceState.commandBuffer, 0, 1, &scissor);

    VkDeviceSize vertexOffsets[] = {0};
    vkCmdBindVertexBuffers(surfaceState.commandBuffer, 0, 1, &surfaceState.vertexBuffer, vertexOffsets);

    for (const DrawCall& drawCall : drawCalls)
    {
        vkCmdBindDescriptorSets(
            surfaceState.commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelineLayout,
            0,
            1,
            &drawCall.descriptorSet,
            0,
            nullptr
        );
        PresenterPushConstants pushConstants{};
        pushConstants.drawMode = drawCall.drawMode;
        pushConstants.scale = inputs.scale;
        pushConstants.rendererWidth = inputs.rendererWidth;
        pushConstants.rendererHeight = inputs.rendererHeight;
        pushConstants.packedStride = inputs.packedStride;
        pushConstants.screenSwap = inputs.screenSwap;
        pushConstants.filtering = static_cast<u32>(surfaceState.config.filtering);
        pushConstants.previousTopSourceValid = inputs.previousTopSourceValid ? 1u : 0u;
        pushConstants.previousBottomSourceValid = inputs.previousBottomSourceValid ? 1u : 0u;
        pushConstants.captureSourceValid = inputs.capture3dSourceValid ? 1u : 0u;
        pushConstants.captureSourceScreenSwapValid = inputs.capture3dSourceScreenSwapValid ? 1u : 0u;
        pushConstants.captureSourceScreenSwap = inputs.capture3dSourceScreenSwap ? 1u : 0u;
        pushConstants.liveSourceScreenSwap = inputs.liveSourceScreenSwap ? 1u : 0u;
        pushConstants.class4VramStructuredPair = inputs.class4VramStructuredPair ? 1u : 0u;
        pushConstants.class4NoAboveVramStructuredPair = inputs.class4NoAboveVramStructuredPair ? 1u : 0u;
        pushConstants.class4PreservePackedVramValid = inputs.class4PreservePackedVramValid ? 1u : 0u;
        pushConstants.class4PreservePackedVramScreenSwap = inputs.class4PreservePackedVramScreenSwap ? 1u : 0u;
        pushConstants.topStructuredHandoffNoCurrent3d = inputs.topStructuredHandoffNoCurrent3d ? 1u : 0u;
        pushConstants.bottomStructuredHandoffNoCurrent3d = inputs.bottomStructuredHandoffNoCurrent3d ? 1u : 0u;
        pushConstants.topStructuredHandoffSuppress3d = inputs.topStructuredHandoffSuppress3d ? 1u : 0u;
        pushConstants.bottomStructuredHandoffSuppress3d = inputs.bottomStructuredHandoffSuppress3d ? 1u : 0u;
        pushConstants.viewportWidth = drawCall.viewportWidth;
        pushConstants.viewportHeight = drawCall.viewportHeight;
        if (MelonDSAndroid::areRendererDebugBgObjLogsEnabled())
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Info,
                "VulkanPresenter[Draw]: surface=%d drawMode=%u first=%u count=%u scale=%u renderer=%ux%u packedStride=%u screenSwap=%u prevTop=%u prevBottom=%u capture=%u",
                surfaceState.id,
                drawCall.drawMode,
                drawCall.firstVertex,
                drawCall.vertexCount,
                pushConstants.scale,
                pushConstants.rendererWidth,
                pushConstants.rendererHeight,
                pushConstants.packedStride,
                pushConstants.screenSwap,
                pushConstants.previousTopSourceValid,
                pushConstants.previousBottomSourceValid,
                pushConstants.captureSourceValid
            );
        }
        vkCmdPushConstants(
            surfaceState.commandBuffer,
            pipelineLayout,
            VK_SHADER_STAGE_FRAGMENT_BIT,
            0,
            sizeof(pushConstants),
            &pushConstants
        );
        vkCmdDraw(surfaceState.commandBuffer, drawCall.vertexCount, 1, drawCall.firstVertex, 0);
    }

    vkCmdEndRenderPass(surfaceState.commandBuffer);

    if (surfaceState.timestampQueryPool != VK_NULL_HANDLE)
        vkCmdWriteTimestamp(surfaceState.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, surfaceState.timestampQueryPool, 1);

    return vkEndCommandBuffer(surfaceState.commandBuffer) == VK_SUCCESS;
}

bool VulkanSurfacePresenter::submitSurfaceCommands(
    SurfaceState& surfaceState,
    u32 imageIndex,
    u64& presentCpuNs,
    u64& presentTimelineValueOut)
{
    presentTimelineValueOut = 0;
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &surfaceState.imageAvailableSemaphore;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &surfaceState.commandBuffer;

    std::array<VkSemaphore, 2> signalSemaphores = {
        surfaceState.renderFinishedSemaphore,
        timelineSemaphore,
    };
    VkTimelineSemaphoreSubmitInfo timelineSubmitInfo{};
    u64 signalValue = 0;
    if (useTimelineSemaphores && timelineSemaphore != VK_NULL_HANDLE)
    {
        signalValue = ++timelineValue;
        timelineSubmitInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
        timelineSubmitInfo.signalSemaphoreValueCount = 1;
        timelineSubmitInfo.pSignalSemaphoreValues = &signalValue;
        submitInfo.pNext = &timelineSubmitInfo;
        submitInfo.signalSemaphoreCount = 2;
        submitInfo.pSignalSemaphores = signalSemaphores.data();
    }
    else
    {
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &surfaceState.renderFinishedSemaphore;
    }

    VkPresentInfoKHR presentInfo{};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = &surfaceState.renderFinishedSemaphore;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &surfaceState.swapchain;
    presentInfo.pImageIndices = &imageIndex;

    VkResult submitResult = VK_SUCCESS;
    VkResult presentResult = VK_SUCCESS;

    if (!resetSurfaceInFlightFence(surfaceState))
        return false;

    {
        std::scoped_lock queueLock(melonDS::VulkanContext::Get().GetQueueLock());
        submitResult = vkQueueSubmit(queue, 1, &submitInfo, surfaceState.inFlightFence);
        if (submitResult == VK_SUCCESS)
        {
            const u64 presentStartNs = PerfNowNs();
            presentResult = vkQueuePresentKHR(queue, &presentInfo);
            presentCpuNs += PerfNowNs() - presentStartNs;
        }
    }
    if (submitResult != VK_SUCCESS)
    {
        (void)createInFlightFence(surfaceState, true);
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanSurfacePresenter: vkQueueSubmit failed for surface %d (%d)",
            surfaceState.id,
            static_cast<int>(submitResult)
        );
        return false;
    }

    if (surfaceState.timestampQueryPool != VK_NULL_HANDLE)
        surfaceState.timestampPending = true;

    presentTimelineValueOut = signalValue;

    if (presentResult == VK_ERROR_OUT_OF_DATE_KHR)
    {
        surfaceState.swapchainDirty = true;
        return true;
    }

    if (presentResult == VK_SUBOPTIMAL_KHR)
        return true;

    if (presentResult != VK_SUCCESS)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanSurfacePresenter: vkQueuePresentKHR failed for surface %d (%d)",
            surfaceState.id,
            static_cast<int>(presentResult)
        );
    }

    return presentResult == VK_SUCCESS;
}

u32 VulkanSurfacePresenter::findMemoryType(u32 typeBits, VkMemoryPropertyFlags properties) const
{
    return melonDS::VulkanContext::Get().FindMemoryType(typeBits, properties);
}

}
