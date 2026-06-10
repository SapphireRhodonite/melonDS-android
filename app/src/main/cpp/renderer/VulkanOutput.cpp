#include "VulkanOutput.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include "GPU.h"
#include "GPU2D_Soft.h"
#include "GPU3D_Vulkan.h"
#include "NDS.h"
#include "Platform.h"
#include "VulkanContext.h"
#include "VulkanDispatch.h"
#include "VulkanCompositorShaderData.h"
#include "VulkanAccumulate3dShaderData.h"

namespace MelonDSAndroid
{
bool areRendererDebugToolsEnabled();
bool areRendererDebugBgObjLogsEnabled();
bool areRenderer2DDebugControlsActive();
bool isRenderer2DDebugBackgroundKindEnabled(melonDS::u32 featureFlag);

namespace
{
constexpr int kScreenWidth = 256;
constexpr int kScreenHeight = 192;
constexpr int kAcceleratedStride = kScreenWidth * 3 + 1;
constexpr VkDeviceSize kPackedBufferSize = static_cast<VkDeviceSize>(kScreenHeight) * static_cast<VkDeviceSize>(kAcceleratedStride) * sizeof(melonDS::u32);
constexpr VkDeviceSize kCapture3dBufferSize = static_cast<VkDeviceSize>(kScreenWidth) * static_cast<VkDeviceSize>(kScreenHeight) * sizeof(melonDS::u32);
constexpr u64 kValidationWaitTimeoutNs = 2'000'000'000ull;
constexpr melonDS::u32 kMetaFlagRegularCaptureUses3d = 1u << 21u;
constexpr melonDS::u32 kMetaFlagVramCaptureUses3d = 1u << 22u;
constexpr melonDS::u32 kMetaFlagForceLive3dCompMode7 = 1u << 18u;
constexpr melonDS::u32 kMetaFlagStructuredAboveDominant = 1u << 19u;
constexpr melonDS::u32 kPacked3dPlaceholder = 0x20000000u;
constexpr melonDS::u32 kRenderer2DDebugFeature3DBackground = 1u << 6u;
constexpr melonDS::u32 kClass4StructuredAboveStableSamplesFor30Fps = 2u;

bool screenUsesFullRegularComp7(const SoftPackedScreenStats& stats)
{
    constexpr u32 dominantPixelThreshold = (kScreenWidth * kScreenHeight) / 2u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.CompModeCounts[7] > dominantPixelThreshold
        && stats.RegularCaptureUses3dLines > (kScreenHeight / 2u)
        && stats.VramCaptureUses3dLines == 0u
        && stats.StructuredSlotPixels > dominantPixelThreshold;
}

bool screenUsesPlainFullComp4(const SoftPackedScreenStats& stats)
{
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.CompModeCounts[4] > nearlyFullPixelThreshold
        && stats.StructuredSlotPixels > nearlyFullPixelThreshold
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
}

bool screenHasVisibleStructured2d(const SoftPackedScreenStats& stats)
{
    return stats.StructuredAboveVisiblePixels > 0u
        || stats.Structured2DOnlyPixels > 0u
        || stats.Structured2DOnlyVisiblePixels > 0u;
}

bool screenUsesFullRegularComp7WithDominantAbove(const SoftPackedScreenStats& stats)
{
    constexpr u32 dominantPixelThreshold = (kScreenWidth * kScreenHeight) / 2u;
    return screenUsesFullRegularComp7(stats)
        && stats.StructuredAboveVisiblePixels > dominantPixelThreshold;
}

bool screenUsesPlainStructuredComp7HandoffSlot(const SoftPackedScreenStats& stats)
{
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.CompModeCounts[7] > nearlyFullPixelThreshold
        && stats.StructuredSlotPixels > nearlyFullPixelThreshold
        && stats.StructuredAbovePixels == 0u
        && stats.StructuredAboveVisiblePixels == 0u
        && stats.Structured2DOnlyPixels == 0u
        && stats.Plane0VisiblePixels == 0u
        && stats.Plane1VisiblePixels == 0u
        && stats.RegularCaptureUses3dLines == 0u
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
}

bool screenUsesPlainStructured3dSlot(const SoftPackedScreenStats& stats)
{
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.StructuredSlotPixels > nearlyFullPixelThreshold
        && stats.StructuredAbovePixels == 0u
        && stats.StructuredAboveVisiblePixels == 0u
        && stats.Structured2DOnlyPixels == 0u
        && stats.Plane0VisiblePixels == 0u
        && stats.Plane1VisiblePixels == 0u
        && stats.RegularCaptureUses3dLines == 0u
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
}

bool screenUsesRegularComp7EmptyPrimarySlot(const SoftPackedScreenStats& stats)
{
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.RegularCaptureUses3dLines > dominantLineThreshold
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u
        && stats.CompModeCounts[7] > nearlyFullPixelThreshold
        && stats.StructuredSlotPixels > nearlyFullPixelThreshold
        && stats.StructuredAbovePixels == 0u
        && stats.StructuredAboveVisiblePixels == 0u
        && stats.Structured2DOnlyPixels == 0u
        && stats.Structured2DOnlyVisiblePixels == 0u
        && stats.Plane0VisiblePixels == 0u
        && stats.Plane1VisiblePixels == 0u;
}

bool screenUsesFullStructuredCompMode2Slot(
    const std::array<u32, SoftPackedFrameSnapshot::kPixelCount>& control,
    const std::array<u32, SoftPackedFrameSnapshot::kLineCount>& lineMeta)
{
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    u32 matchingPixels = 0;
    for (int y = 0; y < kScreenHeight; y++)
    {
        const u32 meta = lineMeta[static_cast<size_t>(y)];
        const u32 displayMode = (meta >> 16u) & 0x3u;
        if (displayMode != 1u
            || (meta & (kMetaFlagRegularCaptureUses3d
                | kMetaFlagVramCaptureUses3d
                | kMetaFlagForceLive3dCompMode7)) != 0u)
        {
            continue;
        }

        const size_t rowBase = static_cast<size_t>(y) * static_cast<size_t>(kScreenWidth);
        for (int x = 0; x < kScreenWidth; x++)
        {
            const u32 controlAlpha = control[rowBase + static_cast<size_t>(x)] >> 24u;
            const u32 compMode = controlAlpha & 0xFu;
            const bool structuredSlot = (controlAlpha & 0x40u) != 0u;
            const bool structuredAbove = structuredSlot && (controlAlpha & 0x80u) != 0u;
            if (compMode == 2u && structuredSlot && !structuredAbove)
                matchingPixels++;
        }
    }

    return matchingPixels > nearlyFullPixelThreshold;
}

bool screenUsesFullVramCaptureOnly(const SoftPackedScreenStats& stats)
{
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[2] > dominantLineThreshold
        && stats.VramCaptureUses3dLines > dominantLineThreshold
        && stats.DisplayModeCounts[1] == 0u
        && stats.RegularCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u
        && stats.StructuredSlotPixels == 0u
        && stats.StructuredAbovePixels == 0u
        && stats.StructuredAboveVisiblePixels == 0u
        && stats.Structured2DOnlyPixels == 0u
        && stats.Structured2DOnlyVisiblePixels == 0u;
}

bool screenHasStructuredHandoffOverlay(const SoftPackedScreenStats& stats)
{
    constexpr u32 dominantPixelThreshold = (kScreenWidth * kScreenHeight * 4u) / 5u;
    constexpr u32 sparseHandoffSlotThreshold = (kScreenWidth * kScreenHeight) / 5u;
    constexpr u32 overlayVisibleThreshold = kScreenWidth * 4u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.StructuredSlotPixels > dominantPixelThreshold
        && stats.CompModeCounts[4] > dominantPixelThreshold
        && stats.CompModeCounts[7] > 0u
        && stats.CompModeCounts[7] < sparseHandoffSlotThreshold
        && stats.StructuredAboveVisiblePixels > overlayVisibleThreshold
        && stats.RegularCaptureUses3dLines == 0u
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
}

bool screenUsesFullStructured2dOnlyDisplay(const SoftPackedScreenStats& stats)
{
    constexpr u32 nearlyFullPixelThreshold = (kScreenWidth * kScreenHeight * 7u) / 8u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    return stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.CompModeCounts[7] > nearlyFullPixelThreshold
        && stats.Structured2DOnlyPixels > nearlyFullPixelThreshold
        && stats.StructuredSlotPixels == 0u
        && stats.StructuredAbovePixels == 0u
        && stats.RegularCaptureUses3dLines == 0u
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
}

bool screenUsesStructuredHandoffWithoutCurrent3d(
    const SoftPackedScreenStats& stats,
    const SoftPackedScreenStats& oppositeStats)
{
    constexpr u32 dominantPixelThreshold = (kScreenWidth * kScreenHeight * 4u) / 5u;
    constexpr u32 overlayVisibleThreshold = kScreenWidth * 4u;
    constexpr u32 dominantLineThreshold = kScreenHeight / 2u;
    const bool structuredHandoffScreen =
        stats.DisplayModeCounts[1] > dominantLineThreshold
        && stats.StructuredSlotPixels > dominantPixelThreshold
        && stats.StructuredAboveVisiblePixels > overlayVisibleThreshold
        && stats.CompModeCounts[7] > 0u
        && stats.RegularCaptureUses3dLines == 0u
        && stats.VramCaptureUses3dLines == 0u
        && stats.ForceLive3dCompMode7Lines == 0u;
    const bool oppositeIsHandoffTarget =
        screenUsesFullStructured2dOnlyDisplay(oppositeStats)
        || screenUsesPlainStructuredComp7HandoffSlot(oppositeStats)
        || screenUsesPlainStructured3dSlot(oppositeStats);
    return structuredHandoffScreen && oppositeIsHandoffTarget;
}

melonDS::u32 expandPackedColor6ToRgba8(melonDS::u32 packedColor)
{
    const melonDS::u32 r6 = packedColor & 0xFFu;
    const melonDS::u32 g6 = (packedColor >> 8u) & 0xFFu;
    const melonDS::u32 b6 = (packedColor >> 16u) & 0xFFu;
    const melonDS::u32 r8 = ((r6 & 0x3Fu) << 2u) | ((r6 & 0x3Fu) >> 4u);
    const melonDS::u32 g8 = ((g6 & 0x3Fu) << 2u) | ((g6 & 0x3Fu) >> 4u);
    const melonDS::u32 b8 = ((b6 & 0x3Fu) << 2u) | ((b6 & 0x3Fu) >> 4u);
    return r8 | (g8 << 8u) | (b8 << 16u) | 0xFF000000u;
}

bool packedBufferNeedsCapture3dSource(const melonDS::u32* packedBuffer)
{
    if (packedBuffer == nullptr)
        return false;

    for (int y = 0; y < kScreenHeight; y++)
    {
        const size_t rowBase = static_cast<size_t>(y) * static_cast<size_t>(kAcceleratedStride);
        const melonDS::u32 meta = packedBuffer[rowBase + (kScreenWidth * 3)];
        if ((meta & (kMetaFlagRegularCaptureUses3d | kMetaFlagVramCaptureUses3d)) != 0u)
            return true;

        for (int x = 0; x < kScreenWidth; x++)
        {
            const melonDS::u32 val1 = packedBuffer[rowBase + static_cast<size_t>(x)];
            const melonDS::u32 val2 = packedBuffer[rowBase + static_cast<size_t>(kScreenWidth + x)];
            const melonDS::u32 val3 = packedBuffer[rowBase + static_cast<size_t>((kScreenWidth * 2) + x)];
            const bool captureBackedComp4 =
                val1 == kPacked3dPlaceholder
                && val2 == kPacked3dPlaceholder
                && (((val3 >> 24u) & 0xFu) == 4u);
            if (captureBackedComp4)
                return true;
        }
    }

    return false;
}

bool softPackedSnapshotNeedsCapture3dSource(const SoftPackedFrameSnapshot& snapshot)
{
    if (!snapshot.valid)
        return false;

    const auto screenNeedsCapture3d = [](const SoftPackedScreenStats& stats) {
        return stats.CaptureBackedComp4Lines > 0u
            || stats.RegularCaptureUses3dLines > 0u
            || stats.VramCaptureUses3dLines > 0u;
    };

    return screenNeedsCapture3d(snapshot.topScreenStats)
        || screenNeedsCapture3d(snapshot.bottomScreenStats);
}

bool capture3dSourceHasAnyUsefulPixel(const melonDS::u32* capture3dSource)
{
    if (capture3dSource == nullptr)
        return false;

    constexpr size_t kCapturePixelCount = static_cast<size_t>(kScreenWidth) * static_cast<size_t>(kScreenHeight);
    for (size_t i = 0; i < kCapturePixelCount; i++)
    {
        const melonDS::u32 pixel = capture3dSource[i];
        if (pixel != 0u && pixel != kPacked3dPlaceholder)
            return true;
    }

    return false;
}

bool capture3dSourceLineHasAnyUsefulPixel(const melonDS::u32* capture3dSource, int line)
{
    if (capture3dSource == nullptr || line < 0 || line >= kScreenHeight)
        return false;

    const size_t rowOffset = static_cast<size_t>(line) * static_cast<size_t>(kScreenWidth);
    for (int x = 0; x < kScreenWidth; x++)
    {
        const melonDS::u32 pixel = capture3dSource[rowOffset + static_cast<size_t>(x)];
        if (pixel != 0u && pixel != kPacked3dPlaceholder)
            return true;
    }

    return false;
}

bool capture3dSourcePixelIsUseful(melonDS::u32 pixel)
{
    return pixel != 0u && pixel != kPacked3dPlaceholder;
}

bool capture3dSourcePixelIsNonBlackUseful(melonDS::u32 pixel)
{
    return capture3dSourcePixelIsUseful(pixel)
        && (pixel & 0x00FFFFFFu) != 0u;
}

bool capture3dSourcePixelIsOpaqueBlack(melonDS::u32 pixel)
{
    return capture3dSourcePixelIsUseful(pixel)
        && (pixel & 0x00FFFFFFu) == 0u;
}

bool capture3dSourceLineIsSolidOpaqueBlack(const melonDS::u32* capture3dSource, int line)
{
    if (capture3dSource == nullptr || line < 0 || line >= kScreenHeight)
        return false;

    const size_t rowOffset = static_cast<size_t>(line) * static_cast<size_t>(kScreenWidth);
    for (int x = 0; x < kScreenWidth; x++)
    {
        if (capture3dSource[rowOffset + static_cast<size_t>(x)] != 0xFF000000u)
            return false;
    }

    return true;
}

VkWriteDescriptorSet makeImageDescriptorWrite(
    VkDescriptorSet descriptorSet,
    melonDS::u32 binding,
    const VkDescriptorImageInfo* imageInfo)
{
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descriptorSet;
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    write.pImageInfo = imageInfo;
    return write;
}

VkWriteDescriptorSet makeBufferDescriptorWrite(
    VkDescriptorSet descriptorSet,
    melonDS::u32 binding,
    const VkDescriptorBufferInfo* bufferInfo)
{
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descriptorSet;
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = bufferInfo;
    return write;
}

}

VulkanOutput::VulkanOutput()
    : lastValidTopPacked(kPackedScreenWordCount),
      lastValidBottomPacked(kPackedScreenWordCount)
{
}

VulkanOutput::~VulkanOutput()
{
    shutdown();
}

bool VulkanOutput::init()
{
    shutdown();

    if (!melonDS::VulkanContext::Get().Acquire())
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to acquire shared Vulkan context");
        return false;
    }

    contextAcquired = true;
    instance = melonDS::VulkanContext::Get().GetInstance();
    physicalDevice = melonDS::VulkanContext::Get().GetPhysicalDevice();
    device = melonDS::VulkanContext::Get().GetDevice();
    queue = melonDS::VulkanContext::Get().GetQueue();
    queueFamilyIndex = melonDS::VulkanContext::Get().GetQueueFamilyIndex();
    useTimelineSemaphores = melonDS::VulkanContext::Get().SupportsTimelineSemaphores();
    waitSemaphores = useTimelineSemaphores ? melonDS::VulkanContext::Get().GetWaitSemaphores() : nullptr;
    getSemaphoreCounterValue = useTimelineSemaphores ? melonDS::VulkanContext::Get().GetSemaphoreCounterValue() : nullptr;
    resetQueryPool = melonDS::VulkanContext::Get().GetResetQueryPool();
    timestampPeriodNs = melonDS::VulkanContext::Get().GetTimestampPeriod();
    timestampQueriesSupported = melonDS::VulkanContext::Get().SupportsTimestamps();

    if (useTimelineSemaphores && (waitSemaphores == nullptr || getSemaphoreCounterValue == nullptr))
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanOutput: timeline semaphore support reported but required functions are unavailable; using fence-based fallback"
        );
        useTimelineSemaphores = false;
        waitSemaphores = nullptr;
        getSemaphoreCounterValue = nullptr;
    }

    if (device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: shared context is incomplete");
        shutdown();
        return false;
    }

    if (!createSyncObjects() || !createCommandObjects() || !createCompositorResources() || !createAccumulateResources())
    {
        shutdown();
        return false;
    }

    initialized = true;
    timelineValue = 0;
    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Warn,
        "VulkanOutput: sync path initialized (timeline=%d)",
        useTimelineSemaphores ? 1 : 0
    );
    return true;
}

void VulkanOutput::shutdown()
{
    if (device != VK_NULL_HANDLE)
        vkDeviceWaitIdle(device);

    destroyFrameResources();
    destroyAccumulateResources();
    destroyCompositorResources();

    if (timelineSemaphore != VK_NULL_HANDLE)
    {
        vkDestroySemaphore(device, timelineSemaphore, nullptr);
        timelineSemaphore = VK_NULL_HANDLE;
    }

    if (commandPool != VK_NULL_HANDLE)
    {
        vkDestroyCommandPool(device, commandPool, nullptr);
        commandPool = VK_NULL_HANDLE;
    }

    if (contextAcquired)
    {
        melonDS::VulkanContext::Get().Release();
        contextAcquired = false;
    }

    instance = VK_NULL_HANDLE;
    physicalDevice = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    queueFamilyIndex = 0;
    waitSemaphores = nullptr;
    getSemaphoreCounterValue = nullptr;
    resetQueryPool = nullptr;
    timestampPeriodNs = 0.0f;
    timestampQueriesSupported = false;
    timelineValue = 0;
    lastPreparedFrame = nullptr;
    lastTopRendererSourceFrame = nullptr;
    lastBottomRendererSourceFrame = nullptr;
    lastTopComposedFrame = nullptr;
    lastBottomComposedFrame = nullptr;
    std::fill(lastValidTopPacked.begin(), lastValidTopPacked.end(), 0u);
    std::fill(lastValidBottomPacked.begin(), lastValidBottomPacked.end(), 0u);
    lastValidTopPackedAvailable = false;
    lastValidBottomPackedAvailable = false;
    lastPackedScreenSwapValid = false;
    lastPackedScreenSwap = false;
    framesSinceTopLive3D = 1024;
    framesSinceBottomLive3D = 1024;
    class4AsymmetricCadenceActive = false;
    class4AsymmetricCadencePhase = 0;
    class4BottomAboveHashValid = false;
    class4BottomAboveHash = 0;
    class4BottomAboveStableFrames = 0;
    class4BottomAboveMotionActive = false;
    class4NoAboveVramStructuredActive = false;
    lastValidCapture3dSource.fill(0);
    lastValidCapture3dSourceLines.fill(0);
    lastValidTopComp4Placeholder.fill(0);
    lastValidTopComp4PlaceholderLines.fill(0);
    lastValidBottomComp4Placeholder.fill(0);
    lastValidBottomComp4PlaceholderLines.fill(0);
    packedDebugLogsRemaining = 0;
    class4PairDebugLogsRemaining = 0;
    regularComp7PackedOwnerDebugLogsRemaining = 0;
    regularComp7PackedOwnerDebugActive = false;
    {
        std::lock_guard<std::mutex> lock(temporalStatsLock);
        temporalStats = {};
    }
    useTimelineSemaphores = false;
    initialized = false;
}

VulkanOutputTemporalStats VulkanOutput::takeTemporalStatsSnapshotAndReset()
{
    std::lock_guard<std::mutex> lock(temporalStatsLock);
    VulkanOutputTemporalStats snapshot = temporalStats;
    temporalStats = {};
    return snapshot;
}

void VulkanOutput::releaseTemporalFrameReferences()
{
    lastPreparedFrame = nullptr;
    lastTopRendererSourceFrame = nullptr;
    lastBottomRendererSourceFrame = nullptr;
    lastTopComposedFrame = nullptr;
    lastBottomComposedFrame = nullptr;
    lastValidTopPackedAvailable = false;
    lastValidBottomPackedAvailable = false;
    lastPackedScreenSwapValid = false;
    lastPackedScreenSwap = false;
    framesSinceTopLive3D = 1024;
    framesSinceBottomLive3D = 1024;
    class4AsymmetricCadenceActive = false;
    class4AsymmetricCadencePhase = 0;
    class4BottomAboveHashValid = false;
    class4BottomAboveHash = 0;
    class4BottomAboveStableFrames = 0;
    class4BottomAboveMotionActive = false;
    class4NoAboveVramStructuredActive = false;
    for (auto& [resourceFrame, resource] : resources)
    {
        (void)resourceFrame;
        resource.previousTopSourceFrame = nullptr;
        resource.previousTopSourcePending = false;
        resource.previousBottomSourceFrame = nullptr;
        resource.previousBottomSourcePending = false;
    }
}

void VulkanOutput::invalidateTemporalHistory()
{
    releaseTemporalFrameReferences();
    accumulatedTopHighresValid = false;
    accumulatedBottomHighresValid = false;
    lastValidTopPackedAvailable = false;
    lastValidBottomPackedAvailable = false;
    lastPackedScreenSwapValid = false;
    lastPackedScreenSwap = false;
    lastValidCapture3dSource.fill(0);
    lastValidCapture3dSourceLines.fill(0);
    lastValidTopComp4Placeholder.fill(0);
    lastValidTopComp4PlaceholderLines.fill(0);
    lastValidBottomComp4Placeholder.fill(0);
    lastValidBottomComp4PlaceholderLines.fill(0);
    packedDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 48u : 0u;
    class4PairDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 240u : 0u;
    regularComp7PackedOwnerDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 12u : 0u;
    structuredComp7HandoffDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 24u : 0u;
    ownershipIntroDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 360u : 0u;
    regularComp7PackedOwnerDebugActive = false;
    class4BottomAboveHashValid = false;
    class4BottomAboveHash = 0;
    class4BottomAboveStableFrames = 0;
    class4BottomAboveMotionActive = false;
    class4NoAboveVramStructuredActive = false;
    {
        std::lock_guard<std::mutex> lock(temporalStatsLock);
        temporalStats = {};
    }
}

void VulkanOutput::clearStructuredCaptureHistory()
{
    lastValidCapture3dSource.fill(0);
    lastValidCapture3dSourceLines.fill(0);
    lastValidTopComp4Placeholder.fill(0);
    lastValidTopComp4PlaceholderLines.fill(0);
    lastValidBottomComp4Placeholder.fill(0);
    lastValidBottomComp4PlaceholderLines.fill(0);
}

bool VulkanOutput::createSyncObjects()
{
    if (!useTimelineSemaphores)
        return true;

    VkSemaphoreTypeCreateInfo semaphoreTypeInfo{};
    semaphoreTypeInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    semaphoreTypeInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    semaphoreTypeInfo.initialValue = 0;

    VkSemaphoreCreateInfo semaphoreCreateInfo{};
    semaphoreCreateInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreCreateInfo.pNext = &semaphoreTypeInfo;

    if (vkCreateSemaphore(device, &semaphoreCreateInfo, nullptr, &timelineSemaphore) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create timeline semaphore");
        return false;
    }

    return true;
}

bool VulkanOutput::createCommandObjects()
{
    VkCommandPoolCreateInfo commandPoolCreateInfo{};
    commandPoolCreateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolCreateInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolCreateInfo.queueFamilyIndex = queueFamilyIndex;

    if (vkCreateCommandPool(device, &commandPoolCreateInfo, nullptr, &commandPool) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create command pool");
        return false;
    }

    return true;
}

bool VulkanOutput::createTimestampQueryPool(VkQueryPool& queryPool)
{
    if (!timestampQueriesSupported)
        return true;

    VkQueryPoolCreateInfo queryPoolCreateInfo{};
    queryPoolCreateInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryPoolCreateInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryPoolCreateInfo.queryCount = 2;

    if (vkCreateQueryPool(device, &queryPoolCreateInfo, nullptr, &queryPool) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Warn, "VulkanOutput: failed to create timestamp query pool");
        queryPool = VK_NULL_HANDLE;
    }

    return true;
}

void VulkanOutput::destroyTimestampQueryPool(VkQueryPool& queryPool)
{
    if (queryPool != VK_NULL_HANDLE)
    {
        vkDestroyQueryPool(device, queryPool, nullptr);
        queryPool = VK_NULL_HANDLE;
    }
}

bool VulkanOutput::createCompositorResources()
{
    VkDescriptorSetLayoutBinding outputBinding{};
    outputBinding.binding = 0;
    outputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    outputBinding.descriptorCount = 1;
    outputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding input3dBinding{};
    input3dBinding.binding = 1;
    input3dBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    input3dBinding.descriptorCount = 1;
    input3dBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding topPackedBinding{};
    topPackedBinding.binding = 2;
    topPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    topPackedBinding.descriptorCount = 1;
    topPackedBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding bottomPackedBinding{};
    bottomPackedBinding.binding = 3;
    bottomPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bottomPackedBinding.descriptorCount = 1;
    bottomPackedBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding previousTopInput3dBinding{};
    previousTopInput3dBinding.binding = 4;
    previousTopInput3dBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    previousTopInput3dBinding.descriptorCount = 1;
    previousTopInput3dBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding capture3dBinding{};
    capture3dBinding.binding = 5;
    capture3dBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    capture3dBinding.descriptorCount = 1;
    capture3dBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding previousBottomInput3dBinding{};
    previousBottomInput3dBinding.binding = 6;
    previousBottomInput3dBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    previousBottomInput3dBinding.descriptorCount = 1;
    previousBottomInput3dBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 7> compositorBindings = {
        outputBinding,
        input3dBinding,
        topPackedBinding,
        bottomPackedBinding,
        previousTopInput3dBinding,
        capture3dBinding,
        previousBottomInput3dBinding,
    };

    VkDescriptorSetLayoutCreateInfo descriptorSetLayoutCreateInfo{};
    descriptorSetLayoutCreateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorSetLayoutCreateInfo.bindingCount = static_cast<u32>(compositorBindings.size());
    descriptorSetLayoutCreateInfo.pBindings = compositorBindings.data();

    if (vkCreateDescriptorSetLayout(device, &descriptorSetLayoutCreateInfo, nullptr, &compositorDescriptorSetLayout) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create compositor descriptor set layout");
        return false;
    }

    std::array<VkDescriptorPoolSize, 2> descriptorPoolSizes{};
    descriptorPoolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    descriptorPoolSizes[0].descriptorCount = static_cast<u32>(FRAME_QUEUE_SIZE * 4);
    descriptorPoolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    descriptorPoolSizes[1].descriptorCount = static_cast<u32>(FRAME_QUEUE_SIZE * 3);

    VkDescriptorPoolCreateInfo descriptorPoolCreateInfo{};
    descriptorPoolCreateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    descriptorPoolCreateInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    descriptorPoolCreateInfo.maxSets = static_cast<u32>(FRAME_QUEUE_SIZE);
    descriptorPoolCreateInfo.poolSizeCount = static_cast<u32>(descriptorPoolSizes.size());
    descriptorPoolCreateInfo.pPoolSizes = descriptorPoolSizes.data();

    if (vkCreateDescriptorPool(device, &descriptorPoolCreateInfo, nullptr, &compositorDescriptorPool) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create compositor descriptor pool");
        return false;
    }

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(CompositorPushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutCreateInfo{};
    pipelineLayoutCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutCreateInfo.setLayoutCount = 1;
    pipelineLayoutCreateInfo.pSetLayouts = &compositorDescriptorSetLayout;
    pipelineLayoutCreateInfo.pushConstantRangeCount = 1;
    pipelineLayoutCreateInfo.pPushConstantRanges = &pushConstantRange;

    if (vkCreatePipelineLayout(device, &pipelineLayoutCreateInfo, nullptr, &compositorPipelineLayout) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create compositor pipeline layout");
        return false;
    }

    if (melonDS_android_vulkan_compositor_comp_spv_len == 0)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: compositor SPIR-V blob is empty");
        return false;
    }

    std::vector<u32> shaderWords((melonDS_android_vulkan_compositor_comp_spv_len + sizeof(u32) - 1u) / sizeof(u32));
    std::memcpy(shaderWords.data(), melonDS_android_vulkan_compositor_comp_spv, melonDS_android_vulkan_compositor_comp_spv_len);

    VkShaderModuleCreateInfo shaderModuleCreateInfo{};
    shaderModuleCreateInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderModuleCreateInfo.codeSize = melonDS_android_vulkan_compositor_comp_spv_len;
    shaderModuleCreateInfo.pCode = shaderWords.data();

    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device, &shaderModuleCreateInfo, nullptr, &shaderModule) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create compositor shader module");
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStageCreateInfo{};
    shaderStageCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStageCreateInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageCreateInfo.module = shaderModule;
    shaderStageCreateInfo.pName = "main";

    VkComputePipelineCreateInfo computePipelineCreateInfo{};
    computePipelineCreateInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    computePipelineCreateInfo.stage = shaderStageCreateInfo;
    computePipelineCreateInfo.layout = compositorPipelineLayout;

    const VkResult pipelineResult = vkCreateComputePipelines(
        device,
        VK_NULL_HANDLE,
        1,
        &computePipelineCreateInfo,
        nullptr,
        &compositorPipeline
    );

    vkDestroyShaderModule(device, shaderModule, nullptr);

    if (pipelineResult != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create compositor pipeline (%d)", static_cast<int>(pipelineResult));
        return false;
    }

    return true;
}

void VulkanOutput::destroyCompositorResources()
{
    if (compositorPipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(device, compositorPipeline, nullptr);
        compositorPipeline = VK_NULL_HANDLE;
    }

    if (compositorPipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(device, compositorPipelineLayout, nullptr);
        compositorPipelineLayout = VK_NULL_HANDLE;
    }

    if (compositorDescriptorPool != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorPool(device, compositorDescriptorPool, nullptr);
        compositorDescriptorPool = VK_NULL_HANDLE;
    }

    if (compositorDescriptorSetLayout != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorSetLayout(device, compositorDescriptorSetLayout, nullptr);
        compositorDescriptorSetLayout = VK_NULL_HANDLE;
    }
}

bool VulkanOutput::createAccumulateResources()
{
    VkDescriptorSetLayoutBinding sourceBinding{};
    sourceBinding.binding = 0;
    sourceBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    sourceBinding.descriptorCount = 1;
    sourceBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding destBinding{};
    destBinding.binding = 1;
    destBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    destBinding.descriptorCount = 1;
    destBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding topPackedBinding{};
    topPackedBinding.binding = 2;
    topPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    topPackedBinding.descriptorCount = 1;
    topPackedBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding bottomPackedBinding{};
    bottomPackedBinding.binding = 3;
    bottomPackedBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bottomPackedBinding.descriptorCount = 1;
    bottomPackedBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 4> bindings = {
        sourceBinding,
        destBinding,
        topPackedBinding,
        bottomPackedBinding,
    };

    VkDescriptorSetLayoutCreateInfo layoutCreateInfo{};
    layoutCreateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutCreateInfo.bindingCount = static_cast<u32>(bindings.size());
    layoutCreateInfo.pBindings = bindings.data();

    if (vkCreateDescriptorSetLayout(device, &layoutCreateInfo, nullptr, &accumulateDescriptorSetLayout) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create accumulate descriptor set layout");
        return false;
    }

    std::array<VkDescriptorPoolSize, 2> poolSizes{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 4;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[1].descriptorCount = 4;

    VkDescriptorPoolCreateInfo poolCreateInfo{};
    poolCreateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolCreateInfo.flags = 0;
    poolCreateInfo.maxSets = 2;
    poolCreateInfo.poolSizeCount = static_cast<u32>(poolSizes.size());
    poolCreateInfo.pPoolSizes = poolSizes.data();

    if (vkCreateDescriptorPool(device, &poolCreateInfo, nullptr, &accumulateDescriptorPool) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create accumulate descriptor pool");
        return false;
    }

    std::array<VkDescriptorSetLayout, 2> setLayouts = {
        accumulateDescriptorSetLayout,
        accumulateDescriptorSetLayout,
    };

    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = accumulateDescriptorPool;
    allocateInfo.descriptorSetCount = 2;
    allocateInfo.pSetLayouts = setLayouts.data();

    std::array<VkDescriptorSet, 2> sets = { VK_NULL_HANDLE, VK_NULL_HANDLE };
    if (vkAllocateDescriptorSets(device, &allocateInfo, sets.data()) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to allocate accumulate descriptor sets");
        return false;
    }
    accumulateTopDescriptorSet = sets[0];
    accumulateBottomDescriptorSet = sets[1];

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(AccumulatePushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutCreateInfo{};
    pipelineLayoutCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutCreateInfo.setLayoutCount = 1;
    pipelineLayoutCreateInfo.pSetLayouts = &accumulateDescriptorSetLayout;
    pipelineLayoutCreateInfo.pushConstantRangeCount = 1;
    pipelineLayoutCreateInfo.pPushConstantRanges = &pushConstantRange;

    if (vkCreatePipelineLayout(device, &pipelineLayoutCreateInfo, nullptr, &accumulatePipelineLayout) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create accumulate pipeline layout");
        return false;
    }

    if (melonDS_android_vulkan_accumulate_3d_comp_spv_len == 0)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: accumulate SPIR-V blob is empty");
        return false;
    }

    std::vector<u32> shaderWords((melonDS_android_vulkan_accumulate_3d_comp_spv_len + sizeof(u32) - 1u) / sizeof(u32));
    std::memcpy(shaderWords.data(), melonDS_android_vulkan_accumulate_3d_comp_spv, melonDS_android_vulkan_accumulate_3d_comp_spv_len);

    VkShaderModuleCreateInfo shaderModuleCreateInfo{};
    shaderModuleCreateInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderModuleCreateInfo.codeSize = melonDS_android_vulkan_accumulate_3d_comp_spv_len;
    shaderModuleCreateInfo.pCode = shaderWords.data();

    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device, &shaderModuleCreateInfo, nullptr, &shaderModule) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create accumulate shader module");
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStageCreateInfo{};
    shaderStageCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStageCreateInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageCreateInfo.module = shaderModule;
    shaderStageCreateInfo.pName = "main";

    VkComputePipelineCreateInfo computePipelineCreateInfo{};
    computePipelineCreateInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    computePipelineCreateInfo.stage = shaderStageCreateInfo;
    computePipelineCreateInfo.layout = accumulatePipelineLayout;

    const VkResult result = vkCreateComputePipelines(
        device,
        VK_NULL_HANDLE,
        1,
        &computePipelineCreateInfo,
        nullptr,
        &accumulatePipeline
    );

    vkDestroyShaderModule(device, shaderModule, nullptr);

    if (result != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create accumulate pipeline (%d)", static_cast<int>(result));
        return false;
    }

    return true;
}

void VulkanOutput::destroyAccumulatedHighresImage(VkImage& image, VkImageView& view, VkDeviceMemory& memory, bool& valid, bool& layoutReady)
{
    if (view != VK_NULL_HANDLE)
    {
        vkDestroyImageView(device, view, nullptr);
        view = VK_NULL_HANDLE;
    }
    if (image != VK_NULL_HANDLE)
    {
        vkDestroyImage(device, image, nullptr);
        image = VK_NULL_HANDLE;
    }
    if (memory != VK_NULL_HANDLE)
    {
        vkFreeMemory(device, memory, nullptr);
        memory = VK_NULL_HANDLE;
    }
    valid = false;
    layoutReady = false;
}

void VulkanOutput::destroyAccumulateResources()
{
    destroyAccumulatedHighresImage(
        accumulatedTopHighresImage,
        accumulatedTopHighresView,
        accumulatedTopHighresMemory,
        accumulatedTopHighresValid,
        accumulatedTopHighresLayoutReady);
    destroyAccumulatedHighresImage(
        accumulatedBottomHighresImage,
        accumulatedBottomHighresView,
        accumulatedBottomHighresMemory,
        accumulatedBottomHighresValid,
        accumulatedBottomHighresLayoutReady);
    accumulatedHighresWidth = 0;
    accumulatedHighresHeight = 0;
    cachedAccumulateTopSourceView = VK_NULL_HANDLE;
    cachedAccumulateBottomSourceView = VK_NULL_HANDLE;
    accumulateTopDescriptorReady = false;
    accumulateBottomDescriptorReady = false;
    accumulateTopDescriptorSet = VK_NULL_HANDLE;
    accumulateBottomDescriptorSet = VK_NULL_HANDLE;

    if (accumulatePipeline != VK_NULL_HANDLE)
    {
        vkDestroyPipeline(device, accumulatePipeline, nullptr);
        accumulatePipeline = VK_NULL_HANDLE;
    }
    if (accumulatePipelineLayout != VK_NULL_HANDLE)
    {
        vkDestroyPipelineLayout(device, accumulatePipelineLayout, nullptr);
        accumulatePipelineLayout = VK_NULL_HANDLE;
    }
    if (accumulateDescriptorPool != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorPool(device, accumulateDescriptorPool, nullptr);
        accumulateDescriptorPool = VK_NULL_HANDLE;
    }
    if (accumulateDescriptorSetLayout != VK_NULL_HANDLE)
    {
        vkDestroyDescriptorSetLayout(device, accumulateDescriptorSetLayout, nullptr);
        accumulateDescriptorSetLayout = VK_NULL_HANDLE;
    }
}

bool VulkanOutput::ensureAccumulatedHighresImages(u32 width, u32 height)
{
    if (width == 0 || height == 0)
        return false;

    if (accumulatedTopHighresImage != VK_NULL_HANDLE
        && accumulatedBottomHighresImage != VK_NULL_HANDLE
        && accumulatedHighresWidth == width
        && accumulatedHighresHeight == height)
        return true;

    destroyAccumulatedHighresImage(
        accumulatedTopHighresImage,
        accumulatedTopHighresView,
        accumulatedTopHighresMemory,
        accumulatedTopHighresValid,
        accumulatedTopHighresLayoutReady);
    destroyAccumulatedHighresImage(
        accumulatedBottomHighresImage,
        accumulatedBottomHighresView,
        accumulatedBottomHighresMemory,
        accumulatedBottomHighresValid,
        accumulatedBottomHighresLayoutReady);
    cachedAccumulateTopSourceView = VK_NULL_HANDLE;
    cachedAccumulateBottomSourceView = VK_NULL_HANDLE;
    accumulateTopDescriptorReady = false;
    accumulateBottomDescriptorReady = false;

    auto createOne = [&](VkImage& image, VkImageView& view, VkDeviceMemory& memory) -> bool {
        VkImageCreateInfo imageCreateInfo{};
        imageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
        imageCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageCreateInfo.extent = { width, height, 1 };
        imageCreateInfo.mipLevels = 1;
        imageCreateInfo.arrayLayers = 1;
        imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageCreateInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        if (vkCreateImage(device, &imageCreateInfo, nullptr, &image) != VK_SUCCESS)
            return false;

        VkMemoryRequirements memoryRequirements{};
        vkGetImageMemoryRequirements(device, image, &memoryRequirements);

        VkMemoryAllocateInfo memoryAllocateInfo{};
        memoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        memoryAllocateInfo.allocationSize = memoryRequirements.size;
        memoryAllocateInfo.memoryTypeIndex = findMemoryType(memoryRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        if (memoryAllocateInfo.memoryTypeIndex == UINT32_MAX
            || vkAllocateMemory(device, &memoryAllocateInfo, nullptr, &memory) != VK_SUCCESS)
        {
            vkDestroyImage(device, image, nullptr);
            image = VK_NULL_HANDLE;
            return false;
        }

        if (vkBindImageMemory(device, image, memory, 0) != VK_SUCCESS)
        {
            vkFreeMemory(device, memory, nullptr);
            memory = VK_NULL_HANDLE;
            vkDestroyImage(device, image, nullptr);
            image = VK_NULL_HANDLE;
            return false;
        }

        VkImageViewCreateInfo viewCreateInfo{};
        viewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewCreateInfo.image = image;
        viewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewCreateInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewCreateInfo.subresourceRange.levelCount = 1;
        viewCreateInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(device, &viewCreateInfo, nullptr, &view) != VK_SUCCESS)
        {
            vkFreeMemory(device, memory, nullptr);
            memory = VK_NULL_HANDLE;
            vkDestroyImage(device, image, nullptr);
            image = VK_NULL_HANDLE;
            return false;
        }
        return true;
    };

    if (!createOne(accumulatedTopHighresImage, accumulatedTopHighresView, accumulatedTopHighresMemory))
        return false;
    if (!createOne(accumulatedBottomHighresImage, accumulatedBottomHighresView, accumulatedBottomHighresMemory))
    {
        destroyAccumulatedHighresImage(
            accumulatedTopHighresImage,
            accumulatedTopHighresView,
            accumulatedTopHighresMemory,
            accumulatedTopHighresValid,
            accumulatedTopHighresLayoutReady);
        return false;
    }

    accumulatedHighresWidth = width;
    accumulatedHighresHeight = height;
    return true;
}

bool VulkanOutput::recordAccumulateMerge(FrameResource& resource, bool topLcd, bool replaceExisting)
{
    if (accumulatePipeline == VK_NULL_HANDLE)
        return false;
    if (resource.commandBuffer == VK_NULL_HANDLE)
        return false;
    if (!resource.hasRenderer3dSnapshot
        || resource.renderer3dSnapshotView == VK_NULL_HANDLE
        || resource.snapshotWidth == 0 || resource.snapshotHeight == 0)
        return false;
    if (resource.topPackedBuffer == VK_NULL_HANDLE
        || resource.bottomPackedBuffer == VK_NULL_HANDLE
        || resource.packedBufferSize == 0)
        return false;

    if (!ensureAccumulatedHighresImages(resource.snapshotWidth, resource.snapshotHeight))
        return false;

    VkImage destImage = topLcd ? accumulatedTopHighresImage : accumulatedBottomHighresImage;
    VkImageView destView = topLcd ? accumulatedTopHighresView : accumulatedBottomHighresView;
    bool& destValid = topLcd ? accumulatedTopHighresValid : accumulatedBottomHighresValid;
    bool& destLayoutReady = topLcd ? accumulatedTopHighresLayoutReady : accumulatedBottomHighresLayoutReady;
    VkDescriptorSet descriptorSet = topLcd ? accumulateTopDescriptorSet : accumulateBottomDescriptorSet;
    bool& descriptorReady = topLcd ? accumulateTopDescriptorReady : accumulateBottomDescriptorReady;
    VkImageView& cachedSourceView = topLcd ? cachedAccumulateTopSourceView : cachedAccumulateBottomSourceView;

    if (!descriptorReady || cachedSourceView != resource.renderer3dSnapshotView)
    {
        VkDescriptorImageInfo sourceImageInfo{};
        sourceImageInfo.imageView = resource.renderer3dSnapshotView;
        sourceImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        VkDescriptorImageInfo destImageInfo{};
        destImageInfo.imageView = destView;
        destImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        VkDescriptorBufferInfo topPackedBufferInfo{};
        topPackedBufferInfo.buffer = resource.topPackedBuffer;
        topPackedBufferInfo.offset = 0;
        topPackedBufferInfo.range = resource.packedBufferSize;

        VkDescriptorBufferInfo bottomPackedBufferInfo{};
        bottomPackedBufferInfo.buffer = resource.bottomPackedBuffer;
        bottomPackedBufferInfo.offset = 0;
        bottomPackedBufferInfo.range = resource.packedBufferSize;

        std::array<VkWriteDescriptorSet, 4> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = descriptorSet;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &sourceImageInfo;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = descriptorSet;
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &destImageInfo;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = descriptorSet;
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[2].pBufferInfo = &topPackedBufferInfo;
        writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[3].dstSet = descriptorSet;
        writes[3].dstBinding = 3;
        writes[3].descriptorCount = 1;
        writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[3].pBufferInfo = &bottomPackedBufferInfo;

        vkUpdateDescriptorSets(device, static_cast<u32>(writes.size()), writes.data(), 0, nullptr);
        cachedSourceView = resource.renderer3dSnapshotView;
        descriptorReady = true;
    }

    VkImageMemoryBarrier destToWriteBarrier{};
    destToWriteBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    destToWriteBarrier.srcAccessMask = destLayoutReady ? VK_ACCESS_SHADER_READ_BIT : 0;
    destToWriteBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    destToWriteBarrier.oldLayout = destLayoutReady ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    destToWriteBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    destToWriteBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destToWriteBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destToWriteBarrier.image = destImage;
    destToWriteBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    destToWriteBarrier.subresourceRange.levelCount = 1;
    destToWriteBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        destLayoutReady
            ? (VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &destToWriteBarrier
    );
    destLayoutReady = true;

    if (replaceExisting)
        destValid = false;

    if (!destValid)
    {
        VkClearColorValue clearColor{};
        VkImageSubresourceRange clearRange{};
        clearRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        clearRange.levelCount = 1;
        clearRange.layerCount = 1;
        vkCmdClearColorImage(
            resource.commandBuffer,
            destImage,
            VK_IMAGE_LAYOUT_GENERAL,
            &clearColor,
            1,
            &clearRange);

        VkImageMemoryBarrier clearToComputeBarrier{};
        clearToComputeBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        clearToComputeBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        clearToComputeBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        clearToComputeBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        clearToComputeBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        clearToComputeBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        clearToComputeBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        clearToComputeBarrier.image = destImage;
        clearToComputeBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        clearToComputeBarrier.subresourceRange.levelCount = 1;
        clearToComputeBarrier.subresourceRange.layerCount = 1;

        vkCmdPipelineBarrier(
            resource.commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0, nullptr,
            0, nullptr,
            1, &clearToComputeBarrier
        );
    }

    vkCmdBindPipeline(resource.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, accumulatePipeline);
    vkCmdBindDescriptorSets(
        resource.commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        accumulatePipelineLayout,
        0,
        1, &descriptorSet,
        0, nullptr
    );
    AccumulatePushConstants pushConstants{};
    pushConstants.scale = resource.snapshotWidth >= SoftPackedFrameSnapshot::kScreenWidth
        ? std::max<u32>(resource.snapshotWidth / static_cast<u32>(SoftPackedFrameSnapshot::kScreenWidth), 1u)
        : 1u;
    pushConstants.packedStride = kAcceleratedStride;
    pushConstants.topLcd = topLcd ? 1u : 0u;
    vkCmdPushConstants(
        resource.commandBuffer,
        accumulatePipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(pushConstants),
        &pushConstants);
    const u32 groupX = (accumulatedHighresWidth + 7u) / 8u;
    const u32 groupY = (accumulatedHighresHeight + 7u) / 8u;
    vkCmdDispatch(resource.commandBuffer, groupX, groupY, 1);

    VkImageMemoryBarrier destReadBarrier{};
    destReadBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    destReadBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    destReadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    destReadBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    destReadBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    destReadBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destReadBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    destReadBarrier.image = destImage;
    destReadBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    destReadBarrier.subresourceRange.levelCount = 1;
    destReadBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &destReadBarrier
    );

    destValid = true;
    return true;
}

u32 VulkanOutput::findMemoryType(u32 typeBits, VkMemoryPropertyFlags properties) const
{
    return melonDS::VulkanContext::Get().FindMemoryType(typeBits, properties);
}

bool VulkanOutput::createFrameResource(Frame* frame, u32 width, u32 height)
{
    std::scoped_lock commandLock(commandPoolLock);

    VkImageCreateInfo imageCreateInfo{};
    imageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    imageCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageCreateInfo.extent.width = width;
    imageCreateInfo.extent.height = height;
    imageCreateInfo.extent.depth = 1;
    imageCreateInfo.mipLevels = 1;
    imageCreateInfo.arrayLayers = 1;
    imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageCreateInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(device, &imageCreateInfo, nullptr, &image) != VK_SUCCESS)
    {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create frame image");
        return false;
    }

    VkMemoryRequirements imageRequirements{};
    vkGetImageMemoryRequirements(device, image, &imageRequirements);

    VkMemoryAllocateInfo imageMemoryAllocateInfo{};
    imageMemoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageMemoryAllocateInfo.allocationSize = imageRequirements.size;

    u32 imageMemoryType = findMemoryType(imageRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (imageMemoryType == UINT32_MAX)
        imageMemoryType = findMemoryType(imageRequirements.memoryTypeBits, 0);
    if (imageMemoryType == UINT32_MAX)
    {
        vkDestroyImage(device, image, nullptr);
        return false;
    }
    imageMemoryAllocateInfo.memoryTypeIndex = imageMemoryType;

    VkDeviceMemory imageMemory = VK_NULL_HANDLE;
    if (vkAllocateMemory(device, &imageMemoryAllocateInfo, nullptr, &imageMemory) != VK_SUCCESS)
    {
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    if (vkBindImageMemory(device, image, imageMemory, 0) != VK_SUCCESS)
    {
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkImageViewCreateInfo imageViewCreateInfo{};
    imageViewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    imageViewCreateInfo.image = image;
    imageViewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    imageViewCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageViewCreateInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageViewCreateInfo.subresourceRange.baseMipLevel = 0;
    imageViewCreateInfo.subresourceRange.levelCount = 1;
    imageViewCreateInfo.subresourceRange.baseArrayLayer = 0;
    imageViewCreateInfo.subresourceRange.layerCount = 1;

    VkImageView imageView = VK_NULL_HANDLE;
    if (vkCreateImageView(device, &imageViewCreateInfo, nullptr, &imageView) != VK_SUCCESS)
    {
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkDeviceSize stagingBufferSize = static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * 4;

    VkBufferCreateInfo stagingBufferCreateInfo{};
    stagingBufferCreateInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferCreateInfo.size = stagingBufferSize;
    stagingBufferCreateInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    stagingBufferCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    if (vkCreateBuffer(device, &stagingBufferCreateInfo, nullptr, &stagingBuffer) != VK_SUCCESS)
    {
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkMemoryRequirements stagingBufferRequirements{};
    vkGetBufferMemoryRequirements(device, stagingBuffer, &stagingBufferRequirements);

    VkMemoryAllocateInfo stagingMemoryAllocateInfo{};
    stagingMemoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingMemoryAllocateInfo.allocationSize = stagingBufferRequirements.size;
    stagingMemoryAllocateInfo.memoryTypeIndex = findMemoryType(
        stagingBufferRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    if (stagingMemoryAllocateInfo.memoryTypeIndex == UINT32_MAX)
    {
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (vkAllocateMemory(device, &stagingMemoryAllocateInfo, nullptr, &stagingMemory) != VK_SUCCESS)
    {
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    if (vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0) != VK_SUCCESS)
    {
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkCommandBufferAllocateInfo commandBufferAllocateInfo{};
    commandBufferAllocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferAllocateInfo.commandPool = commandPool;
    commandBufferAllocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferAllocateInfo.commandBufferCount = 1;

    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(device, &commandBufferAllocateInfo, &commandBuffer) != VK_SUCCESS)
    {
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkFenceCreateInfo fenceCreateInfo{};
    fenceCreateInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceCreateInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    VkFence submitFence = VK_NULL_HANDLE;
    if (vkCreateFence(device, &fenceCreateInfo, nullptr, &submitFence) != VK_SUCCESS)
    {
        vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    VkDescriptorSetAllocateInfo descriptorSetAllocateInfo{};
    descriptorSetAllocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    descriptorSetAllocateInfo.descriptorPool = compositorDescriptorPool;
    descriptorSetAllocateInfo.descriptorSetCount = 1;
    descriptorSetAllocateInfo.pSetLayouts = &compositorDescriptorSetLayout;

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(device, &descriptorSetAllocateInfo, &descriptorSet) != VK_SUCCESS)
    {
        vkDestroyFence(device, submitFence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    auto createMappedStorageBuffer = [&](VkBuffer& buffer, VkDeviceMemory& memory, void*& mappedMemory, VkDeviceSize size, const char* label) -> bool {
        VkBufferCreateInfo bufferCreateInfo{};
        bufferCreateInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferCreateInfo.size = size;
        bufferCreateInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufferCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        if (vkCreateBuffer(device, &bufferCreateInfo, nullptr, &buffer) != VK_SUCCESS)
        {
            melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to create %s packed buffer", label);
            return false;
        }

        VkMemoryRequirements memoryRequirements{};
        vkGetBufferMemoryRequirements(device, buffer, &memoryRequirements);

        VkMemoryAllocateInfo memoryAllocateInfo{};
        memoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        memoryAllocateInfo.allocationSize = memoryRequirements.size;
        memoryAllocateInfo.memoryTypeIndex = findMemoryType(
            memoryRequirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );

        if (memoryAllocateInfo.memoryTypeIndex == UINT32_MAX
            || vkAllocateMemory(device, &memoryAllocateInfo, nullptr, &memory) != VK_SUCCESS
            || vkBindBufferMemory(device, buffer, memory, 0) != VK_SUCCESS
            || vkMapMemory(device, memory, 0, size, 0, &mappedMemory) != VK_SUCCESS)
        {
            melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "VulkanOutput: failed to allocate %s storage buffer memory", label);
            if (mappedMemory != nullptr)
            {
                vkUnmapMemory(device, memory);
                mappedMemory = nullptr;
            }
            if (memory != VK_NULL_HANDLE)
            {
                vkFreeMemory(device, memory, nullptr);
                memory = VK_NULL_HANDLE;
            }
            if (buffer != VK_NULL_HANDLE)
            {
                vkDestroyBuffer(device, buffer, nullptr);
                buffer = VK_NULL_HANDLE;
            }
            return false;
        }

        return true;
    };

    VkBuffer topPackedBuffer = VK_NULL_HANDLE;
    VkDeviceMemory topPackedMemory = VK_NULL_HANDLE;
    void* topPackedMapped = nullptr;
    VkBuffer bottomPackedBuffer = VK_NULL_HANDLE;
    VkDeviceMemory bottomPackedMemory = VK_NULL_HANDLE;
    void* bottomPackedMapped = nullptr;
    VkBuffer capture3dBuffer = VK_NULL_HANDLE;
    VkDeviceMemory capture3dMemory = VK_NULL_HANDLE;
    void* capture3dMapped = nullptr;

    if (!createMappedStorageBuffer(topPackedBuffer, topPackedMemory, topPackedMapped, kPackedBufferSize, "top")
        || !createMappedStorageBuffer(bottomPackedBuffer, bottomPackedMemory, bottomPackedMapped, kPackedBufferSize, "bottom")
        || !createMappedStorageBuffer(capture3dBuffer, capture3dMemory, capture3dMapped, kCapture3dBufferSize, "capture3d"))
    {
        if (capture3dMapped != nullptr)
            vkUnmapMemory(device, capture3dMemory);
        if (capture3dMemory != VK_NULL_HANDLE)
            vkFreeMemory(device, capture3dMemory, nullptr);
        if (capture3dBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device, capture3dBuffer, nullptr);
        if (bottomPackedMapped != nullptr)
            vkUnmapMemory(device, bottomPackedMemory);
        if (bottomPackedMemory != VK_NULL_HANDLE)
            vkFreeMemory(device, bottomPackedMemory, nullptr);
        if (bottomPackedBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device, bottomPackedBuffer, nullptr);
        if (topPackedMapped != nullptr)
            vkUnmapMemory(device, topPackedMemory);
        if (topPackedMemory != VK_NULL_HANDLE)
            vkFreeMemory(device, topPackedMemory, nullptr);
        if (topPackedBuffer != VK_NULL_HANDLE)
            vkDestroyBuffer(device, topPackedBuffer, nullptr);
        vkFreeDescriptorSets(device, compositorDescriptorPool, 1, &descriptorSet);
        vkDestroyFence(device, submitFence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        return false;
    }

    auto resource = std::make_unique<FrameResource>();
    resource->image = image;
    resource->imageView = imageView;
    resource->imageMemory = imageMemory;
    resource->stagingBuffer = stagingBuffer;
    resource->stagingMemory = stagingMemory;
    resource->stagingSize = stagingBufferSize;
    resource->commandBuffer = commandBuffer;
    resource->submitFence = submitFence;
    resource->descriptorSet = descriptorSet;
    resource->topPackedBuffer = topPackedBuffer;
    resource->topPackedMemory = topPackedMemory;
    resource->topPackedMapped = topPackedMapped;
    resource->bottomPackedBuffer = bottomPackedBuffer;
    resource->bottomPackedMemory = bottomPackedMemory;
    resource->bottomPackedMapped = bottomPackedMapped;
    resource->capture3dBuffer = capture3dBuffer;
    resource->capture3dMemory = capture3dMemory;
    resource->capture3dMapped = capture3dMapped;
    resource->packedBufferSize = kPackedBufferSize;
    resource->renderer3dSnapshot = VK_NULL_HANDLE;
    resource->renderer3dSnapshotView = VK_NULL_HANDLE;
    resource->renderer3dSnapshotMemory = VK_NULL_HANDLE;
    resource->snapshotWidth = 0;
    resource->snapshotHeight = 0;
    resource->previousTopRendererSourceImage = VK_NULL_HANDLE;
    resource->previousTopRendererSourceImageView = VK_NULL_HANDLE;
    resource->previousTopSourceFrame = nullptr;
    resource->previousTopSourcePending = false;
    resource->previousBottomRendererSourceImage = VK_NULL_HANDLE;
    resource->previousBottomRendererSourceImageView = VK_NULL_HANDLE;
    resource->previousBottomSourceFrame = nullptr;
    resource->previousBottomSourcePending = false;
    resource->captureBackedClass4Only = false;
    resource->class4NoAboveVramStructuredPair = false;
    resource->class4PreservePackedVramValid = false;
    resource->class4PreservePackedVramScreenSwap = false;
    resource->topStructuredHandoffNoCurrent3d = false;
    resource->bottomStructuredHandoffNoCurrent3d = false;
    resource->topStructuredHandoffSuppress3d = false;
    resource->bottomStructuredHandoffSuppress3d = false;
    resource->topPackedCarryFromPrevious = false;
    resource->bottomPackedCarryFromPrevious = false;
    resource->topPureAlternatingVramCapture = false;
    resource->bottomPureAlternatingVramCapture = false;
    resource->submissionValue = 0;
    resource->width = width;
    resource->height = height;
    resource->hasContent = false;
    resource->hasPreparedInputs = false;
    resource->hasRenderer3dSnapshot = false;
    resource->renderer3dSnapshotScreenSwap = false;
    resource->hasPreparedCapture3dSource = false;
    resource->snapshotFromPreRun = false;
    resource->snapshotFromInitializedTarget = false;
    resource->snapshotFromGraphicsBackend = false;
    resource->descriptorSetReady = false;
    resource->timestampPending = false;
    resource->cachedRendererImageView = VK_NULL_HANDLE;
    resource->cachedPreviousTopRendererImageView = VK_NULL_HANDLE;
    resource->cachedPreviousBottomRendererImageView = VK_NULL_HANDLE;
    resource->preparedCapture3dSource.fill(0);

    (void)createTimestampQueryPool(resource->timestampQueryPool);

    const auto insertResult = resources.emplace(frame, std::move(*resource));
    if (!insertResult.second)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanOutput: frame resource unexpectedly already existed during creation");
        vkUnmapMemory(device, capture3dMemory);
        vkFreeMemory(device, capture3dMemory, nullptr);
        vkDestroyBuffer(device, capture3dBuffer, nullptr);
        vkUnmapMemory(device, bottomPackedMemory);
        vkFreeMemory(device, bottomPackedMemory, nullptr);
        vkDestroyBuffer(device, bottomPackedBuffer, nullptr);
        vkUnmapMemory(device, topPackedMemory);
        vkFreeMemory(device, topPackedMemory, nullptr);
        vkDestroyBuffer(device, topPackedBuffer, nullptr);
        vkFreeDescriptorSets(device, compositorDescriptorPool, 1, &descriptorSet);
        vkDestroyFence(device, submitFence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &commandBuffer);
        vkFreeMemory(device, stagingMemory, nullptr);
        vkDestroyBuffer(device, stagingBuffer, nullptr);
        vkDestroyImageView(device, imageView, nullptr);
        vkFreeMemory(device, imageMemory, nullptr);
        vkDestroyImage(device, image, nullptr);
        destroyTimestampQueryPool(resource->timestampQueryPool);
        return false;
    }

    frame->backend = FrameBackend::VulkanImage;
    frame->renderTimelineValue = 0;

    return true;
}

void VulkanOutput::destroyFrameResource(Frame* frame)
{
    std::scoped_lock commandLock(commandPoolLock);

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return;

    FrameResource& resource = iterator->second;

    if (resource.submitFence != VK_NULL_HANDLE)
        vkWaitForFences(device, 1, &resource.submitFence, VK_TRUE, UINT64_MAX);

    if (resource.descriptorSet != VK_NULL_HANDLE && compositorDescriptorPool != VK_NULL_HANDLE)
        vkFreeDescriptorSets(device, compositorDescriptorPool, 1, &resource.descriptorSet);

    destroyTimestampQueryPool(resource.timestampQueryPool);

    if (resource.submitFence != VK_NULL_HANDLE)
        vkDestroyFence(device, resource.submitFence, nullptr);

    if (resource.commandBuffer != VK_NULL_HANDLE && commandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(device, commandPool, 1, &resource.commandBuffer);

    if (resource.topPackedMapped != nullptr)
    {
        vkUnmapMemory(device, resource.topPackedMemory);
        resource.topPackedMapped = nullptr;
    }
    if (resource.topPackedBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, resource.topPackedBuffer, nullptr);
    if (resource.topPackedMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.topPackedMemory, nullptr);

    if (resource.bottomPackedMapped != nullptr)
    {
        vkUnmapMemory(device, resource.bottomPackedMemory);
        resource.bottomPackedMapped = nullptr;
    }
    if (resource.bottomPackedBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, resource.bottomPackedBuffer, nullptr);
    if (resource.bottomPackedMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.bottomPackedMemory, nullptr);

    if (resource.capture3dMapped != nullptr)
    {
        vkUnmapMemory(device, resource.capture3dMemory);
        resource.capture3dMapped = nullptr;
    }
    if (resource.capture3dBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, resource.capture3dBuffer, nullptr);
    if (resource.capture3dMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.capture3dMemory, nullptr);

    destroyRenderer3dSnapshot(resource);

    if (resource.stagingBuffer != VK_NULL_HANDLE)
        vkDestroyBuffer(device, resource.stagingBuffer, nullptr);
    if (resource.stagingMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.stagingMemory, nullptr);

    if (resource.imageView != VK_NULL_HANDLE)
        vkDestroyImageView(device, resource.imageView, nullptr);
    if (resource.image != VK_NULL_HANDLE)
        vkDestroyImage(device, resource.image, nullptr);
    if (resource.imageMemory != VK_NULL_HANDLE)
        vkFreeMemory(device, resource.imageMemory, nullptr);

    if (frame != nullptr)
    {
        frame->renderTimelineValue = 0;
    }

    if (lastPreparedFrame == frame)
        lastPreparedFrame = nullptr;
    if (lastTopRendererSourceFrame == frame)
        lastTopRendererSourceFrame = nullptr;
    if (lastBottomRendererSourceFrame == frame)
        lastBottomRendererSourceFrame = nullptr;
    if (lastTopComposedFrame == frame)
        lastTopComposedFrame = nullptr;
    if (lastBottomComposedFrame == frame)
        lastBottomComposedFrame = nullptr;

    resources.erase(iterator);
}

void VulkanOutput::destroyFrameResources()
{
    while (!resources.empty())
    {
        auto iterator = resources.begin();
        destroyFrameResource(iterator->first);
    }
}

bool VulkanOutput::ensureFrameResources(Frame* frame, u32 width, u32 height)
{
    if (!initialized || frame == nullptr || width == 0 || height == 0)
        return false;

    auto iterator = resources.find(frame);
    if (iterator != resources.end())
    {
        const FrameResource& resource = iterator->second;
        if (resource.width == width && resource.height == height)
        {
            frame->backend = FrameBackend::VulkanImage;
            return true;
        }

        destroyFrameResource(frame);
    }

    return createFrameResource(frame, width, height);
}

bool VulkanOutput::beginFrameCommand(FrameResource& resource, u64 waitTimeoutNs)
{
    const VkResult waitResult = vkWaitForFences(device, 1, &resource.submitFence, VK_TRUE, waitTimeoutNs);
    if (waitResult != VK_SUCCESS)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanOutput: beginFrameCommand fence wait failed (%d, timeoutNs=%llu)",
            static_cast<int>(waitResult),
            static_cast<unsigned long long>(waitTimeoutNs)
        );
        return false;
    }

    consumeFrameGpuTiming(resource);

    if (resource.timestampQueryPool != VK_NULL_HANDLE && resetQueryPool != nullptr)
        resetQueryPool(device, resource.timestampQueryPool, 0, 2);

    if (vkResetFences(device, 1, &resource.submitFence) != VK_SUCCESS)
        return false;

    if (vkResetCommandBuffer(resource.commandBuffer, 0) != VK_SUCCESS)
        return false;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    return vkBeginCommandBuffer(resource.commandBuffer, &beginInfo) == VK_SUCCESS;
}

bool VulkanOutput::submitFrameCommand(Frame* frame, FrameResource& resource, bool signalTimeline)
{
    if (vkEndCommandBuffer(resource.commandBuffer) != VK_SUCCESS)
        return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &resource.commandBuffer;

    u64 signalValue = resource.submissionValue;
    VkTimelineSemaphoreSubmitInfo timelineSubmitInfo{};
    const bool shouldSignalTimelineSemaphore = signalTimeline && useTimelineSemaphores && timelineSemaphore != VK_NULL_HANDLE;
    if (signalTimeline)
    {
        signalValue = ++timelineValue;
        if (shouldSignalTimelineSemaphore)
        {
            timelineSubmitInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
            timelineSubmitInfo.signalSemaphoreValueCount = 1;
            timelineSubmitInfo.pSignalSemaphoreValues = &signalValue;

            submitInfo.pNext = &timelineSubmitInfo;
            submitInfo.signalSemaphoreCount = 1;
            submitInfo.pSignalSemaphores = &timelineSemaphore;
        }
    }

    {
        std::scoped_lock queueLock(melonDS::VulkanContext::Get().GetQueueLock());
        if (vkQueueSubmit(queue, 1, &submitInfo, resource.submitFence) != VK_SUCCESS)
            return false;
    }

    if (frame != nullptr)
    {
        frame->backend = FrameBackend::VulkanImage;
        if (signalTimeline)
            frame->renderTimelineValue = signalValue;
    }

    if (signalTimeline)
        resource.submissionValue = signalValue;

    if (signalTimeline && resource.timestampQueryPool != VK_NULL_HANDLE)
        resource.timestampPending = true;

    return true;
}

bool VulkanOutput::updateCompositorPackedBuffers(
    Frame* frame,
    FrameResource& resource,
    const SoftPackedFrameSnapshot& softPackedSnapshot)
{
    if (!softPackedSnapshot.valid)
        return false;

    if (resource.topPackedMapped == nullptr || resource.bottomPackedMapped == nullptr || resource.packedBufferSize == 0)
        return false;

    auto* topPacked = static_cast<melonDS::u32*>(resource.topPackedMapped);
    auto* bottomPacked = static_cast<melonDS::u32*>(resource.bottomPackedMapped);
    if (topPacked == nullptr || bottomPacked == nullptr)
        return false;

    const bool topStructuredAboveDominant =
        screenUsesFullRegularComp7WithDominantAbove(softPackedSnapshot.topScreenStats);
    const bool bottomStructuredAboveDominant =
        screenUsesFullRegularComp7WithDominantAbove(softPackedSnapshot.bottomScreenStats);
    for (size_t y = 0; y < SoftPackedFrameSnapshot::kLineCount; y++)
    {
        const size_t packedRowBase = y * static_cast<size_t>(kAcceleratedStride);
        const size_t snapshotRowBase = y * SoftPackedFrameSnapshot::kScreenWidth;
        std::memcpy(
            topPacked + packedRowBase,
            softPackedSnapshot.packedTopPlane0.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        std::memcpy(
            topPacked + packedRowBase + SoftPackedFrameSnapshot::kScreenWidth,
            softPackedSnapshot.packedTopPlane1.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        std::memcpy(
            topPacked + packedRowBase + (SoftPackedFrameSnapshot::kScreenWidth * 2u),
            softPackedSnapshot.packedTopControl.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        topPacked[packedRowBase + (SoftPackedFrameSnapshot::kScreenWidth * 3u)] =
            softPackedSnapshot.packedTopLineMeta[y]
            | (topStructuredAboveDominant ? kMetaFlagStructuredAboveDominant : 0u);

        std::memcpy(
            bottomPacked + packedRowBase,
            softPackedSnapshot.packedBottomPlane0.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        std::memcpy(
            bottomPacked + packedRowBase + SoftPackedFrameSnapshot::kScreenWidth,
            softPackedSnapshot.packedBottomPlane1.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        std::memcpy(
            bottomPacked + packedRowBase + (SoftPackedFrameSnapshot::kScreenWidth * 2u),
            softPackedSnapshot.packedBottomControl.data() + snapshotRowBase,
            SoftPackedFrameSnapshot::kScreenWidth * sizeof(melonDS::u32));
        bottomPacked[packedRowBase + (SoftPackedFrameSnapshot::kScreenWidth * 3u)] =
            softPackedSnapshot.packedBottomLineMeta[y]
            | (bottomStructuredAboveDominant ? kMetaFlagStructuredAboveDominant : 0u);
    }

    const bool topStructuredHandoffNoCurrent3d =
        !softPackedSnapshot.hasCapture3dSource
        && screenUsesStructuredHandoffWithoutCurrent3d(
            softPackedSnapshot.topScreenStats,
            softPackedSnapshot.bottomScreenStats);
    const bool bottomStructuredHandoffNoCurrent3d =
        !softPackedSnapshot.hasCapture3dSource
        && screenUsesStructuredHandoffWithoutCurrent3d(
            softPackedSnapshot.bottomScreenStats,
            softPackedSnapshot.topScreenStats);
    const bool topPackedCarryState =
        (screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.topScreenStats)
            || screenUsesPlainStructured3dSlot(softPackedSnapshot.topScreenStats))
        && bottomStructuredHandoffNoCurrent3d;
    const bool bottomPackedCarryState =
        (screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.bottomScreenStats)
            || screenUsesPlainStructured3dSlot(softPackedSnapshot.bottomScreenStats))
        && topStructuredHandoffNoCurrent3d;
    const bool topPackedCarryFromPrevious =
        lastValidTopPackedAvailable
        && topPackedCarryState;
    const bool bottomPackedCarryFromPrevious =
        lastValidBottomPackedAvailable
        && bottomPackedCarryState;
    if (topPackedCarryFromPrevious)
    {
        std::memcpy(
            topPacked,
            lastValidTopPacked.data(),
            lastValidTopPacked.size() * sizeof(u32));
    }
    if (bottomPackedCarryFromPrevious)
    {
        std::memcpy(
            bottomPacked,
            lastValidBottomPacked.data(),
            lastValidBottomPacked.size() * sizeof(u32));
    }
    resource.topPackedCarryFromPrevious = topPackedCarryFromPrevious;
    resource.bottomPackedCarryFromPrevious = bottomPackedCarryFromPrevious;

    const auto screenHasReusablePacked2d = [](const SoftPackedScreenStats& stats) {
        return stats.RegularCaptureUses3dLines == 0u
            && stats.VramCaptureUses3dLines == 0u
            && stats.ForceLive3dCompMode7Lines == 0u
            && (stats.Plane0VisiblePixels > static_cast<u32>(kScreenWidth)
                || stats.Plane1VisiblePixels > static_cast<u32>(kScreenWidth)
                || stats.StructuredAboveVisiblePixels > static_cast<u32>(kScreenWidth)
                || stats.Structured2DOnlyVisiblePixels > static_cast<u32>(kScreenWidth));
    };
    if (topPackedCarryFromPrevious || screenHasReusablePacked2d(softPackedSnapshot.topScreenStats))
    {
        std::memcpy(
            lastValidTopPacked.data(),
            topPacked,
            lastValidTopPacked.size() * sizeof(u32));
        lastValidTopPackedAvailable = true;
    }
    if (bottomPackedCarryFromPrevious || screenHasReusablePacked2d(softPackedSnapshot.bottomScreenStats))
    {
        std::memcpy(
            lastValidBottomPacked.data(),
            bottomPacked,
            lastValidBottomPacked.size() * sizeof(u32));
        lastValidBottomPackedAvailable = true;
    }
    lastPackedScreenSwap = softPackedSnapshot.screenSwapLatched;
    lastPackedScreenSwapValid = true;
    if ((topPackedCarryFromPrevious || bottomPackedCarryFromPrevious)
        && areRendererDebugBgObjLogsEnabled()
        && structuredComp7HandoffDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[PackedCarry]: frameId=%u screenSwap=%u topCarry=%u bottomCarry=%u topNoCurrent=%u bottomNoCurrent=%u topStruct=%u topAbove=%u bottomStruct=%u bottomAbove=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            softPackedSnapshot.screenSwapLatched ? 1u : 0u,
            topPackedCarryFromPrevious ? 1u : 0u,
            bottomPackedCarryFromPrevious ? 1u : 0u,
            topStructuredHandoffNoCurrent3d ? 1u : 0u,
            bottomStructuredHandoffNoCurrent3d ? 1u : 0u,
            softPackedSnapshot.topScreenStats.StructuredSlotPixels,
            softPackedSnapshot.topScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
            softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels,
            structuredComp7HandoffDebugLogsRemaining);
        structuredComp7HandoffDebugLogsRemaining--;
    }

    resource.softPackedFrameId = softPackedSnapshot.frameId;
    resource.frontBufferLatched = softPackedSnapshot.frontBufferLatched;
    resource.captureBackedClass4Only = softPackedSnapshot.captureBackedClass4Only;
    resource.hasSoftPackedDebugData = true;
    resource.topScreenStats = softPackedSnapshot.topScreenStats;
    resource.bottomScreenStats = softPackedSnapshot.bottomScreenStats;
    resource.capture3dSourceDsFrame = softPackedSnapshot.capture3dSourceDsFrame;
    resource.captureLineUses3dMask = softPackedSnapshot.captureLineUses3dMask;
    resource.captureFallbackLines.fill(0);
    resource.comp4TopPlaceholder = softPackedSnapshot.comp4TopPlaceholder;
    resource.comp4BottomPlaceholder = softPackedSnapshot.comp4BottomPlaceholder;

    if (areRendererDebugBgObjLogsEnabled() && packedDebugLogsRemaining > 0)
    {
        const size_t topPlane1Index = 256u;
        const size_t topControlIndex = 512u;
        const size_t topCenterIndex = static_cast<size_t>(96) * static_cast<size_t>(kAcceleratedStride) + 128u;
        const size_t topCenterPlane1Index = topCenterIndex + 256u;
        const size_t topCenterControlIndex = topCenterIndex + 512u;
        const size_t bottomPlane1Index = 256u;
        const size_t bottomControlIndex = 512u;
        const size_t bottomCenterIndex = static_cast<size_t>(96) * static_cast<size_t>(kAcceleratedStride) + 128u;
        const size_t bottomCenterPlane1Index = bottomCenterIndex + 256u;
        const size_t bottomCenterControlIndex = bottomCenterIndex + 512u;
        const size_t metaIndex = 256u * 3u;
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanPacked[Frame]: frameId=%u front=%d screenSwap=%u top0=%08X top1=%08X topCtl=%08X topCenter0=%08X topCenter1=%08X topCenterCtl=%08X topMeta=%08X bottom0=%08X bottom1=%08X bottomCtl=%08X bottomCenter0=%08X bottomCenter1=%08X bottomCenterCtl=%08X bottomMeta=%08X remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            softPackedSnapshot.frontBufferLatched,
            softPackedSnapshot.screenSwapLatched ? 1u : 0u,
            topPacked[0],
            topPacked[topPlane1Index],
            topPacked[topControlIndex],
            topPacked[topCenterIndex],
            topPacked[topCenterPlane1Index],
            topPacked[topCenterControlIndex],
            topPacked[metaIndex],
            bottomPacked[0],
            bottomPacked[bottomPlane1Index],
            bottomPacked[bottomControlIndex],
            bottomPacked[bottomCenterIndex],
            bottomPacked[bottomCenterPlane1Index],
            bottomPacked[bottomCenterControlIndex],
            bottomPacked[metaIndex],
            packedDebugLogsRemaining
        );
        packedDebugLogsRemaining--;
    }

    return true;
}

void VulkanOutput::recordTemporalStats(
    const SoftPackedFrameSnapshot& softPackedSnapshot,
    const FrameResource& resource,
    bool topNeedsAccumulatedHighres,
    bool bottomNeedsAccumulatedHighres,
    bool topAccumulatorAvailable,
    bool bottomAccumulatorAvailable,
    bool packedScreenSwap,
    bool liveSourceScreenSwap,
    bool hasRenderer3dSnapshot,
    bool renderer3dSnapshotScreenSwap)
{
    constexpr u32 currentCaptureLineThreshold = kScreenHeight / 2u;
    const bool topStructuredSlot =
        softPackedSnapshot.topScreenStats.StructuredSlotPixels > static_cast<u32>(kScreenWidth);
    const bool bottomStructuredSlot =
        softPackedSnapshot.bottomScreenStats.StructuredSlotPixels > static_cast<u32>(kScreenWidth);
    const bool topRegularCapture =
        softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomRegularCapture =
        softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topVramCapture =
        softPackedSnapshot.topScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomVramCapture =
        softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topForceLiveCompMode7 =
        softPackedSnapshot.topScreenStats.ForceLive3dCompMode7Lines > currentCaptureLineThreshold;
    const bool bottomForceLiveCompMode7 =
        softPackedSnapshot.bottomScreenStats.ForceLive3dCompMode7Lines > currentCaptureLineThreshold;
    const bool topCaptureBackedComp4 =
        softPackedSnapshot.topScreenStats.CaptureBackedComp4Lines > currentCaptureLineThreshold;
    const bool bottomCaptureBackedComp4 =
        softPackedSnapshot.bottomScreenStats.CaptureBackedComp4Lines > currentCaptureLineThreshold;

    std::lock_guard<std::mutex> lock(temporalStatsLock);
    temporalStats.FramesPrepared++;
    if (softPackedSnapshot.hasCapture3dSource)
        temporalStats.FramesWithCapture3dSource++;
    if (topNeedsAccumulatedHighres)
        temporalStats.TopNeedsHighres++;
    if (bottomNeedsAccumulatedHighres)
        temporalStats.BottomNeedsHighres++;
    if (resource.previousTopRendererSourceValid)
        temporalStats.TopPreviousSourceValid++;
    if (resource.previousBottomRendererSourceValid)
        temporalStats.BottomPreviousSourceValid++;
    if (topNeedsAccumulatedHighres && !resource.previousTopRendererSourceValid)
        temporalStats.TopMissingHighresSource++;
    if (bottomNeedsAccumulatedHighres && !resource.previousBottomRendererSourceValid)
        temporalStats.BottomMissingHighresSource++;
    if (topStructuredSlot)
        temporalStats.TopStructuredSlot++;
    if (bottomStructuredSlot)
        temporalStats.BottomStructuredSlot++;
    if (topStructuredSlot && !topAccumulatorAvailable)
        temporalStats.TopStructuredMissingAccumulator++;
    if (bottomStructuredSlot && !bottomAccumulatorAvailable)
        temporalStats.BottomStructuredMissingAccumulator++;
    if (topAccumulatorAvailable)
        temporalStats.TopAccumulatorAvailable++;
    if (bottomAccumulatorAvailable)
        temporalStats.BottomAccumulatorAvailable++;
    if (topRegularCapture)
        temporalStats.TopRegularCapture++;
    if (bottomRegularCapture)
        temporalStats.BottomRegularCapture++;
    if (topVramCapture)
        temporalStats.TopVramCapture++;
    if (bottomVramCapture)
        temporalStats.BottomVramCapture++;
    if (topForceLiveCompMode7)
        temporalStats.TopForceLiveCompMode7++;
    if (bottomForceLiveCompMode7)
        temporalStats.BottomForceLiveCompMode7++;
    if (topCaptureBackedComp4)
        temporalStats.TopCaptureBackedComp4++;
    if (bottomCaptureBackedComp4)
        temporalStats.BottomCaptureBackedComp4++;
    if (packedScreenSwap)
        temporalStats.PackedTopOwner++;
    else
        temporalStats.PackedBottomOwner++;
    if (liveSourceScreenSwap)
        temporalStats.LiveTopOwner++;
    else
        temporalStats.LiveBottomOwner++;
    if (packedScreenSwap != liveSourceScreenSwap)
        temporalStats.LiveOwnerOverride++;
    if (hasRenderer3dSnapshot)
    {
        temporalStats.SnapshotFrames++;
        if (renderer3dSnapshotScreenSwap)
            temporalStats.SnapshotTopOwner++;
        else
            temporalStats.SnapshotBottomOwner++;
        if (renderer3dSnapshotScreenSwap != liveSourceScreenSwap)
            temporalStats.SnapshotOwnerDiffersFromLive++;
    }
    temporalStats.TopPlane0UsefulPixels += softPackedSnapshot.topScreenStats.Plane0UsefulPixels;
    temporalStats.TopPlane0VisiblePixels += softPackedSnapshot.topScreenStats.Plane0VisiblePixels;
    temporalStats.TopPlane0OpaqueBlackPixels += softPackedSnapshot.topScreenStats.Plane0OpaqueBlackPixels;
    temporalStats.TopPlane1UsefulPixels += softPackedSnapshot.topScreenStats.Plane1UsefulPixels;
    temporalStats.TopPlane1VisiblePixels += softPackedSnapshot.topScreenStats.Plane1VisiblePixels;
    temporalStats.TopPlane1OpaqueBlackPixels += softPackedSnapshot.topScreenStats.Plane1OpaqueBlackPixels;
    temporalStats.TopStructuredAboveVisiblePixels += softPackedSnapshot.topScreenStats.StructuredAboveVisiblePixels;
    temporalStats.TopStructuredAboveBlackPixels += softPackedSnapshot.topScreenStats.StructuredAboveBlackPixels;
    temporalStats.TopStructured2DOnlyVisiblePixels += softPackedSnapshot.topScreenStats.Structured2DOnlyVisiblePixels;
    temporalStats.TopProtectedBlackPixels += softPackedSnapshot.topScreenStats.ProtectedBlackPixels;
    temporalStats.BottomPlane0UsefulPixels += softPackedSnapshot.bottomScreenStats.Plane0UsefulPixels;
    temporalStats.BottomPlane0VisiblePixels += softPackedSnapshot.bottomScreenStats.Plane0VisiblePixels;
    temporalStats.BottomPlane0OpaqueBlackPixels += softPackedSnapshot.bottomScreenStats.Plane0OpaqueBlackPixels;
    temporalStats.BottomPlane1UsefulPixels += softPackedSnapshot.bottomScreenStats.Plane1UsefulPixels;
    temporalStats.BottomPlane1VisiblePixels += softPackedSnapshot.bottomScreenStats.Plane1VisiblePixels;
    temporalStats.BottomPlane1OpaqueBlackPixels += softPackedSnapshot.bottomScreenStats.Plane1OpaqueBlackPixels;
    temporalStats.BottomStructuredAboveVisiblePixels += softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels;
    temporalStats.BottomStructuredAboveBlackPixels += softPackedSnapshot.bottomScreenStats.StructuredAboveBlackPixels;
    temporalStats.BottomStructured2DOnlyVisiblePixels += softPackedSnapshot.bottomScreenStats.Structured2DOnlyVisiblePixels;
    temporalStats.BottomProtectedBlackPixels += softPackedSnapshot.bottomScreenStats.ProtectedBlackPixels;
}

bool VulkanOutput::prepareFrameForPresentation(
    Frame* frame,
    const melonDS::GPU& gpu,
    int frontBuffer,
    bool frameScreenSwap,
    SoftPackedFrameSnapshot& softPackedSnapshot,
    melonDS::VulkanRenderer3D& renderer3D)
{
    (void)gpu;
    (void)frontBuffer;
    if (!initialized || frame == nullptr || !renderer3D.HasColorTarget())
        return false;
    if (!softPackedSnapshot.valid
        || softPackedSnapshot.frontBufferLatched < 0
        || softPackedSnapshot.frontBufferLatched > 1)
    {
        return false;
    }

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;

    resource.screenSwap = softPackedSnapshot.valid ? softPackedSnapshot.screenSwapLatched : frameScreenSwap;
    const u64 packedUploadStartNs = PerfNowNs();
    if (!updateCompositorPackedBuffers(frame, resource, softPackedSnapshot))
        return false;
    packedUploadCpuWindow.Add(PerfNowNs() - packedUploadStartNs);
    const bool currentBackendIsGraphics =
        renderer3D.GetActiveBackendMode() == melonDS::VulkanRenderer3D::BackendMode::GraphicsHardware;
    const FrameResource* previousResource = nullptr;
    if (lastPreparedFrame != nullptr && lastPreparedFrame != frame)
    {
        const auto previousIt = resources.find(lastPreparedFrame);
        if (previousIt != resources.end())
            previousResource = &previousIt->second;
    }

    const bool snapshotNeedsCapture3dSource =
        softPackedSnapshotNeedsCapture3dSource(softPackedSnapshot);
    const bool currentFrameNeedsCapture3dSource = softPackedSnapshot.valid
        ? snapshotNeedsCapture3dSource
        : (packedBufferNeedsCapture3dSource(static_cast<const melonDS::u32*>(resource.topPackedMapped))
            || packedBufferNeedsCapture3dSource(static_cast<const melonDS::u32*>(resource.bottomPackedMapped))
            || snapshotNeedsCapture3dSource);

    if (!updatePreparedCapture3dSource(
            resource,
            softPackedSnapshot,
            previousResource,
            currentBackendIsGraphics,
            currentFrameNeedsCapture3dSource,
            renderer3D))
    {
        return false;
    }

    if (previousResource != nullptr)
    {
        const bool shouldCarryPreviousCapture3d =
            previousResource->hasPreparedCapture3dSource
            && previousResource->capture3dMapped != nullptr
            && !currentBackendIsGraphics;
        if (shouldCarryPreviousCapture3d)
        {
            const auto* previousCapture3d = static_cast<const u32*>(previousResource->capture3dMapped);
            auto* currentCapture3d = static_cast<u32*>(resource.capture3dMapped);
            if (!resource.hasPreparedCapture3dSource)
            {
                if (currentCapture3d != nullptr)
                    std::memcpy(currentCapture3d, previousCapture3d, static_cast<size_t>(kCapture3dBufferSize));
                resource.preparedCapture3dSource = previousResource->preparedCapture3dSource;
                resource.hasPreparedCapture3dSource = true;
                if (currentBackendIsGraphics && areRendererDebugBgObjLogsEnabled() && packedDebugLogsRemaining > 0)
                {
                    melonDS::Platform::Log(
                        melonDS::Platform::LogLevel::Warn,
                        "VulkanCapture3D[Carry]: reusedPrevious=1 currentNeedsCapture=%u remaining=%u",
                        currentFrameNeedsCapture3dSource ? 1u : 0u,
                        packedDebugLogsRemaining
                    );
                    packedDebugLogsRemaining--;
                }
            }
        }
    }

    if (currentBackendIsGraphics
        && currentFrameNeedsCapture3dSource
        && !resource.hasPreparedCapture3dSource
        && areRendererDebugBgObjLogsEnabled()
        && packedDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanCapture3D[Prepared]: graphicsMissingCurrent=1 front=%d remaining=%u",
            frontBuffer,
            packedDebugLogsRemaining
        );
        packedDebugLogsRemaining--;
    }

    bool hasStablePreviousPreparedFrame = false;
    if (lastPreparedFrame != nullptr
        && lastPreparedFrame != frame)
    {
        const auto previousIt = resources.find(lastPreparedFrame);
        if (previousIt != resources.end())
        {
            const FrameResource& previousResource = previousIt->second;
            hasStablePreviousPreparedFrame = previousResource.hasPreparedInputs
                && previousResource.hasRenderer3dSnapshot
                && previousResource.renderer3dSnapshot != VK_NULL_HANDLE
                && previousResource.renderer3dSnapshotView != VK_NULL_HANDLE;
        }
    }

    constexpr u32 currentCaptureLineThreshold = kScreenHeight / 2u;
    const bool topUsesRegularCapture3d =
        softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topUsesVramCapture3d =
        softPackedSnapshot.topScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomUsesRegularCapture3d =
        softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomUsesVramCapture3d =
        softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topUsesCurrentCapture3d = topUsesRegularCapture3d || topUsesVramCapture3d;
    const bool bottomUsesCurrentCapture3d = bottomUsesRegularCapture3d || bottomUsesVramCapture3d;
    constexpr u32 dominantStructuredSlotThreshold = (kScreenWidth * kScreenHeight) / 2u;
    const bool topUsesStructured3d =
        softPackedSnapshot.topScreenStats.StructuredSlotPixels > dominantStructuredSlotThreshold;
    const bool bottomUsesStructured3d =
        softPackedSnapshot.bottomScreenStats.StructuredSlotPixels > dominantStructuredSlotThreshold;
    const bool backendRenderScreenSwap = currentBackendIsGraphics
        ? renderer3D.GetCurrentRenderScreenSwap()
        : resource.screenSwap;
    const bool class4VramStructuredPair =
        currentBackendIsGraphics
        && softPackedSnapshot.captureBackedClass4Only
        && !topUsesRegularCapture3d
        && !bottomUsesRegularCapture3d
        && (topUsesVramCapture3d != bottomUsesVramCapture3d)
        && (topUsesStructured3d != bottomUsesStructured3d);
    const bool topStructuredAboveInClass4Pair =
        class4VramStructuredPair
        && topUsesStructured3d
        && softPackedSnapshot.topScreenStats.StructuredAbovePixels > 0u;
    const bool bottomStructuredAboveInClass4Pair =
        class4VramStructuredPair
        && bottomUsesStructured3d
        && softPackedSnapshot.bottomScreenStats.StructuredAbovePixels > 0u;
    const bool class4NoAboveVramStructuredPairBase =
        class4VramStructuredPair
        && !topStructuredAboveInClass4Pair
        && !bottomStructuredAboveInClass4Pair
        && ((topUsesStructured3d && bottomUsesVramCapture3d)
            || (topUsesVramCapture3d && bottomUsesStructured3d));
    const bool class4PreservePackedTopVram =
        class4VramStructuredPair
        && topUsesVramCapture3d
        && bottomStructuredAboveInClass4Pair;
    const bool class4PreservePackedBottomVram =
        class4VramStructuredPair
        && bottomUsesVramCapture3d
        && topStructuredAboveInClass4Pair;
    const bool topVramPackedHasVisibleContent =
        softPackedSnapshot.topScreenStats.Plane0VisiblePixels > 0u
        || softPackedSnapshot.topScreenStats.Plane1VisiblePixels > 0u
        || softPackedSnapshot.topScreenStats.StructuredAboveVisiblePixels > 0u
        || softPackedSnapshot.topScreenStats.Structured2DOnlyVisiblePixels > 0u;
    const bool bottomVramPackedHasVisibleContent =
        softPackedSnapshot.bottomScreenStats.Plane0VisiblePixels > 0u
        || softPackedSnapshot.bottomScreenStats.Plane1VisiblePixels > 0u
        || softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels > 0u
        || softPackedSnapshot.bottomScreenStats.Structured2DOnlyVisiblePixels > 0u;
    constexpr u32 class4SmallStructuredAboveVisibleThreshold = kScreenWidth * 8u;
    const bool class4SmallBottomAboveNoAboveMarker =
        class4VramStructuredPair
        && topUsesVramCapture3d
        && bottomUsesStructured3d
        && !topStructuredAboveInClass4Pair
        && bottomStructuredAboveInClass4Pair
        && topVramPackedHasVisibleContent
        && softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels > 0u
        && softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels <= class4SmallStructuredAboveVisibleThreshold;
    const bool class4ZeroAboveTopVramStructuredMarker =
        class4VramStructuredPair
        && topUsesVramCapture3d
        && bottomUsesStructured3d
        && !topStructuredAboveInClass4Pair
        && !bottomStructuredAboveInClass4Pair
        && topVramPackedHasVisibleContent;
    const bool class4LargeBottomAboveMarker =
        class4VramStructuredPair
        && topUsesVramCapture3d
        && bottomUsesStructured3d
        && softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels > class4SmallStructuredAboveVisibleThreshold;
    if (class4LargeBottomAboveMarker)
        class4NoAboveVramStructuredActive = false;
    if (class4SmallBottomAboveNoAboveMarker || class4ZeroAboveTopVramStructuredMarker)
        class4NoAboveVramStructuredActive = true;
    const bool class4NoAboveVramStructuredActiveForFrame = class4NoAboveVramStructuredActive;
    const bool class4NoAboveVramStructuredPair =
        class4NoAboveVramStructuredPairBase
        && class4NoAboveVramStructuredActiveForFrame;
    const auto computeBottomStructuredAboveHash = [&softPackedSnapshot]() {
        u64 hash = 1469598103934665603ull;
        u32 pixels = 0;
        for (size_t index = 0; index < SoftPackedFrameSnapshot::kPixelCount; index++)
        {
            const u32 control = softPackedSnapshot.packedBottomControl[index];
            const u32 controlAlpha = control >> 24u;
            const bool structuredAbove =
                (controlAlpha & 0x40u) != 0u
                && (controlAlpha & 0x80u) != 0u
                && softPackedSnapshot.packedBottomPlane1[index] != 0u;
            if (!structuredAbove)
                continue;

            hash ^= static_cast<u64>(index);
            hash *= 1099511628211ull;
            hash ^= static_cast<u64>(softPackedSnapshot.packedBottomPlane1[index]);
            hash *= 1099511628211ull;
            hash ^= static_cast<u64>(control);
            hash *= 1099511628211ull;
            pixels++;
        }
        return std::pair<u64, u32>{hash, pixels};
    };
    bool bottomStructuredAboveChanged = false;
    bool bottomStructuredAboveHashSampled = false;
    const bool class4BottomDominantAsymmetricBaseSample =
        class4VramStructuredPair
        && topUsesVramCapture3d
        && bottomUsesStructured3d
        && !topStructuredAboveInClass4Pair
        && topVramPackedHasVisibleContent;
    const bool class4BottomDominantAsymmetricSample =
        class4BottomDominantAsymmetricBaseSample
        && bottomStructuredAboveInClass4Pair;
    if (class4BottomDominantAsymmetricSample)
    {
        const auto [bottomAboveHash, bottomAbovePixels] = computeBottomStructuredAboveHash();
        bottomStructuredAboveHashSampled = bottomAbovePixels > 0u;
        if (bottomStructuredAboveHashSampled)
        {
            bottomStructuredAboveChanged =
                class4BottomAboveHashValid
                && bottomAboveHash != class4BottomAboveHash;
            if (class4BottomAboveHashValid && bottomAboveHash == class4BottomAboveHash)
            {
                if (class4BottomAboveStableFrames < 1024u)
                    class4BottomAboveStableFrames++;
            }
            else
            {
                class4BottomAboveStableFrames = 0;
            }
            class4BottomAboveHash = bottomAboveHash;
            class4BottomAboveHashValid = true;
            class4BottomAboveMotionActive =
                bottomStructuredAboveChanged
                || (class4BottomAboveMotionActive
                    && class4BottomAboveStableFrames < kClass4StructuredAboveStableSamplesFor30Fps);
        }
        else
        {
            class4BottomAboveMotionActive = false;
        }
    }
    else if (class4BottomDominantAsymmetricBaseSample)
    {
        class4BottomAboveHashValid = false;
        class4BottomAboveHash = 0;
        class4BottomAboveStableFrames = 0;
        class4BottomAboveMotionActive = false;
    }
    const bool bottomStructuredAboveTransitionActive =
        class4BottomAboveMotionActive;
    const bool class4BottomDominantAsymmetricMarker =
        class4BottomDominantAsymmetricSample
        && bottomStructuredAboveTransitionActive;
    const bool class4BottomDominantAsymmetricCarry =
        class4VramStructuredPair
        && bottomUsesVramCapture3d
        && topUsesStructured3d
        && class4BottomAboveMotionActive;
    const bool class4AsymmetricBottomDominantPair =
        class4BottomDominantAsymmetricMarker
        || class4BottomDominantAsymmetricCarry;
    const bool class4NoAbovePreservePackedTopVram =
        class4NoAboveVramStructuredPair
        && topUsesVramCapture3d
        && topVramPackedHasVisibleContent;
    const bool class4NoAbovePreservePackedBottomVram =
        class4NoAboveVramStructuredPair
        && bottomUsesVramCapture3d
        && bottomVramPackedHasVisibleContent;
    const bool class4PreservePackedTopVramFinal =
        (class4PreservePackedTopVram
            && (!class4BottomDominantAsymmetricBaseSample || bottomStructuredAboveTransitionActive))
        || class4NoAbovePreservePackedTopVram;
    const bool class4PreservePackedBottomVramFinal =
        class4PreservePackedBottomVram
        || class4NoAbovePreservePackedBottomVram;
    resource.class4PreservePackedVramValid =
        class4PreservePackedTopVramFinal || class4PreservePackedBottomVramFinal;
    resource.class4PreservePackedVramScreenSwap = class4PreservePackedTopVramFinal;
    resource.class4NoAboveVramStructuredPair = class4NoAboveVramStructuredPair;
    const bool topUsesFullRegularComp7 =
        screenUsesFullRegularComp7(softPackedSnapshot.topScreenStats);
    const bool bottomUsesFullRegularComp7 =
        screenUsesFullRegularComp7(softPackedSnapshot.bottomScreenStats);
    const bool topUsesPlainStructuredComp7Slot =
        screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.topScreenStats);
    const bool bottomUsesPlainStructuredComp7Slot =
        screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.bottomScreenStats);
    const bool topUsesPlainStructured3dSlot =
        screenUsesPlainStructured3dSlot(softPackedSnapshot.topScreenStats);
    const bool bottomUsesPlainStructured3dSlot =
        screenUsesPlainStructured3dSlot(softPackedSnapshot.bottomScreenStats);
    const bool topUsesFullStructured2dOnlyDisplay =
        screenUsesFullStructured2dOnlyDisplay(softPackedSnapshot.topScreenStats);
    const bool bottomUsesFullStructured2dOnlyDisplay =
        screenUsesFullStructured2dOnlyDisplay(softPackedSnapshot.bottomScreenStats);
    resource.screenSwapToggledFromPrevious =
        previousResource != nullptr
        && previousResource->screenSwap != resource.screenSwap;
    const bool asymmetricFullRegularComp7 =
        topUsesFullRegularComp7 != bottomUsesFullRegularComp7;
    const bool preservePackedOwnerForPlainRegularComp7Pair =
        currentBackendIsGraphics
        && topUsesFullRegularComp7
        && !bottomUsesFullRegularComp7
        && screenUsesPlainFullComp4(softPackedSnapshot.bottomScreenStats)
        && !screenHasVisibleStructured2d(softPackedSnapshot.topScreenStats)
        && !screenHasVisibleStructured2d(softPackedSnapshot.bottomScreenStats);
    const bool preservePackedOwnerForAlternatingPlainStructuredComp7 =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && (topUsesPlainStructuredComp7Slot || bottomUsesPlainStructuredComp7Slot)
        && topUsesCurrentCapture3d != bottomUsesCurrentCapture3d;
    const bool topVramBottomPureStructuredComp7Pair =
        currentBackendIsGraphics
        && class4VramStructuredPair
        && topUsesVramCapture3d
        && !bottomUsesVramCapture3d
        && bottomUsesPlainStructuredComp7Slot
        && screenUsesFullVramCaptureOnly(softPackedSnapshot.topScreenStats)
        && !resource.screenSwap;
    const bool preservePackedOwnerForTopVramBottomPlainStructuredComp7 =
        currentBackendIsGraphics
        && class4VramStructuredPair
        && topUsesVramCapture3d
        && !bottomUsesVramCapture3d
        && bottomUsesPlainStructuredComp7Slot
        && softPackedSnapshot.topScreenStats.DisplayModeCounts[2] == kScreenHeight
        && softPackedSnapshot.bottomScreenStats.DisplayModeCounts[1] == kScreenHeight
        && softPackedSnapshot.topScreenStats.StructuredSlotPixels == 0u
        && softPackedSnapshot.bottomScreenStats.StructuredAbovePixels == 0u
        && !resource.screenSwap
        && !topVramBottomPureStructuredComp7Pair;
    const bool topStructuredSlotUsesPreviousWhileBottom2dOnly =
        currentBackendIsGraphics
        && !softPackedSnapshot.hasCapture3dSource
        && resource.screenSwapToggledFromPrevious
        && topUsesPlainStructured3dSlot
        && bottomUsesFullStructured2dOnlyDisplay
        && accumulatedTopHighresValid;
    const bool bottomStructuredSlotUsesPreviousWhileTop2dOnly =
        currentBackendIsGraphics
        && !softPackedSnapshot.hasCapture3dSource
        && resource.screenSwapToggledFromPrevious
        && bottomUsesPlainStructured3dSlot
        && topUsesFullStructured2dOnlyDisplay
        && accumulatedBottomHighresValid;
    const bool topStructuredHandoffOverlay =
        screenHasStructuredHandoffOverlay(softPackedSnapshot.topScreenStats);
    const bool bottomStructuredHandoffOverlay =
        screenHasStructuredHandoffOverlay(softPackedSnapshot.bottomScreenStats);
    const bool topStructuredHandoffNoCurrent3d =
        currentBackendIsGraphics
        && !softPackedSnapshot.hasCapture3dSource
        && screenUsesStructuredHandoffWithoutCurrent3d(
            softPackedSnapshot.topScreenStats,
            softPackedSnapshot.bottomScreenStats);
    const bool bottomStructuredHandoffNoCurrent3d =
        currentBackendIsGraphics
        && !softPackedSnapshot.hasCapture3dSource
        && screenUsesStructuredHandoffWithoutCurrent3d(
            softPackedSnapshot.bottomScreenStats,
            softPackedSnapshot.topScreenStats);
    resource.topStructuredHandoffNoCurrent3d = topStructuredHandoffNoCurrent3d;
    resource.bottomStructuredHandoffNoCurrent3d = bottomStructuredHandoffNoCurrent3d;
    bool topStructuredHandoffSuppress3d = false;
    bool bottomStructuredHandoffSuppress3d = false;
    const bool topStructuredHandoffOverlayHasNoCurrent3dSource =
        currentBackendIsGraphics
        && topStructuredHandoffNoCurrent3d
        && topStructuredHandoffOverlay
        && (screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.bottomScreenStats)
            || screenUsesFullStructured2dOnlyDisplay(softPackedSnapshot.bottomScreenStats)
            || screenUsesPlainStructured3dSlot(softPackedSnapshot.bottomScreenStats))
        && softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines == 0u
        && softPackedSnapshot.topScreenStats.VramCaptureUses3dLines == 0u
        && softPackedSnapshot.topScreenStats.ForceLive3dCompMode7Lines == 0u;
    const bool bottomStructuredHandoffOverlayHasNoCurrent3dSource =
        currentBackendIsGraphics
        && bottomStructuredHandoffNoCurrent3d
        && bottomStructuredHandoffOverlay
        && (screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.topScreenStats)
            || screenUsesFullStructured2dOnlyDisplay(softPackedSnapshot.topScreenStats)
            || screenUsesPlainStructured3dSlot(softPackedSnapshot.topScreenStats))
        && softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines == 0u
        && softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines == 0u
        && softPackedSnapshot.bottomScreenStats.ForceLive3dCompMode7Lines == 0u;
    bool liveSourceScreenSwap = resource.screenSwap;
    if (topStructuredSlotUsesPreviousWhileBottom2dOnly)
    {
        liveSourceScreenSwap = false;
    }
    else if (bottomStructuredSlotUsesPreviousWhileTop2dOnly)
    {
        liveSourceScreenSwap = true;
    }
    else if (preservePackedOwnerForTopVramBottomPlainStructuredComp7)
    {
        liveSourceScreenSwap = resource.screenSwap;
    }
    else if (class4VramStructuredPair)
    {
        liveSourceScreenSwap = topUsesVramCapture3d;
    }
    else if (preservePackedOwnerForAlternatingPlainStructuredComp7)
    {
        liveSourceScreenSwap = resource.screenSwap;
    }
    else if (asymmetricFullRegularComp7
        && !preservePackedOwnerForPlainRegularComp7Pair)
    {
        liveSourceScreenSwap = topUsesFullRegularComp7;
    }
    else if (topUsesCurrentCapture3d != bottomUsesCurrentCapture3d
        && !preservePackedOwnerForPlainRegularComp7Pair)
    {
        liveSourceScreenSwap = topUsesCurrentCapture3d;
    }
    else if (topUsesVramCapture3d
        && !topUsesRegularCapture3d
        && bottomUsesRegularCapture3d)
    {
        liveSourceScreenSwap = false;
    }
    else if (bottomUsesVramCapture3d
        && !bottomUsesRegularCapture3d
        && topUsesRegularCapture3d)
    {
        liveSourceScreenSwap = true;
    }
    const bool topPlainStructuredComp7UsesOppositeLive3d =
        currentBackendIsGraphics
        && topUsesPlainStructuredComp7Slot
        && !topUsesCurrentCapture3d
        && bottomUsesCurrentCapture3d
        && !liveSourceScreenSwap;
    const bool bottomPlainStructuredComp7UsesOppositeLive3d =
        currentBackendIsGraphics
        && bottomUsesPlainStructuredComp7Slot
        && !bottomUsesCurrentCapture3d
        && topUsesCurrentCapture3d
        && liveSourceScreenSwap;
    const bool topPlainStructuredComp7PureAlternatingVramPair =
        topPlainStructuredComp7UsesOppositeLive3d
        && preservePackedOwnerForAlternatingPlainStructuredComp7
        && screenUsesFullVramCaptureOnly(softPackedSnapshot.bottomScreenStats);
    const bool bottomPlainStructuredComp7PureAlternatingVramPair =
        bottomPlainStructuredComp7UsesOppositeLive3d
        && preservePackedOwnerForAlternatingPlainStructuredComp7
        && screenUsesFullVramCaptureOnly(softPackedSnapshot.topScreenStats);
    const bool topPlainStructuredComp7PureAlternatingVramCadenceCarry =
        topPlainStructuredComp7PureAlternatingVramPair
        && class4BottomDominantAsymmetricCarry
        && bottomStructuredAboveTransitionActive
        && bottomUsesVramCapture3d
        && topUsesStructured3d
        && topUsesPlainStructuredComp7Slot;
    resource.topPureAlternatingVramCapture = bottomPlainStructuredComp7PureAlternatingVramPair;
    resource.bottomPureAlternatingVramCapture = topPlainStructuredComp7PureAlternatingVramPair;
    const bool topUsesScreenWideCaptureBackedComp4 =
        softPackedSnapshot.topScreenStats.CaptureBackedComp4Lines > currentCaptureLineThreshold;
    const bool bottomUsesScreenWideCaptureBackedComp4 =
        softPackedSnapshot.bottomScreenStats.CaptureBackedComp4Lines > currentCaptureLineThreshold;
    const bool frameHasExplicitCurrent3dSource =
        softPackedSnapshot.hasCapture3dSource
        || softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.topScreenStats.VramCaptureUses3dLines > 0u
        || softPackedSnapshot.topScreenStats.ForceLive3dCompMode7Lines > 0u
        || softPackedSnapshot.topScreenStats.StructuredSlotPixels > static_cast<u32>(kScreenWidth)
        || softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines > 0u
        || softPackedSnapshot.bottomScreenStats.ForceLive3dCompMode7Lines > 0u
        || softPackedSnapshot.bottomScreenStats.StructuredSlotPixels > static_cast<u32>(kScreenWidth);
    const bool needsDsTimedCaptureBackedComp4Source =
        currentBackendIsGraphics
        && !frameHasExplicitCurrent3dSource
        && (topUsesScreenWideCaptureBackedComp4 || bottomUsesScreenWideCaptureBackedComp4);
    if (needsDsTimedCaptureBackedComp4Source
        && topUsesScreenWideCaptureBackedComp4 != bottomUsesScreenWideCaptureBackedComp4)
    {
        liveSourceScreenSwap = topUsesScreenWideCaptureBackedComp4;
    }
    u32 class4AsymmetricCadencePhaseForFrame = class4AsymmetricCadencePhase & 3u;
    bool class4AsymmetricCadenceAllowsTop = true;
    bool class4AsymmetricCadenceSuppressesTop = false;
    const bool class4AsymmetricCadenceWasActive = class4AsymmetricCadenceActive;
    if (class4VramStructuredPair
        && areRendererDebugBgObjLogsEnabled()
        && class4PairDebugLogsRemaining == 0)
    {
        class4PairDebugLogsRemaining = 600;
    }
    if (preservePackedOwnerForPlainRegularComp7Pair)
    {
        if (!regularComp7PackedOwnerDebugActive)
        {
            regularComp7PackedOwnerDebugActive = true;
            regularComp7PackedOwnerDebugLogsRemaining = areRendererDebugBgObjLogsEnabled() ? 12u : 0u;
        }
    }
    else
    {
        regularComp7PackedOwnerDebugActive = false;
    }
    if (class4AsymmetricBottomDominantPair)
    {
        if (!class4AsymmetricCadenceActive)
        {
            class4AsymmetricCadenceActive = true;
            class4AsymmetricCadencePhase = 0;
        }
        class4AsymmetricCadencePhaseForFrame = class4AsymmetricCadencePhase & 3u;
        class4AsymmetricCadenceAllowsTop =
            !topUsesVramCapture3d || ((class4AsymmetricCadencePhaseForFrame & 1u) == 0u);
        if (topUsesVramCapture3d && !class4AsymmetricCadenceAllowsTop)
        {
            liveSourceScreenSwap = false;
            class4AsymmetricCadenceSuppressesTop = true;
        }
        if (topUsesVramCapture3d)
            class4AsymmetricCadencePhase = (class4AsymmetricCadencePhaseForFrame + 1u) & 1u;
    }
    else
    {
        if (class4AsymmetricCadenceWasActive
            && areRendererDebugBgObjLogsEnabled()
            && packedDebugLogsRemaining > 0)
        {
            melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "VulkanLive3D[Class4CadenceExit]: frameId=%u class4Pair=%u packedSwap=%u liveSwap=%u backendSwap=%u topReg=%u topVram=%u topStruct=%u topAbove=%u bottomReg=%u bottomVram=%u bottomStruct=%u bottomAbove=%u remaining=%u",
                frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
                class4VramStructuredPair ? 1u : 0u,
                resource.screenSwap ? 1u : 0u,
                liveSourceScreenSwap ? 1u : 0u,
                backendRenderScreenSwap ? 1u : 0u,
                softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines,
                softPackedSnapshot.topScreenStats.VramCaptureUses3dLines,
                softPackedSnapshot.topScreenStats.StructuredSlotPixels,
                softPackedSnapshot.topScreenStats.StructuredAbovePixels,
                softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines,
                softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines,
                softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
                softPackedSnapshot.bottomScreenStats.StructuredAbovePixels,
                packedDebugLogsRemaining
            );
            packedDebugLogsRemaining--;
        }
        class4AsymmetricCadenceActive = false;
        class4AsymmetricCadencePhase = 0;
    }

    resource.topStructuredHandoffSuppress3d = topStructuredHandoffSuppress3d;
    resource.bottomStructuredHandoffSuppress3d = bottomStructuredHandoffSuppress3d;

    if (liveSourceScreenSwap != resource.screenSwap
        && areRendererDebugBgObjLogsEnabled()
        && packedDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[OwnerOverride]: frameId=%u packedScreenSwap=%u liveSourceScreenSwap=%u backendRenderScreenSwap=%u class4Only=%u preserveValid=%u preserveTop=%u topCurrentCapture=%u bottomCurrentCapture=%u topReg=%u topVram=%u topStruct=%u bottomReg=%u bottomVram=%u bottomStruct=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.screenSwap ? 1u : 0u,
            liveSourceScreenSwap ? 1u : 0u,
            backendRenderScreenSwap ? 1u : 0u,
            softPackedSnapshot.captureBackedClass4Only ? 1u : 0u,
            resource.class4PreservePackedVramValid ? 1u : 0u,
            resource.class4PreservePackedVramScreenSwap ? 1u : 0u,
            topUsesCurrentCapture3d ? 1u : 0u,
            bottomUsesCurrentCapture3d ? 1u : 0u,
            topUsesRegularCapture3d ? 1u : 0u,
            topUsesVramCapture3d ? 1u : 0u,
            topUsesStructured3d ? 1u : 0u,
            bottomUsesRegularCapture3d ? 1u : 0u,
            bottomUsesVramCapture3d ? 1u : 0u,
            bottomUsesStructured3d ? 1u : 0u,
            packedDebugLogsRemaining
        );
        packedDebugLogsRemaining--;
    }
    else if (preservePackedOwnerForPlainRegularComp7Pair
        && areRendererDebugBgObjLogsEnabled()
        && regularComp7PackedOwnerDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[RegularComp7PackedOwner]: frameId=%u packedScreenSwap=%u liveSourceScreenSwap=%u topReg=%u topComp7=%u topAboveVisible=%u top2DOnly=%u bottomComp4=%u bottomStruct=%u bottomAboveVisible=%u bottom2DOnly=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.screenSwap ? 1u : 0u,
            liveSourceScreenSwap ? 1u : 0u,
            softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines,
            softPackedSnapshot.topScreenStats.CompModeCounts[7],
            softPackedSnapshot.topScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.topScreenStats.Structured2DOnlyPixels,
            softPackedSnapshot.bottomScreenStats.CompModeCounts[4],
            softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
            softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.bottomScreenStats.Structured2DOnlyPixels,
            regularComp7PackedOwnerDebugLogsRemaining
        );
        regularComp7PackedOwnerDebugLogsRemaining--;
    }

    const bool canReusePreRunSnapshot = hasStablePreviousPreparedFrame
        && resource.hasRenderer3dSnapshot
        && frame->renderTimelineValue != 0
        && resource.snapshotFromPreRun
        && resource.snapshotFromInitializedTarget
        && resource.snapshotFromGraphicsBackend == currentBackendIsGraphics
        && resource.snapshotWidth == renderer3D.GetColorTargetWidth()
        && resource.snapshotHeight == renderer3D.GetColorTargetHeight()
        && (!currentBackendIsGraphics || needsDsTimedCaptureBackedComp4Source);

    const auto screenCanUseAccumulatedHighres = [&](const SoftPackedScreenStats& stats) {
        const bool hasExplicitHighresSource =
            softPackedSnapshot.hasCapture3dSource
            || stats.RegularCaptureUses3dLines > 0u
            || stats.VramCaptureUses3dLines > 0u
            || stats.ForceLive3dCompMode7Lines > 0u
            || stats.StructuredSlotPixels > static_cast<u32>(kScreenWidth);
        return stats.RegularCaptureUses3dLines > (kScreenHeight / 2u)
            || stats.VramCaptureUses3dLines > (kScreenHeight / 2u)
            || stats.StructuredSlotPixels > static_cast<u32>(kScreenWidth)
            || (stats.CaptureBackedComp4Lines > (kScreenHeight / 2u) && hasExplicitHighresSource);
    };
    const bool topCanUseAccumulatedHighres =
        screenCanUseAccumulatedHighres(softPackedSnapshot.topScreenStats);
    const bool bottomCanUseAccumulatedHighres =
        screenCanUseAccumulatedHighres(softPackedSnapshot.bottomScreenStats);
    bool replaceAccumulatedHighres = false;
    if (canReusePreRunSnapshot)
    {
        if (needsDsTimedCaptureBackedComp4Source
            && topUsesScreenWideCaptureBackedComp4 != bottomUsesScreenWideCaptureBackedComp4)
        {
            resource.renderer3dSnapshotScreenSwap = liveSourceScreenSwap;
        }
        resource.hasPreparedInputs = true;
        resource.hasContent = false;
    }
    else
    {
        const bool live3dOwnerWasSameLcdLastFrame = liveSourceScreenSwap
            ? framesSinceTopLive3D == 0u
            : framesSinceBottomLive3D == 0u;
        const bool liveOwnerUsesScreenWideRegularCapture =
            liveSourceScreenSwap
                ? softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > (kScreenHeight / 2u)
                : softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > (kScreenHeight / 2u);
        const bool structuredHandoffLiveOwnerNeedsReplace =
            !softPackedSnapshot.hasCapture3dSource
            && ((topStructuredHandoffNoCurrent3d && liveSourceScreenSwap)
                || (bottomStructuredHandoffNoCurrent3d && !liveSourceScreenSwap));
        replaceAccumulatedHighres =
            currentBackendIsGraphics
            && ((class4VramStructuredPair
                    && live3dOwnerWasSameLcdLastFrame
                    && !topPlainStructuredComp7PureAlternatingVramPair
                    && !bottomPlainStructuredComp7PureAlternatingVramPair)
                || asymmetricFullRegularComp7
                || liveOwnerUsesScreenWideRegularCapture
                || structuredHandoffLiveOwnerNeedsReplace);
        if (!recordDirectPresentationPrep(
                frame,
                resource,
                renderer3D,
                liveSourceScreenSwap,
                liveSourceScreenSwap,
                !liveSourceScreenSwap,
                replaceAccumulatedHighres))
        {
            return false;
        }

        resource.hasPreparedInputs = true;
        resource.hasContent = false;
    }

    VkImage currentSourceImage = VK_NULL_HANDLE;
    VkImageView currentSourceImageView = VK_NULL_HANDLE;
    u32 currentSourceWidth = 0;
    u32 currentSourceHeight = 0;
    if (resource.hasRenderer3dSnapshot
        && resource.renderer3dSnapshot != VK_NULL_HANDLE
        && resource.renderer3dSnapshotView != VK_NULL_HANDLE)
    {
        currentSourceImage = resource.renderer3dSnapshot;
        currentSourceImageView = resource.renderer3dSnapshotView;
        currentSourceWidth = resource.snapshotWidth;
        currentSourceHeight = resource.snapshotHeight;
    }
    else
    {
        currentSourceImage = renderer3D.GetColorTargetImage();
        currentSourceImageView = renderer3D.GetColorTargetImageView();
        currentSourceWidth = renderer3D.GetColorTargetWidth();
        currentSourceHeight = renderer3D.GetColorTargetHeight();
    }

    const bool live3dOwnerIsTop = resource.hasRenderer3dSnapshot
        ? resource.renderer3dSnapshotScreenSwap
        : liveSourceScreenSwap;
    if (resource.hasRenderer3dSnapshot
        && resource.renderer3dSnapshot != VK_NULL_HANDLE
        && resource.renderer3dSnapshotView != VK_NULL_HANDLE)
    {
        if (live3dOwnerIsTop)
        {
            framesSinceTopLive3D = 0;
            if (framesSinceBottomLive3D < 1024)
                framesSinceBottomLive3D++;
        }
        else
        {
            framesSinceBottomLive3D = 0;
            if (framesSinceTopLive3D < 1024)
                framesSinceTopLive3D++;
        }
    }
    else
    {
        if (framesSinceTopLive3D < 1024)
            framesSinceTopLive3D++;
        if (framesSinceBottomLive3D < 1024)
            framesSinceBottomLive3D++;
    }
    const bool topPlainStructuredComp7PureAlternatingVramNoAboveCarry =
        topPlainStructuredComp7PureAlternatingVramPair
        && class4NoAboveVramStructuredPair
        && bottomUsesVramCapture3d
        && !topUsesVramCapture3d
        && topUsesStructured3d
        && topUsesPlainStructuredComp7Slot
        && !live3dOwnerIsTop;
    const bool topNeedsAccumulatedHighres =
        topCanUseAccumulatedHighres
        && (!topStructuredHandoffNoCurrent3d || live3dOwnerIsTop)
        && (live3dOwnerIsTop
            || framesSinceTopLive3D <= 1u
            || topUsesFullRegularComp7
            || topStructuredSlotUsesPreviousWhileBottom2dOnly
            || topPlainStructuredComp7PureAlternatingVramCadenceCarry
            || (topPlainStructuredComp7UsesOppositeLive3d
                && !topPlainStructuredComp7PureAlternatingVramPair)
            || (class4VramStructuredPair
                && !live3dOwnerIsTop
                && !topPlainStructuredComp7PureAlternatingVramPair));
    const bool bottomNeedsAccumulatedHighres =
        bottomCanUseAccumulatedHighres
        && (!bottomStructuredHandoffNoCurrent3d || !live3dOwnerIsTop)
        && (!live3dOwnerIsTop
            || framesSinceBottomLive3D <= 1u
            || bottomUsesFullRegularComp7
            || bottomStructuredSlotUsesPreviousWhileTop2dOnly
            || (bottomPlainStructuredComp7UsesOppositeLive3d
                && !bottomPlainStructuredComp7PureAlternatingVramPair)
            || (class4VramStructuredPair
                && live3dOwnerIsTop
                && !bottomPlainStructuredComp7PureAlternatingVramPair));
    resource.previousTopRendererSourceImage = currentSourceImage;
    resource.previousTopRendererSourceImageView = currentSourceImageView;
    resource.previousTopRendererSourceValid = false;
    resource.previousTopSourceFrame = nullptr;
    resource.previousTopSourcePending = false;
    resource.previousBottomRendererSourceImage = currentSourceImage;
    resource.previousBottomRendererSourceImageView = currentSourceImageView;
    resource.previousBottomRendererSourceValid = false;
    resource.previousBottomSourceFrame = nullptr;
    resource.previousBottomSourcePending = false;
    resource.replayTopComposedFromPrevious = false;
    resource.replayBottomComposedFromPrevious = false;
    resource.replayTopComposedFromLatest = false;
    resource.previousTopComposedFrame = nullptr;
    resource.previousBottomComposedFrame = nullptr;
    auto latchPreviousLcdSource = [&](Frame* sourceFrame, bool topLcd) {
        if (sourceFrame == nullptr || sourceFrame == frame)
            return;

        const auto previousIt = resources.find(sourceFrame);
        if (previousIt == resources.end())
            return;

        const FrameResource& previousResource = previousIt->second;
        const bool previousSnapshotCompatible = previousResource.hasRenderer3dSnapshot
            && previousResource.renderer3dSnapshot != VK_NULL_HANDLE
            && previousResource.renderer3dSnapshotView != VK_NULL_HANDLE
            && previousResource.snapshotWidth == currentSourceWidth
            && previousResource.snapshotHeight == currentSourceHeight;
        if (!previousSnapshotCompatible)
            return;

        if (topLcd)
        {
            resource.previousTopRendererSourceImage = previousResource.renderer3dSnapshot;
            resource.previousTopRendererSourceImageView = previousResource.renderer3dSnapshotView;
            resource.previousTopRendererSourceValid = true;
            resource.previousTopSourceFrame = sourceFrame;
            resource.previousTopSourcePending = true;
        }
        else
        {
            resource.previousBottomRendererSourceImage = previousResource.renderer3dSnapshot;
            resource.previousBottomRendererSourceImageView = previousResource.renderer3dSnapshotView;
            resource.previousBottomRendererSourceValid = true;
            resource.previousBottomSourceFrame = sourceFrame;
            resource.previousBottomSourcePending = true;
        }
    };

    if (topNeedsAccumulatedHighres)
        latchPreviousLcdSource(lastTopRendererSourceFrame, true);
    if (bottomNeedsAccumulatedHighres)
        latchPreviousLcdSource(lastBottomRendererSourceFrame, false);

    const bool useAccumulators = resource.snapshotFromGraphicsBackend
        && accumulatedHighresWidth == currentSourceWidth
        && accumulatedHighresHeight == currentSourceHeight;
    const bool topAccumulatorAvailable = useAccumulators
        && accumulatedTopHighresValid
        && accumulatedTopHighresImage != VK_NULL_HANDLE
        && accumulatedTopHighresView != VK_NULL_HANDLE;
    const bool bottomAccumulatorAvailable = useAccumulators
        && accumulatedBottomHighresValid
        && accumulatedBottomHighresImage != VK_NULL_HANDLE
        && accumulatedBottomHighresView != VK_NULL_HANDLE;
    const bool ownershipIntroDebugRelevant =
        currentBackendIsGraphics
        && (resource.screenSwapToggledFromPrevious
            || topUsesPlainStructuredComp7Slot
            || bottomUsesPlainStructuredComp7Slot
            || topUsesRegularCapture3d
            || bottomUsesRegularCapture3d
            || topUsesVramCapture3d
            || bottomUsesVramCapture3d
            || softPackedSnapshot.topScreenStats.CompModeCounts[7] > 0u
            || softPackedSnapshot.bottomScreenStats.CompModeCounts[7] > 0u
            || softPackedSnapshot.topScreenStats.DisplayModeCounts[2] > 0u
            || softPackedSnapshot.bottomScreenStats.DisplayModeCounts[2] > 0u);
    if (ownershipIntroDebugRelevant
        && areRendererDebugBgObjLogsEnabled()
        && ownershipIntroDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanOwnershipIntro[Frame]: frameId=%u packedSwap=%u liveSwap=%u backendSwap=%u prevSwap=%u toggled=%u snapshot=%u snapshotSwap=%u capSrc=%u capHintValid=%u capHint=%u exactValid=%u exactSwap=%u class4Pair=%u preserveTopVramBottomPlain=%u preserveAltPlain=%u topSlotHold=%u bottomSlotHold=%u topCurrent=%u bottomCurrent=%u topPlain=%u bottomPlain=%u topOpp=%u bottomOpp=%u topCan=%u bottomCan=%u topNeed=%u bottomNeed=%u topAcc=%u bottomAcc=%u sinceTop=%u sinceBottom=%u topPrevValid=%u bottomPrevValid=%u topDM=%u/%u/%u/%u topReg=%u topVram=%u topForce=%u topComp7=%u topStruct=%u topAbove=%u top2DOnly=%u bottomDM=%u/%u/%u/%u bottomReg=%u bottomVram=%u bottomForce=%u bottomComp7=%u bottomStruct=%u bottomAbove=%u bottom2DOnly=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.screenSwap ? 1u : 0u,
            liveSourceScreenSwap ? 1u : 0u,
            backendRenderScreenSwap ? 1u : 0u,
            previousResource != nullptr && previousResource->screenSwap ? 1u : 0u,
            resource.screenSwapToggledFromPrevious ? 1u : 0u,
            resource.hasRenderer3dSnapshot ? 1u : 0u,
            resource.renderer3dSnapshotScreenSwap ? 1u : 0u,
            softPackedSnapshot.hasCapture3dSource ? 1u : 0u,
            renderer3D.IsCurrentCaptureScreenSwapHintValid() ? 1u : 0u,
            renderer3D.GetCurrentCaptureScreenSwapHint() ? 1u : 0u,
            renderer3D.IsLastValidExactCaptureAvailable() ? 1u : 0u,
            renderer3D.GetLastValidExactCaptureScreenSwap() ? 1u : 0u,
            class4VramStructuredPair ? 1u : 0u,
            preservePackedOwnerForTopVramBottomPlainStructuredComp7 ? 1u : 0u,
            preservePackedOwnerForAlternatingPlainStructuredComp7 ? 1u : 0u,
            topStructuredSlotUsesPreviousWhileBottom2dOnly ? 1u : 0u,
            bottomStructuredSlotUsesPreviousWhileTop2dOnly ? 1u : 0u,
            topUsesCurrentCapture3d ? 1u : 0u,
            bottomUsesCurrentCapture3d ? 1u : 0u,
            topUsesPlainStructuredComp7Slot ? 1u : 0u,
            bottomUsesPlainStructuredComp7Slot ? 1u : 0u,
            topPlainStructuredComp7UsesOppositeLive3d ? 1u : 0u,
            bottomPlainStructuredComp7UsesOppositeLive3d ? 1u : 0u,
            topCanUseAccumulatedHighres ? 1u : 0u,
            bottomCanUseAccumulatedHighres ? 1u : 0u,
            topNeedsAccumulatedHighres ? 1u : 0u,
            bottomNeedsAccumulatedHighres ? 1u : 0u,
            topAccumulatorAvailable ? 1u : 0u,
            bottomAccumulatorAvailable ? 1u : 0u,
            framesSinceTopLive3D,
            framesSinceBottomLive3D,
            resource.previousTopRendererSourceValid ? 1u : 0u,
            resource.previousBottomRendererSourceValid ? 1u : 0u,
            softPackedSnapshot.topScreenStats.DisplayModeCounts[0],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[1],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[2],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[3],
            softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines,
            softPackedSnapshot.topScreenStats.VramCaptureUses3dLines,
            softPackedSnapshot.topScreenStats.ForceLive3dCompMode7Lines,
            softPackedSnapshot.topScreenStats.CompModeCounts[7],
            softPackedSnapshot.topScreenStats.StructuredSlotPixels,
            softPackedSnapshot.topScreenStats.StructuredAbovePixels,
            softPackedSnapshot.topScreenStats.Structured2DOnlyPixels,
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[0],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[1],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[2],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[3],
            softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines,
            softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines,
            softPackedSnapshot.bottomScreenStats.ForceLive3dCompMode7Lines,
            softPackedSnapshot.bottomScreenStats.CompModeCounts[7],
            softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
            softPackedSnapshot.bottomScreenStats.StructuredAbovePixels,
            softPackedSnapshot.bottomScreenStats.Structured2DOnlyPixels,
            ownershipIntroDebugLogsRemaining);
        ownershipIntroDebugLogsRemaining--;
    }
    if (class4VramStructuredPair
        && areRendererDebugBgObjLogsEnabled()
        && class4PairDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[Class4Pair]: frameId=%u packedSwap=%u liveSwap=%u backendSwap=%u capHintValid=%u capHint=%u exactValid=%u exactSwap=%u snapshot=%u snapshotSwap=%u preserveValid=%u preserveTop=%u noAbove=%u smallAboveMarker=%u noAboveActive=%u cadence=%u cadenceMarker=%u cadenceCarry=%u topPackedVisible=%u bottomAboveSampled=%u bottomAboveChanged=%u bottomAboveStable=%u bottomAboveMotion=%u bottomAboveTransition=%u cadencePhase=%u cadenceTop=%u cadenceSuppressTop=%u topDM=%u/%u/%u/%u bottomDM=%u/%u/%u/%u topVram=%u topStruct=%u topAbove=%u bottomVram=%u bottomStruct=%u bottomAbove=%u bottomAboveVisible=%u bottomAboveBlack=%u topCan=%u bottomCan=%u topNeed=%u bottomNeed=%u topAcc=%u bottomAcc=%u sinceTop=%u sinceBottom=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.screenSwap ? 1u : 0u,
            liveSourceScreenSwap ? 1u : 0u,
            backendRenderScreenSwap ? 1u : 0u,
            renderer3D.IsCurrentCaptureScreenSwapHintValid() ? 1u : 0u,
            renderer3D.GetCurrentCaptureScreenSwapHint() ? 1u : 0u,
            renderer3D.IsLastValidExactCaptureAvailable() ? 1u : 0u,
            renderer3D.GetLastValidExactCaptureScreenSwap() ? 1u : 0u,
            resource.hasRenderer3dSnapshot ? 1u : 0u,
            resource.renderer3dSnapshotScreenSwap ? 1u : 0u,
            resource.class4PreservePackedVramValid ? 1u : 0u,
            resource.class4PreservePackedVramScreenSwap ? 1u : 0u,
            resource.class4NoAboveVramStructuredPair ? 1u : 0u,
            class4SmallBottomAboveNoAboveMarker ? 1u : 0u,
            class4NoAboveVramStructuredActiveForFrame ? 1u : 0u,
            class4AsymmetricBottomDominantPair ? 1u : 0u,
            class4BottomDominantAsymmetricMarker ? 1u : 0u,
            class4BottomDominantAsymmetricCarry ? 1u : 0u,
            topVramPackedHasVisibleContent ? 1u : 0u,
            bottomStructuredAboveHashSampled ? 1u : 0u,
            bottomStructuredAboveChanged ? 1u : 0u,
            class4BottomAboveStableFrames,
            class4BottomAboveMotionActive ? 1u : 0u,
            bottomStructuredAboveTransitionActive ? 1u : 0u,
            class4AsymmetricCadencePhaseForFrame,
            class4AsymmetricCadenceAllowsTop ? 1u : 0u,
            class4AsymmetricCadenceSuppressesTop ? 1u : 0u,
            softPackedSnapshot.topScreenStats.DisplayModeCounts[0],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[1],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[2],
            softPackedSnapshot.topScreenStats.DisplayModeCounts[3],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[0],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[1],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[2],
            softPackedSnapshot.bottomScreenStats.DisplayModeCounts[3],
            softPackedSnapshot.topScreenStats.VramCaptureUses3dLines,
            softPackedSnapshot.topScreenStats.StructuredSlotPixels,
            softPackedSnapshot.topScreenStats.StructuredAbovePixels,
            softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines,
            softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
            softPackedSnapshot.bottomScreenStats.StructuredAbovePixels,
            softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.bottomScreenStats.StructuredAboveBlackPixels,
            topCanUseAccumulatedHighres ? 1u : 0u,
            bottomCanUseAccumulatedHighres ? 1u : 0u,
            topNeedsAccumulatedHighres ? 1u : 0u,
            bottomNeedsAccumulatedHighres ? 1u : 0u,
            topAccumulatorAvailable ? 1u : 0u,
            bottomAccumulatorAvailable ? 1u : 0u,
            framesSinceTopLive3D,
            framesSinceBottomLive3D
        );
        class4PairDebugLogsRemaining--;
    }
    if (topNeedsAccumulatedHighres
        && topAccumulatorAvailable
        && (!topUsesFullRegularComp7 || !live3dOwnerIsTop))
    {
        resource.previousTopRendererSourceImage = accumulatedTopHighresImage;
        resource.previousTopRendererSourceImageView = accumulatedTopHighresView;
        resource.previousTopRendererSourceValid = true;
    }
    if (bottomNeedsAccumulatedHighres
        && bottomAccumulatorAvailable
        && (!bottomUsesFullRegularComp7 || live3dOwnerIsTop))
    {
        resource.previousBottomRendererSourceImage = accumulatedBottomHighresImage;
        resource.previousBottomRendererSourceImageView = accumulatedBottomHighresView;
        resource.previousBottomRendererSourceValid = true;
    }

    const bool topStructuredHandoffIncomplete =
        screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.topScreenStats)
        && screenHasStructuredHandoffOverlay(softPackedSnapshot.bottomScreenStats);
    const bool bottomStructuredHandoffIncomplete =
        screenUsesPlainStructuredComp7HandoffSlot(softPackedSnapshot.bottomScreenStats)
        && screenHasStructuredHandoffOverlay(softPackedSnapshot.topScreenStats);
    const bool topStructuredHandoffBlankCarry =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && softPackedSnapshot.topScreenStats.DisplayModeCounts[0] == kScreenHeight
        && softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines == 0u
        && softPackedSnapshot.topScreenStats.VramCaptureUses3dLines == 0u
        && softPackedSnapshot.topScreenStats.ForceLive3dCompMode7Lines == 0u
        && softPackedSnapshot.topScreenStats.StructuredSlotPixels == 0u
        && bottomUsesPlainStructuredComp7Slot
        && lastTopComposedFrame != nullptr
        && lastTopComposedFrame != frame;
    const bool bottomStructuredHandoffBlankCarry =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && softPackedSnapshot.bottomScreenStats.DisplayModeCounts[0] == kScreenHeight
        && softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines == 0u
        && softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines == 0u
        && softPackedSnapshot.bottomScreenStats.ForceLive3dCompMode7Lines == 0u
        && softPackedSnapshot.bottomScreenStats.StructuredSlotPixels == 0u
        && topUsesPlainStructuredComp7Slot
        && lastBottomComposedFrame != nullptr
        && lastBottomComposedFrame != frame;
    const bool topPlainStructuredComp7CarriesPreviousPureVram =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && topUsesPlainStructuredComp7Slot
        && !topUsesCurrentCapture3d
        && !bottomUsesCurrentCapture3d
        && previousResource != nullptr
        && previousResource->topPureAlternatingVramCapture
        && lastTopComposedFrame != nullptr
        && lastTopComposedFrame != frame;
    const bool bottomPlainStructuredComp7CarriesPreviousPureVram =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && bottomUsesPlainStructuredComp7Slot
        && !bottomUsesCurrentCapture3d
        && !topUsesCurrentCapture3d
        && previousResource != nullptr
        && previousResource->bottomPureAlternatingVramCapture
        && lastBottomComposedFrame != nullptr
        && lastBottomComposedFrame != frame;
    const bool bottomPlainStructuredComp7CanUseRegularTopHistory =
        currentBackendIsGraphics
        && bottomUsesPlainStructuredComp7Slot
        && topUsesRegularCapture3d
        && softPackedSnapshot.topScreenStats.CompModeCounts[0] > dominantStructuredSlotThreshold
        && bottomNeedsAccumulatedHighres
        && bottomAccumulatorAvailable
        && resource.previousBottomRendererSourceValid;
    const bool topRegularComp7BottomComp2ReplayComposed =
        currentBackendIsGraphics
        && resource.screenSwapToggledFromPrevious
        && screenUsesRegularComp7EmptyPrimarySlot(softPackedSnapshot.topScreenStats)
        && screenUsesFullStructuredCompMode2Slot(
            softPackedSnapshot.packedBottomControl,
            softPackedSnapshot.packedBottomLineMeta)
        && !bottomUsesRegularCapture3d
        && !bottomUsesVramCapture3d
        && lastTopComposedFrame != nullptr
        && lastTopComposedFrame != frame;
    const bool topPlainStructuredComp7ReplayComposed =
        ((topPlainStructuredComp7UsesOppositeLive3d
            && !topPlainStructuredComp7PureAlternatingVramPair)
            || topPlainStructuredComp7PureAlternatingVramNoAboveCarry
            || topPlainStructuredComp7CarriesPreviousPureVram)
        && lastTopComposedFrame != nullptr
        && lastTopComposedFrame != frame;
    const bool bottomPlainStructuredComp7ReplayComposed =
        ((bottomPlainStructuredComp7UsesOppositeLive3d
            && !bottomPlainStructuredComp7PureAlternatingVramPair
            && !bottomPlainStructuredComp7CanUseRegularTopHistory)
            || bottomPlainStructuredComp7CarriesPreviousPureVram)
        && lastBottomComposedFrame != nullptr
        && lastBottomComposedFrame != frame;
    const bool topPlainStructuredSlotDuringOppositeNoCurrentHandoff =
        resource.screenSwapToggledFromPrevious
        &&
        (topUsesPlainStructuredComp7Slot || topUsesPlainStructured3dSlot)
        && bottomStructuredHandoffNoCurrent3d;
    const bool bottomPlainStructuredSlotDuringOppositeNoCurrentHandoff =
        resource.screenSwapToggledFromPrevious
        &&
        (bottomUsesPlainStructuredComp7Slot || bottomUsesPlainStructured3dSlot)
        && topStructuredHandoffNoCurrent3d;
    const bool topStructuredHandoffCarrySource =
        currentBackendIsGraphics
        && topStructuredHandoffIncomplete
        && topAccumulatorAvailable;
    const bool bottomStructuredHandoffCarrySource =
        currentBackendIsGraphics
        && bottomStructuredHandoffIncomplete
        && bottomAccumulatorAvailable;
    if (topStructuredHandoffCarrySource)
    {
        resource.previousTopRendererSourceImage = accumulatedTopHighresImage;
        resource.previousTopRendererSourceImageView = accumulatedTopHighresView;
        resource.previousTopRendererSourceValid = true;
    }
    if (bottomStructuredHandoffCarrySource)
    {
        resource.previousBottomRendererSourceImage = accumulatedBottomHighresImage;
        resource.previousBottomRendererSourceImageView = accumulatedBottomHighresView;
        resource.previousBottomRendererSourceValid = true;
    }
    resource.replayTopComposedFromPrevious =
        currentBackendIsGraphics
        && ((topStructuredHandoffIncomplete && !resource.topPackedCarryFromPrevious)
            || topStructuredHandoffBlankCarry
            || topPlainStructuredComp7ReplayComposed
            || topRegularComp7BottomComp2ReplayComposed)
        && lastTopComposedFrame != nullptr
        && lastTopComposedFrame != frame;
    resource.replayBottomComposedFromPrevious =
        currentBackendIsGraphics
        && ((bottomStructuredHandoffIncomplete && !resource.bottomPackedCarryFromPrevious)
            || bottomStructuredHandoffBlankCarry
            || bottomPlainStructuredComp7ReplayComposed)
        && lastBottomComposedFrame != nullptr
        && lastBottomComposedFrame != frame;
    resource.replayTopComposedFromLatest = topRegularComp7BottomComp2ReplayComposed;
    resource.previousTopComposedFrame =
        (resource.replayTopComposedFromPrevious && !resource.replayTopComposedFromLatest)
            ? lastTopComposedFrame
            : nullptr;
    resource.previousBottomComposedFrame = resource.replayBottomComposedFromPrevious ? lastBottomComposedFrame : nullptr;

    if ((topStructuredHandoffCarrySource || bottomStructuredHandoffCarrySource
            || resource.replayTopComposedFromPrevious || resource.replayBottomComposedFromPrevious
            || topStructuredHandoffNoCurrent3d
            || bottomStructuredHandoffNoCurrent3d
            || topStructuredHandoffSuppress3d
            || bottomStructuredHandoffSuppress3d
            || topStructuredHandoffOverlayHasNoCurrent3dSource
            || bottomStructuredHandoffOverlayHasNoCurrent3dSource
            || topStructuredHandoffBlankCarry
            || bottomStructuredHandoffBlankCarry
            || topPlainStructuredComp7UsesOppositeLive3d
            || bottomPlainStructuredComp7UsesOppositeLive3d
            || topPlainStructuredComp7PureAlternatingVramPair
            || bottomPlainStructuredComp7PureAlternatingVramPair
            || topPlainStructuredComp7CarriesPreviousPureVram
            || bottomPlainStructuredComp7CarriesPreviousPureVram
            || topPlainStructuredComp7PureAlternatingVramNoAboveCarry
            || topRegularComp7BottomComp2ReplayComposed
            || topPlainStructuredSlotDuringOppositeNoCurrentHandoff
            || bottomPlainStructuredSlotDuringOppositeNoCurrentHandoff)
        && areRendererDebugBgObjLogsEnabled()
        && structuredComp7HandoffDebugLogsRemaining == 0)
    {
        structuredComp7HandoffDebugLogsRemaining = 12u;
    }
    if ((topStructuredHandoffCarrySource || bottomStructuredHandoffCarrySource
            || resource.replayTopComposedFromPrevious || resource.replayBottomComposedFromPrevious
            || topStructuredHandoffNoCurrent3d
            || bottomStructuredHandoffNoCurrent3d
            || topStructuredHandoffSuppress3d
            || bottomStructuredHandoffSuppress3d
            || topStructuredHandoffOverlayHasNoCurrent3dSource
            || bottomStructuredHandoffOverlayHasNoCurrent3dSource
            || topPlainStructuredComp7UsesOppositeLive3d
            || bottomPlainStructuredComp7UsesOppositeLive3d
            || topPlainStructuredComp7PureAlternatingVramPair
            || bottomPlainStructuredComp7PureAlternatingVramPair
            || topPlainStructuredComp7CarriesPreviousPureVram
            || bottomPlainStructuredComp7CarriesPreviousPureVram
            || topPlainStructuredComp7PureAlternatingVramNoAboveCarry
            || topRegularComp7BottomComp2ReplayComposed
            || topPlainStructuredSlotDuringOppositeNoCurrentHandoff
            || bottomPlainStructuredSlotDuringOppositeNoCurrentHandoff)
        && areRendererDebugBgObjLogsEnabled()
        && structuredComp7HandoffDebugLogsRemaining > 0)
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[StructuredComp7Handoff]: frameId=%u packedSwap=%u liveSwap=%u prevSwap=%u swapToggled=%u topNoCurrent=%u bottomNoCurrent=%u topSuppress3d=%u bottomSuppress3d=%u replaceAcc=%u topOverlayNo3d=%u bottomOverlayNo3d=%u topBlankCarry=%u bottomBlankCarry=%u topPlainOpposite=%u bottomPlainOpposite=%u topPureAltVram=%u bottomPureAltVram=%u topCarryPureVram=%u bottomCarryPureVram=%u bottomRegularTopHistory=%u topRegularBottomComp2Replay=%u topNoAboveCarry=%u topPlainOppositeNoCurrent=%u bottomPlainOppositeNoCurrent=%u topCarrySource=%u bottomCarrySource=%u topReplayComposed=%u bottomReplayComposed=%u topAcc=%u bottomAcc=%u topPrev=%u bottomPrev=%u topComp7=%u topStruct=%u topAbove=%u top2DOnly=%u bottomComp7=%u bottomStruct=%u bottomAbove=%u bottom2DOnly=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.screenSwap ? 1u : 0u,
            liveSourceScreenSwap ? 1u : 0u,
            previousResource != nullptr && previousResource->screenSwap ? 1u : 0u,
            resource.screenSwapToggledFromPrevious ? 1u : 0u,
            topStructuredHandoffNoCurrent3d ? 1u : 0u,
            bottomStructuredHandoffNoCurrent3d ? 1u : 0u,
            topStructuredHandoffSuppress3d ? 1u : 0u,
            bottomStructuredHandoffSuppress3d ? 1u : 0u,
            replaceAccumulatedHighres ? 1u : 0u,
            topStructuredHandoffOverlayHasNoCurrent3dSource ? 1u : 0u,
            bottomStructuredHandoffOverlayHasNoCurrent3dSource ? 1u : 0u,
            topStructuredHandoffBlankCarry ? 1u : 0u,
            bottomStructuredHandoffBlankCarry ? 1u : 0u,
            topPlainStructuredComp7UsesOppositeLive3d ? 1u : 0u,
            bottomPlainStructuredComp7UsesOppositeLive3d ? 1u : 0u,
            topPlainStructuredComp7PureAlternatingVramPair ? 1u : 0u,
            bottomPlainStructuredComp7PureAlternatingVramPair ? 1u : 0u,
            topPlainStructuredComp7CarriesPreviousPureVram ? 1u : 0u,
            bottomPlainStructuredComp7CarriesPreviousPureVram ? 1u : 0u,
            bottomPlainStructuredComp7CanUseRegularTopHistory ? 1u : 0u,
            topRegularComp7BottomComp2ReplayComposed ? 1u : 0u,
            topPlainStructuredComp7PureAlternatingVramNoAboveCarry ? 1u : 0u,
            topPlainStructuredSlotDuringOppositeNoCurrentHandoff ? 1u : 0u,
            bottomPlainStructuredSlotDuringOppositeNoCurrentHandoff ? 1u : 0u,
            topStructuredHandoffCarrySource ? 1u : 0u,
            bottomStructuredHandoffCarrySource ? 1u : 0u,
            resource.replayTopComposedFromPrevious ? 1u : 0u,
            resource.replayBottomComposedFromPrevious ? 1u : 0u,
            topAccumulatorAvailable ? 1u : 0u,
            bottomAccumulatorAvailable ? 1u : 0u,
            resource.previousTopRendererSourceValid ? 1u : 0u,
            resource.previousBottomRendererSourceValid ? 1u : 0u,
            softPackedSnapshot.topScreenStats.CompModeCounts[7],
            softPackedSnapshot.topScreenStats.StructuredSlotPixels,
            softPackedSnapshot.topScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.topScreenStats.Structured2DOnlyPixels,
            softPackedSnapshot.bottomScreenStats.CompModeCounts[7],
            softPackedSnapshot.bottomScreenStats.StructuredSlotPixels,
            softPackedSnapshot.bottomScreenStats.StructuredAboveVisiblePixels,
            softPackedSnapshot.bottomScreenStats.Structured2DOnlyPixels,
            structuredComp7HandoffDebugLogsRemaining);
        structuredComp7HandoffDebugLogsRemaining--;
    }
    recordTemporalStats(
        softPackedSnapshot,
        resource,
        topNeedsAccumulatedHighres,
        bottomNeedsAccumulatedHighres,
        topAccumulatorAvailable,
        bottomAccumulatorAvailable,
        resource.screenSwap,
        liveSourceScreenSwap,
        resource.hasRenderer3dSnapshot,
        resource.renderer3dSnapshotScreenSwap);
    if (resource.hasRenderer3dSnapshot
        && resource.renderer3dSnapshot != VK_NULL_HANDLE
        && resource.renderer3dSnapshotView != VK_NULL_HANDLE)
    {
        if (live3dOwnerIsTop)
            lastTopRendererSourceFrame = frame;
        else
            lastBottomRendererSourceFrame = frame;
    }

    lastPreparedFrame = frame;
    return true;
}

bool VulkanOutput::updatePreparedCapture3dSource(
    FrameResource& resource,
    SoftPackedFrameSnapshot& softPackedSnapshot,
    const FrameResource* previousResource,
    bool currentBackendIsGraphics,
    bool currentFrameNeedsCapture3dSource,
    melonDS::VulkanRenderer3D& renderer3D)
{
    resource.hasPreparedCapture3dSource = false;
    resource.preparedCapture3dSource.fill(0);
    resource.captureFallbackLines.fill(0);
    softPackedSnapshot.captureFallbackLines.fill(0);
    if (resource.capture3dMapped != nullptr)
        std::memset(resource.capture3dMapped, 0, static_cast<size_t>(kCapture3dBufferSize));

    const bool renderer2dDebugControlsActive = areRenderer2DDebugControlsActive();
    if (renderer2dDebugControlsActive)
    {
        lastValidCapture3dSourceLines.fill(0);
        lastValidTopComp4PlaceholderLines.fill(0);
        lastValidBottomComp4PlaceholderLines.fill(0);
    }
    const bool renderer2dDebug3dBackgroundEnabled =
        !renderer2dDebugControlsActive
        || isRenderer2DDebugBackgroundKindEnabled(kRenderer2DDebugFeature3DBackground);
    const u32* preparedCapture3dSource = softPackedSnapshot.hasCapture3dSource
        ? softPackedSnapshot.capture3dSourceDsFrame.data()
        : nullptr;
    const u32* previousPreparedCapture3dSource =
        !renderer2dDebugControlsActive && previousResource != nullptr && previousResource->hasPreparedCapture3dSource
        ? (previousResource->capture3dMapped != nullptr
            ? static_cast<const u32*>(previousResource->capture3dMapped)
            : previousResource->preparedCapture3dSource.data())
        : nullptr;
    const u32* lastValidPreparedCapture3dSource =
        renderer2dDebugControlsActive ? nullptr : lastValidCapture3dSource.data();
    const bool frameUsesCurrentRegularCapture3d =
        softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.topScreenStats.VramCaptureUses3dLines > 0u
        || softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines > 0u;
    const bool topUsesCurrentRegularCapture3d =
        softPackedSnapshot.topScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.topScreenStats.VramCaptureUses3dLines > 0u;
    const bool bottomUsesCurrentRegularCapture3d =
        softPackedSnapshot.bottomScreenStats.RegularCaptureUses3dLines > 0u
        || softPackedSnapshot.bottomScreenStats.VramCaptureUses3dLines > 0u;
    const bool preferTopComp4Placeholder =
        !topUsesCurrentRegularCapture3d
        &&
        softPackedSnapshot.topScreenStats.CaptureBackedComp4Lines > 0u
        && softPackedSnapshot.bottomScreenStats.CaptureBackedComp4Lines == 0u;
    const bool preferBottomComp4Placeholder =
        !bottomUsesCurrentRegularCapture3d
        &&
        softPackedSnapshot.bottomScreenStats.CaptureBackedComp4Lines > 0u
        && softPackedSnapshot.topScreenStats.CaptureBackedComp4Lines == 0u;
    const u32* preferredComp4Placeholder = nullptr;
    bool preferredComp4PlaceholderIsTemporal = false;
    u32* lastValidComp4Placeholder = nullptr;
    u8* lastValidComp4PlaceholderLines = nullptr;
    if (preferTopComp4Placeholder)
    {
        preferredComp4Placeholder = renderer2dDebug3dBackgroundEnabled
            ? softPackedSnapshot.comp4TopPlaceholder.data()
            : nullptr;
        preferredComp4PlaceholderIsTemporal = true;
        lastValidComp4Placeholder = renderer2dDebugControlsActive ? nullptr : lastValidTopComp4Placeholder.data();
        lastValidComp4PlaceholderLines = renderer2dDebugControlsActive ? nullptr : lastValidTopComp4PlaceholderLines.data();
    }
    else if (preferBottomComp4Placeholder)
    {
        preferredComp4Placeholder = renderer2dDebug3dBackgroundEnabled
            ? softPackedSnapshot.comp4BottomPlaceholder.data()
            : nullptr;
        preferredComp4PlaceholderIsTemporal = true;
        lastValidComp4Placeholder = renderer2dDebugControlsActive ? nullptr : lastValidBottomComp4Placeholder.data();
        lastValidComp4PlaceholderLines = renderer2dDebugControlsActive ? nullptr : lastValidBottomComp4PlaceholderLines.data();
    }
    const auto* captureLineUses3dMask = &softPackedSnapshot.captureLineUses3dMask;
    const bool renderer2dCapture3dSourceHasPixels =
        renderer2dDebug3dBackgroundEnabled && capture3dSourceHasAnyUsefulPixel(preparedCapture3dSource);
    if (!currentFrameNeedsCapture3dSource)
        return true;
    if (!renderer2dDebug3dBackgroundEnabled)
        return true;

    u32 linesFromRenderer2d = 0;
    u32 linesFromLatchedValid = 0;
    u32 linesFromPreviousFrame = 0;
    u32 linesFromRenderer3d = 0;
    u32 emptyLines = 0;
    bool needsRenderer3dFallback = false;
    std::array<u8, kScreenHeight> resolvedLines{};

    auto* capture3dMapped = static_cast<u32*>(resource.capture3dMapped);
    for (int y = 0; y < kScreenHeight; y++)
    {
        const bool latchedComp4LineHasPixels =
            lastValidComp4Placeholder != nullptr
            && lastValidComp4PlaceholderLines != nullptr
            && lastValidComp4PlaceholderLines[static_cast<size_t>(y)] != 0u
            && capture3dSourceLineHasAnyUsefulPixel(lastValidComp4Placeholder, y);
        const bool preferredComp4LineHasPixels = capture3dSourceLineHasAnyUsefulPixel(preferredComp4Placeholder, y);
        const bool preferredComp4LineIsSolidOpaqueBlack =
            preferredComp4PlaceholderIsTemporal
            && capture3dSourceLineIsSolidOpaqueBlack(preferredComp4Placeholder, y);
        const bool acceptPreferredComp4Line =
            preferredComp4LineHasPixels
            && !(preferredComp4LineIsSolidOpaqueBlack && latchedComp4LineHasPixels);
        const bool lineHasPixels = capture3dSourceLineHasAnyUsefulPixel(preparedCapture3dSource, y);
        const bool latchedLineHasPixels =
            !renderer2dDebugControlsActive
            && lastValidCapture3dSourceLines[static_cast<size_t>(y)] != 0u
            && capture3dSourceLineHasAnyUsefulPixel(lastValidPreparedCapture3dSource, y);
        const bool previousLineHasPixels = capture3dSourceLineHasAnyUsefulPixel(previousPreparedCapture3dSource, y);
        const u32 topLineMeta = softPackedSnapshot.packedTopLineMeta[static_cast<size_t>(y)];
        const u32 bottomLineMeta = softPackedSnapshot.packedBottomLineMeta[static_cast<size_t>(y)];
        const bool lineMetaUses3d =
            ((topLineMeta | bottomLineMeta) & (kMetaFlagRegularCaptureUses3d | kMetaFlagVramCaptureUses3d)) != 0u;
        const bool lineUses3d =
            frameUsesCurrentRegularCapture3d
            || lineMetaUses3d
            || (captureLineUses3dMask != nullptr
                && (*captureLineUses3dMask)[static_cast<size_t>(y)] != 0u);
        const size_t rowOffset = static_cast<size_t>(y) * static_cast<size_t>(kScreenWidth);
        if (acceptPreferredComp4Line)
        {
            if (capture3dMapped != nullptr)
            {
                if (lineHasPixels)
                {
                    for (int x = 0; x < kScreenWidth; x++)
                    {
                        const size_t index = rowOffset + static_cast<size_t>(x);
                        u32 preferredPixel = preferredComp4Placeholder[index];
                        if (preferredComp4PlaceholderIsTemporal
                            && capture3dSourcePixelIsOpaqueBlack(preferredPixel)
                            && latchedLineHasPixels
                            && capture3dSourcePixelIsNonBlackUseful(lastValidPreparedCapture3dSource[index]))
                        {
                            preferredPixel = lastValidPreparedCapture3dSource[index];
                        }
                        capture3dMapped[index] = capture3dSourcePixelIsUseful(preferredPixel)
                            ? preferredPixel
                            : preparedCapture3dSource[index];
                    }
                }
                else
                {
                    for (int x = 0; x < kScreenWidth; x++)
                    {
                        const size_t index = rowOffset + static_cast<size_t>(x);
                        u32 preferredPixel = preferredComp4Placeholder[index];
                        if (preferredComp4PlaceholderIsTemporal
                            && capture3dSourcePixelIsOpaqueBlack(preferredPixel)
                            && latchedLineHasPixels
                            && capture3dSourcePixelIsNonBlackUseful(lastValidPreparedCapture3dSource[index]))
                        {
                            preferredPixel = lastValidPreparedCapture3dSource[index];
                        }
                        capture3dMapped[index] = preferredPixel;
                    }
                }
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                const size_t index = rowOffset + static_cast<size_t>(x);
                u32 preferredPixel = preferredComp4Placeholder[index];
                if (preferredComp4PlaceholderIsTemporal
                    && capture3dSourcePixelIsOpaqueBlack(preferredPixel)
                    && latchedLineHasPixels
                    && capture3dSourcePixelIsNonBlackUseful(lastValidPreparedCapture3dSource[index]))
                {
                    preferredPixel = lastValidPreparedCapture3dSource[index];
                }
                const u32 pixel = lineHasPixels && !capture3dSourcePixelIsUseful(preferredPixel)
                    ? preparedCapture3dSource[index]
                    : preferredPixel;
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(pixel);
            }
            if (lastValidComp4Placeholder != nullptr && lastValidComp4PlaceholderLines != nullptr)
            {
                for (int x = 0; x < kScreenWidth; x++)
                {
                    const size_t index = rowOffset + static_cast<size_t>(x);
                    u32 preferredPixel = preferredComp4Placeholder[index];
                    if (preferredComp4PlaceholderIsTemporal
                        && capture3dSourcePixelIsOpaqueBlack(preferredPixel)
                        && latchedLineHasPixels
                        && capture3dSourcePixelIsNonBlackUseful(lastValidPreparedCapture3dSource[index]))
                    {
                        preferredPixel = lastValidPreparedCapture3dSource[index];
                    }
                    lastValidComp4Placeholder[index] = preferredPixel;
                }
                lastValidComp4PlaceholderLines[static_cast<size_t>(y)] = 1u;
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            if (preferredComp4PlaceholderIsTemporal)
                linesFromPreviousFrame++;
            else
                linesFromRenderer2d++;
            continue;
        }

        if (latchedComp4LineHasPixels)
        {
            if (capture3dMapped != nullptr)
            {
                if (lineHasPixels)
                {
                    for (int x = 0; x < kScreenWidth; x++)
                    {
                        const size_t index = rowOffset + static_cast<size_t>(x);
                        const u32 latchedPixel = lastValidComp4Placeholder[index];
                        capture3dMapped[index] = capture3dSourcePixelIsUseful(latchedPixel)
                            ? latchedPixel
                            : preparedCapture3dSource[index];
                    }
                }
                else
                {
                    std::memcpy(
                        capture3dMapped + rowOffset,
                        lastValidComp4Placeholder + rowOffset,
                        static_cast<size_t>(kScreenWidth) * sizeof(u32));
                }
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                const size_t index = rowOffset + static_cast<size_t>(x);
                const u32 latchedPixel = lastValidComp4Placeholder[index];
                const u32 pixel = lineHasPixels && !capture3dSourcePixelIsUseful(latchedPixel)
                    ? preparedCapture3dSource[index]
                    : latchedPixel;
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(pixel);
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            linesFromLatchedValid++;
            continue;
        }

        if (lineHasPixels)
        {
            if (capture3dMapped != nullptr)
            {
                std::memcpy(
                    capture3dMapped + rowOffset,
                    preparedCapture3dSource + rowOffset,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(preparedCapture3dSource[rowOffset + static_cast<size_t>(x)]);
            }
            if (!renderer2dDebugControlsActive)
            {
                std::memcpy(
                    lastValidCapture3dSource.data() + rowOffset,
                    preparedCapture3dSource + rowOffset,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
                lastValidCapture3dSourceLines[static_cast<size_t>(y)] = 1u;
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            linesFromRenderer2d++;
            continue;
        }

        if (latchedLineHasPixels)
        {
            if (capture3dMapped != nullptr)
            {
                std::memcpy(
                    capture3dMapped + rowOffset,
                    lastValidPreparedCapture3dSource + rowOffset,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(lastValidPreparedCapture3dSource[rowOffset + static_cast<size_t>(x)]);
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            linesFromLatchedValid++;
            continue;
        }

        if (currentBackendIsGraphics && lineUses3d && previousLineHasPixels)
        {
            if (capture3dMapped != nullptr)
            {
                std::memcpy(
                    capture3dMapped + rowOffset,
                    previousPreparedCapture3dSource + rowOffset,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(previousPreparedCapture3dSource[rowOffset + static_cast<size_t>(x)]);
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            linesFromPreviousFrame++;
            continue;
        }

        if (currentBackendIsGraphics && lineUses3d)
        {
            needsRenderer3dFallback = true;
            continue;
        }

        if (previousLineHasPixels)
        {
            if (capture3dMapped != nullptr)
            {
                std::memcpy(
                    capture3dMapped + rowOffset,
                    previousPreparedCapture3dSource + rowOffset,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
            }
            for (int x = 0; x < kScreenWidth; x++)
            {
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] =
                    expandPackedColor6ToRgba8(previousPreparedCapture3dSource[rowOffset + static_cast<size_t>(x)]);
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            linesFromPreviousFrame++;
            continue;
        }

        emptyLines++;
    }

    if (currentBackendIsGraphics && currentFrameNeedsCapture3dSource && (needsRenderer3dFallback || !renderer2dCapture3dSourceHasPixels))
    {
        renderer3D.PrepareCaptureFrame();
        for (int y = 0; y < kScreenHeight; y++)
        {
            const bool renderer2dLineHasPixels = capture3dSourceLineHasAnyUsefulPixel(preparedCapture3dSource, y);
            const u32 topLineMeta = softPackedSnapshot.packedTopLineMeta[static_cast<size_t>(y)];
            const u32 bottomLineMeta = softPackedSnapshot.packedBottomLineMeta[static_cast<size_t>(y)];
            const bool lineMetaUses3d =
                ((topLineMeta | bottomLineMeta) & (kMetaFlagRegularCaptureUses3d | kMetaFlagVramCaptureUses3d)) != 0u;
            const bool lineUses3d =
                frameUsesCurrentRegularCapture3d
                || lineMetaUses3d
                || (captureLineUses3dMask != nullptr
                    && (*captureLineUses3dMask)[static_cast<size_t>(y)] != 0u);
            if (resolvedLines[static_cast<size_t>(y)] != 0u)
                continue;
            if (renderer2dLineHasPixels)
                continue;
            if (preparedCapture3dSource != nullptr && !lineUses3d && renderer2dCapture3dSourceHasPixels)
                continue;

            const u32* line = renderer3D.GetLine(y);
            if (line == nullptr)
                return false;

            const size_t rowOffset = static_cast<size_t>(y) * static_cast<size_t>(kScreenWidth);
            if (capture3dMapped != nullptr)
                std::memcpy(capture3dMapped + rowOffset, line, static_cast<size_t>(kScreenWidth) * sizeof(u32));

            for (int x = 0; x < kScreenWidth; x++)
                resource.preparedCapture3dSource[rowOffset + static_cast<size_t>(x)] = expandPackedColor6ToRgba8(line[x]);
            if (!renderer2dDebugControlsActive)
            {
                std::memcpy(
                    lastValidCapture3dSource.data() + rowOffset,
                    line,
                    static_cast<size_t>(kScreenWidth) * sizeof(u32));
                lastValidCapture3dSourceLines[static_cast<size_t>(y)] = 1u;
            }
            resolvedLines[static_cast<size_t>(y)] = 1u;
            resource.captureFallbackLines[static_cast<size_t>(y)] = 1u;
            softPackedSnapshot.captureFallbackLines[static_cast<size_t>(y)] = 1u;
            linesFromRenderer3d++;
        }
    }

    resource.hasPreparedCapture3dSource = (linesFromRenderer2d + linesFromLatchedValid + linesFromPreviousFrame + linesFromRenderer3d) > 0u;
    if (areRendererDebugBgObjLogsEnabled() && packedDebugLogsRemaining > 0)
    {
        const auto* capture3dSource = capture3dMapped != nullptr
            ? capture3dMapped
            : resource.preparedCapture3dSource.data();
        const size_t centerIndex = static_cast<size_t>(96) * 256u + 128u;
                melonDS::Platform::Log(
                melonDS::Platform::LogLevel::Warn,
                "VulkanCapture3D[Prepared]: source=merged front=%d renderer2dLines=%u latchedLines=%u previousLines=%u renderer3dLines=%u emptyLines=%u any2d=%u line0=%08X center=%08X last=%08X valid=%u remaining=%u",
                softPackedSnapshot.frontBufferLatched,
                linesFromRenderer2d,
                linesFromLatchedValid,
                linesFromPreviousFrame,
                linesFromRenderer3d,
                emptyLines,
            renderer2dCapture3dSourceHasPixels ? 1u : 0u,
            capture3dSource[0],
            capture3dSource[centerIndex],
            capture3dSource[(256u * 192u) - 1u],
            resource.hasPreparedCapture3dSource ? 1u : 0u,
            packedDebugLogsRemaining
        );
        packedDebugLogsRemaining--;
    }

    return resource.hasPreparedCapture3dSource || !currentFrameNeedsCapture3dSource;
}

bool VulkanOutput::captureRenderer3dSnapshot(Frame* frame, const melonDS::VulkanRenderer3D& renderer3D, bool snapshotScreenSwap)
{
    std::scoped_lock commandLock(commandPoolLock);

    if (frame != nullptr)
        frame->renderTimelineValue = 0;

    if (!initialized || frame == nullptr || !renderer3D.HasColorTarget())
        return false;

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;
    resource.hasPreparedInputs = false;
    resource.snapshotFromPreRun = false;
    resource.snapshotFromInitializedTarget = false;
    resource.snapshotFromGraphicsBackend = false;

    if (!renderer3D.IsColorTargetInitialized())
        return false;

    if (!beginFrameCommand(resource))
        return false;

    if (!recordRenderer3dSnapshotCopy(resource, renderer3D, snapshotScreenSwap))
        return false;

    resource.snapshotFromPreRun = true;
    resource.snapshotFromInitializedTarget = true;
    resource.snapshotFromGraphicsBackend =
        renderer3D.GetActiveBackendMode() == melonDS::VulkanRenderer3D::BackendMode::GraphicsHardware;
    resource.previousTopSourceFrame = nullptr;
    resource.previousTopSourcePending = false;
    resource.previousBottomSourceFrame = nullptr;
    resource.previousBottomSourcePending = false;

    const bool submitted = submitFrameCommand(frame, resource, true);
    if (submitted)
        resource.timestampPending = false;
    return submitted;
}

bool VulkanOutput::composeAndSubmitFrame(
    Frame* frame,
    const VulkanCompositionInputs& inputs)
{
    if (!initialized || frame == nullptr || inputs.scale < 1 || inputs.sourceImage == VK_NULL_HANDLE || inputs.sourceImageView == VK_NULL_HANDLE)
        return false;

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;

    const u64 composeStartNs = PerfNowNs();
    const bool dispatched = dispatchCompositor(frame, resource, inputs);
    composeCpuWindow.Add(PerfNowNs() - composeStartNs);
    logPerformanceIfNeeded();
    return dispatched;
}

bool VulkanOutput::buildCompositionInputs(
    const Frame* frame,
    const melonDS::VulkanRenderer3D& renderer3D,
    int scale,
    VulkanFilterMode filtering,
    bool needsReadback,
    bool multiSurface,
    bool validationMode,
    VulkanCompositionInputs& outInputs) const
{
    if (!initialized || frame == nullptr || scale < 1)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    const FrameResource& resource = iterator->second;
    if (!resource.hasPreparedInputs)
        return false;

    const bool hasRenderer3dSnapshot =
        resource.hasRenderer3dSnapshot
        && resource.renderer3dSnapshot != VK_NULL_HANDLE
        && resource.renderer3dSnapshotView != VK_NULL_HANDLE;
    if (!hasRenderer3dSnapshot && !renderer3D.HasColorTarget())
        return false;

    if (hasRenderer3dSnapshot)
    {
        outInputs.sourceImage = resource.renderer3dSnapshot;
        outInputs.sourceImageView = resource.renderer3dSnapshotView;
        outInputs.rendererWidth = resource.snapshotWidth;
        outInputs.rendererHeight = resource.snapshotHeight;
    }
    else
    {
        outInputs.sourceImage = renderer3D.GetColorTargetImage();
        outInputs.sourceImageView = renderer3D.GetColorTargetImageView();
        outInputs.rendererWidth = renderer3D.GetColorTargetWidth();
        outInputs.rendererHeight = renderer3D.GetColorTargetHeight();
    }
    outInputs.previousTopSourceValid = resource.previousTopRendererSourceValid;
    outInputs.previousTopSourceImage = outInputs.previousTopSourceValid && resource.previousTopRendererSourceImage != VK_NULL_HANDLE
        ? resource.previousTopRendererSourceImage
        : outInputs.sourceImage;
    outInputs.previousTopSourceImageView = outInputs.previousTopSourceValid && resource.previousTopRendererSourceImageView != VK_NULL_HANDLE
        ? resource.previousTopRendererSourceImageView
        : outInputs.sourceImageView;
    outInputs.previousBottomSourceValid = resource.previousBottomRendererSourceValid;
    outInputs.previousBottomSourceImage = outInputs.previousBottomSourceValid && resource.previousBottomRendererSourceImage != VK_NULL_HANDLE
        ? resource.previousBottomRendererSourceImage
        : outInputs.sourceImage;
    outInputs.previousBottomSourceImageView = outInputs.previousBottomSourceValid && resource.previousBottomRendererSourceImageView != VK_NULL_HANDLE
        ? resource.previousBottomRendererSourceImageView
        : outInputs.sourceImageView;
    outInputs.liveSourceScreenSwap = resource.hasRenderer3dSnapshot
        ? resource.renderer3dSnapshotScreenSwap
        : resource.screenSwap;
    constexpr u32 currentCaptureLineThreshold = kScreenHeight / 2u;
    constexpr u32 dominantStructuredSlotThreshold = (kScreenWidth * kScreenHeight) / 2u;
    const bool topUsesRegularCapture3d =
        resource.topScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomUsesRegularCapture3d =
        resource.bottomScreenStats.RegularCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topUsesVramCapture3d =
        resource.topScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool bottomUsesVramCapture3d =
        resource.bottomScreenStats.VramCaptureUses3dLines > currentCaptureLineThreshold;
    const bool topUsesStructured3d =
        resource.topScreenStats.StructuredSlotPixels > dominantStructuredSlotThreshold;
    const bool bottomUsesStructured3d =
        resource.bottomScreenStats.StructuredSlotPixels > dominantStructuredSlotThreshold;
    outInputs.class4VramStructuredPair =
        resource.captureBackedClass4Only
        && !topUsesRegularCapture3d
        && !bottomUsesRegularCapture3d
        && (topUsesVramCapture3d != bottomUsesVramCapture3d)
        && (topUsesStructured3d != bottomUsesStructured3d);
    outInputs.class4NoAboveVramStructuredPair =
        outInputs.class4VramStructuredPair
        && resource.class4NoAboveVramStructuredPair;
    outInputs.class4PreservePackedVramValid =
        outInputs.class4VramStructuredPair
        && resource.class4PreservePackedVramValid;
    outInputs.class4PreservePackedVramScreenSwap =
        resource.class4PreservePackedVramScreenSwap;
    outInputs.topStructuredHandoffNoCurrent3d = resource.topStructuredHandoffNoCurrent3d;
    outInputs.bottomStructuredHandoffNoCurrent3d = resource.bottomStructuredHandoffNoCurrent3d;
    outInputs.topStructuredHandoffSuppress3d = resource.topStructuredHandoffSuppress3d;
    outInputs.bottomStructuredHandoffSuppress3d = resource.bottomStructuredHandoffSuppress3d;
    outInputs.topPackedBuffer = resource.topPackedBuffer;
    outInputs.bottomPackedBuffer = resource.bottomPackedBuffer;
    outInputs.capture3dBuffer = resource.capture3dBuffer;
    outInputs.packedBufferSize = resource.packedBufferSize;
    outInputs.capture3dBufferSize = kCapture3dBufferSize;
    outInputs.packedStride = kAcceleratedStride;
    outInputs.screenSwap = resource.screenSwap ? 1u : 0u;
    outInputs.scale = static_cast<u32>(scale);
    outInputs.filtering = filtering;
    outInputs.capture3dSourceValid = resource.hasPreparedCapture3dSource && resource.capture3dBuffer != VK_NULL_HANDLE;
    const bool topUsesCurrentCapture3d = topUsesRegularCapture3d || topUsesVramCapture3d;
    const bool bottomUsesCurrentCapture3d = bottomUsesRegularCapture3d || bottomUsesVramCapture3d;
    const bool asymmetricRegularCapture3d =
        topUsesRegularCapture3d != bottomUsesRegularCapture3d
        && !topUsesVramCapture3d
        && !bottomUsesVramCapture3d;
    outInputs.capture3dSourceScreenSwapValid =
        asymmetricRegularCapture3d || (topUsesCurrentCapture3d != bottomUsesCurrentCapture3d);
    outInputs.capture3dSourceScreenSwap = asymmetricRegularCapture3d
        ? topUsesRegularCapture3d
        : topUsesCurrentCapture3d;
    outInputs.needsReadback = needsReadback;
    outInputs.multiSurface = multiSurface;
    outInputs.validationMode = validationMode;
    return outInputs.sourceImage != VK_NULL_HANDLE
        && outInputs.sourceImageView != VK_NULL_HANDLE
        && outInputs.previousTopSourceImage != VK_NULL_HANDLE
        && outInputs.previousTopSourceImageView != VK_NULL_HANDLE
        && outInputs.previousBottomSourceImage != VK_NULL_HANDLE
        && outInputs.previousBottomSourceImageView != VK_NULL_HANDLE
        && outInputs.topPackedBuffer != VK_NULL_HANDLE
        && outInputs.bottomPackedBuffer != VK_NULL_HANDLE
        && outInputs.capture3dBuffer != VK_NULL_HANDLE;
}

void VulkanOutput::destroyRenderer3dSnapshot(FrameResource& resource)
{
    if (resource.renderer3dSnapshotView != VK_NULL_HANDLE)
    {
        vkDestroyImageView(device, resource.renderer3dSnapshotView, nullptr);
        resource.renderer3dSnapshotView = VK_NULL_HANDLE;
    }
    if (resource.renderer3dSnapshot != VK_NULL_HANDLE)
    {
        vkDestroyImage(device, resource.renderer3dSnapshot, nullptr);
        resource.renderer3dSnapshot = VK_NULL_HANDLE;
    }
    if (resource.renderer3dSnapshotMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(device, resource.renderer3dSnapshotMemory, nullptr);
        resource.renderer3dSnapshotMemory = VK_NULL_HANDLE;
    }

    resource.snapshotWidth = 0;
    resource.snapshotHeight = 0;
    resource.hasRenderer3dSnapshot = false;
}

bool VulkanOutput::ensureRenderer3dSnapshot(FrameResource& resource, u32 width, u32 height)
{
    if (width == 0 || height == 0)
        return false;

    if (resource.renderer3dSnapshot != VK_NULL_HANDLE
        && resource.renderer3dSnapshotView != VK_NULL_HANDLE
        && resource.snapshotWidth == width
        && resource.snapshotHeight == height)
        return true;

    destroyRenderer3dSnapshot(resource);

    VkImageCreateInfo imageCreateInfo{};
    imageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    imageCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageCreateInfo.extent = {width, height, 1};
    imageCreateInfo.mipLevels = 1;
    imageCreateInfo.arrayLayers = 1;
    imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageCreateInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(device, &imageCreateInfo, nullptr, &resource.renderer3dSnapshot) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memoryRequirements{};
    vkGetImageMemoryRequirements(device, resource.renderer3dSnapshot, &memoryRequirements);

    VkMemoryAllocateInfo memoryAllocateInfo{};
    memoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryAllocateInfo.allocationSize = memoryRequirements.size;
    memoryAllocateInfo.memoryTypeIndex = findMemoryType(memoryRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (memoryAllocateInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(device, &memoryAllocateInfo, nullptr, &resource.renderer3dSnapshotMemory) != VK_SUCCESS)
    {
        vkDestroyImage(device, resource.renderer3dSnapshot, nullptr);
        resource.renderer3dSnapshot = VK_NULL_HANDLE;
        return false;
    }

    if (vkBindImageMemory(device, resource.renderer3dSnapshot, resource.renderer3dSnapshotMemory, 0) != VK_SUCCESS)
    {
        destroyRenderer3dSnapshot(resource);
        return false;
    }

    VkImageViewCreateInfo imageViewCreateInfo{};
    imageViewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    imageViewCreateInfo.image = resource.renderer3dSnapshot;
    imageViewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    imageViewCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageViewCreateInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageViewCreateInfo.subresourceRange.baseMipLevel = 0;
    imageViewCreateInfo.subresourceRange.levelCount = 1;
    imageViewCreateInfo.subresourceRange.baseArrayLayer = 0;
    imageViewCreateInfo.subresourceRange.layerCount = 1;

    if (vkCreateImageView(device, &imageViewCreateInfo, nullptr, &resource.renderer3dSnapshotView) != VK_SUCCESS)
    {
        destroyRenderer3dSnapshot(resource);
        return false;
    }

    resource.snapshotWidth = width;
    resource.snapshotHeight = height;
    return true;
}

bool VulkanOutput::recordDirectPresentationPrep(
    Frame* frame,
    FrameResource& resource,
    const melonDS::VulkanRenderer3D& renderer3D,
    bool snapshotScreenSwap,
    bool accumulateTopHighres,
    bool accumulateBottomHighres,
    bool replaceAccumulatedHighres)
{
    std::scoped_lock commandLock(commandPoolLock);

    if (!beginFrameCommand(resource))
        return false;

    if (!recordRenderer3dSnapshotCopy(resource, renderer3D, snapshotScreenSwap))
        return false;

    resource.snapshotFromPreRun = false;
    resource.snapshotFromInitializedTarget = renderer3D.IsColorTargetInitialized();
    resource.snapshotFromGraphicsBackend =
        renderer3D.GetActiveBackendMode() == melonDS::VulkanRenderer3D::BackendMode::GraphicsHardware;

    if (resource.snapshotFromGraphicsBackend && resource.hasRenderer3dSnapshot)
    {
        VkBufferMemoryBarrier topPackedToAccumulateBarrier{};
        topPackedToAccumulateBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        topPackedToAccumulateBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        topPackedToAccumulateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        topPackedToAccumulateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        topPackedToAccumulateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        topPackedToAccumulateBarrier.buffer = resource.topPackedBuffer;
        topPackedToAccumulateBarrier.offset = 0;
        topPackedToAccumulateBarrier.size = resource.packedBufferSize;

        VkBufferMemoryBarrier bottomPackedToAccumulateBarrier{};
        bottomPackedToAccumulateBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        bottomPackedToAccumulateBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        bottomPackedToAccumulateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bottomPackedToAccumulateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bottomPackedToAccumulateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bottomPackedToAccumulateBarrier.buffer = resource.bottomPackedBuffer;
        bottomPackedToAccumulateBarrier.offset = 0;
        bottomPackedToAccumulateBarrier.size = resource.packedBufferSize;

        std::array<VkBufferMemoryBarrier, 2> packedToAccumulateBarriers = {
            topPackedToAccumulateBarrier,
            bottomPackedToAccumulateBarrier,
        };
        vkCmdPipelineBarrier(
            resource.commandBuffer,
            VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            static_cast<u32>(packedToAccumulateBarriers.size()),
            packedToAccumulateBarriers.data(),
            0,
            nullptr
        );

        if (accumulateTopHighres)
            (void)recordAccumulateMerge(resource, true, replaceAccumulatedHighres);
        if (accumulateBottomHighres)
            (void)recordAccumulateMerge(resource, false, replaceAccumulatedHighres);
    }

    VkBufferMemoryBarrier topPackedBarrier{};
    topPackedBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    topPackedBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    topPackedBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    topPackedBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    topPackedBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    topPackedBarrier.buffer = resource.topPackedBuffer;
    topPackedBarrier.offset = 0;
    topPackedBarrier.size = resource.packedBufferSize;

    VkBufferMemoryBarrier bottomPackedBarrier{};
    bottomPackedBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bottomPackedBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    bottomPackedBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bottomPackedBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bottomPackedBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bottomPackedBarrier.buffer = resource.bottomPackedBuffer;
    bottomPackedBarrier.offset = 0;
    bottomPackedBarrier.size = resource.packedBufferSize;

    std::array<VkBufferMemoryBarrier, 2> bufferBarriers = {
        topPackedBarrier,
        bottomPackedBarrier,
    };
    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        static_cast<u32>(bufferBarriers.size()),
        bufferBarriers.data(),
        0,
        nullptr
    );

    const bool submitted = submitFrameCommand(frame, resource, true);
    if (submitted)
        resource.timestampPending = false;
    return submitted;
}

bool VulkanOutput::recordRenderer3dSnapshotCopy(FrameResource& resource, const melonDS::VulkanRenderer3D& renderer3D, bool snapshotScreenSwap)
{
    const u32 rendererWidth = renderer3D.GetColorTargetWidth();
    const u32 rendererHeight = renderer3D.GetColorTargetHeight();
    if (!ensureRenderer3dSnapshot(resource, rendererWidth, rendererHeight))
        return false;

    VkImageMemoryBarrier sourceToTransferBarrier{};
    sourceToTransferBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    sourceToTransferBarrier.srcAccessMask =
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
        VK_ACCESS_SHADER_WRITE_BIT |
        VK_ACCESS_TRANSFER_WRITE_BIT |
        VK_ACCESS_TRANSFER_READ_BIT;
    sourceToTransferBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceToTransferBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceToTransferBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    sourceToTransferBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToTransferBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceToTransferBarrier.image = renderer3D.GetColorTargetImage();
    sourceToTransferBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    sourceToTransferBarrier.subresourceRange.baseMipLevel = 0;
    sourceToTransferBarrier.subresourceRange.levelCount = 1;
    sourceToTransferBarrier.subresourceRange.baseArrayLayer = 0;
    sourceToTransferBarrier.subresourceRange.layerCount = 1;

    VkImageMemoryBarrier snapshotToTransferBarrier{};
    snapshotToTransferBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    snapshotToTransferBarrier.srcAccessMask = resource.hasRenderer3dSnapshot ? VK_ACCESS_SHADER_READ_BIT : 0;
    snapshotToTransferBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    snapshotToTransferBarrier.oldLayout = resource.hasRenderer3dSnapshot ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    snapshotToTransferBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    snapshotToTransferBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    snapshotToTransferBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    snapshotToTransferBarrier.image = resource.renderer3dSnapshot;
    snapshotToTransferBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    snapshotToTransferBarrier.subresourceRange.baseMipLevel = 0;
    snapshotToTransferBarrier.subresourceRange.levelCount = 1;
    snapshotToTransferBarrier.subresourceRange.baseArrayLayer = 0;
    snapshotToTransferBarrier.subresourceRange.layerCount = 1;

    std::array<VkImageMemoryBarrier, 2> preCopyBarriers = {
        sourceToTransferBarrier,
        snapshotToTransferBarrier,
    };
    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        static_cast<u32>(preCopyBarriers.size()),
        preCopyBarriers.data()
    );

    if (vkCmdCopyImage != nullptr)
    {
        VkImageCopy copyRegion{};
        copyRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.srcSubresource.layerCount = 1;
        copyRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.dstSubresource.layerCount = 1;
        copyRegion.extent = {rendererWidth, rendererHeight, 1};
        vkCmdCopyImage(
            resource.commandBuffer,
            renderer3D.GetColorTargetImage(),
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            resource.renderer3dSnapshot,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &copyRegion
        );
    }
    else if (vkCmdBlitImage != nullptr)
    {
        VkImageBlit blitRegion{};
        blitRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blitRegion.srcSubresource.layerCount = 1;
        blitRegion.srcOffsets[1] = {static_cast<int32_t>(rendererWidth), static_cast<int32_t>(rendererHeight), 1};
        blitRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blitRegion.dstSubresource.layerCount = 1;
        blitRegion.dstOffsets[1] = {static_cast<int32_t>(rendererWidth), static_cast<int32_t>(rendererHeight), 1};
        vkCmdBlitImage(
            resource.commandBuffer,
            renderer3D.GetColorTargetImage(),
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            resource.renderer3dSnapshot,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &blitRegion,
            VK_FILTER_NEAREST
        );
    }
    else
    {
        return false;
    }

    VkImageMemoryBarrier sourceBackToGeneralBarrier{};
    sourceBackToGeneralBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    sourceBackToGeneralBarrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceBackToGeneralBarrier.dstAccessMask =
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
        VK_ACCESS_SHADER_READ_BIT |
        VK_ACCESS_SHADER_WRITE_BIT;
    sourceBackToGeneralBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    sourceBackToGeneralBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceBackToGeneralBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceBackToGeneralBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceBackToGeneralBarrier.image = renderer3D.GetColorTargetImage();
    sourceBackToGeneralBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    sourceBackToGeneralBarrier.subresourceRange.baseMipLevel = 0;
    sourceBackToGeneralBarrier.subresourceRange.levelCount = 1;
    sourceBackToGeneralBarrier.subresourceRange.baseArrayLayer = 0;
    sourceBackToGeneralBarrier.subresourceRange.layerCount = 1;

    VkImageMemoryBarrier snapshotToReadableBarrier{};
    snapshotToReadableBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    snapshotToReadableBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    snapshotToReadableBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    snapshotToReadableBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    snapshotToReadableBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    snapshotToReadableBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    snapshotToReadableBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    snapshotToReadableBarrier.image = resource.renderer3dSnapshot;
    snapshotToReadableBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    snapshotToReadableBarrier.subresourceRange.baseMipLevel = 0;
    snapshotToReadableBarrier.subresourceRange.levelCount = 1;
    snapshotToReadableBarrier.subresourceRange.baseArrayLayer = 0;
    snapshotToReadableBarrier.subresourceRange.layerCount = 1;

    std::array<VkImageMemoryBarrier, 2> postCopyBarriers = {
        sourceBackToGeneralBarrier,
        snapshotToReadableBarrier,
    };
    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        static_cast<u32>(postCopyBarriers.size()),
        postCopyBarriers.data()
    );

    resource.hasRenderer3dSnapshot = true;
    resource.renderer3dSnapshotScreenSwap = snapshotScreenSwap;
    return true;
}

bool VulkanOutput::dispatchCompositor(
    Frame* frame,
    FrameResource& resource,
    const VulkanCompositionInputs& inputs)
{
    std::scoped_lock commandLock(commandPoolLock);

    if (!beginFrameCommand(resource))
        return false;

    if (resource.timestampQueryPool != VK_NULL_HANDLE)
        vkCmdWriteTimestamp(resource.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, resource.timestampQueryPool, 0);

    VkImageMemoryBarrier outputToGeneralBarrier{};
    outputToGeneralBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    outputToGeneralBarrier.srcAccessMask = resource.hasContent ? (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT) : 0;
    outputToGeneralBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputToGeneralBarrier.oldLayout = resource.hasContent ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    outputToGeneralBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputToGeneralBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputToGeneralBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputToGeneralBarrier.image = resource.image;
    outputToGeneralBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    outputToGeneralBarrier.subresourceRange.baseMipLevel = 0;
    outputToGeneralBarrier.subresourceRange.levelCount = 1;
    outputToGeneralBarrier.subresourceRange.baseArrayLayer = 0;
    outputToGeneralBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        resource.hasContent ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &outputToGeneralBarrier
    );

    std::array<VkImageMemoryBarrier, 3> renderer3dReadableBarriers{};
    u32 renderer3dBarrierCount = 0;
    auto appendRenderer3dBarrier = [&](VkImage image) {
        if (image == VK_NULL_HANDLE)
            return;

        for (u32 i = 0; i < renderer3dBarrierCount; i++)
        {
            if (renderer3dReadableBarriers[i].image == image)
                return;
        }

        VkImageMemoryBarrier& barrier = renderer3dReadableBarriers[renderer3dBarrierCount++];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask =
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
            VK_ACCESS_SHADER_WRITE_BIT |
            VK_ACCESS_TRANSFER_WRITE_BIT |
            VK_ACCESS_TRANSFER_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;
    };
    appendRenderer3dBarrier(inputs.sourceImage);
    if (renderer3dBarrierCount < renderer3dReadableBarriers.size())
        appendRenderer3dBarrier(inputs.previousTopSourceImage);
    if (renderer3dBarrierCount < renderer3dReadableBarriers.size())
        appendRenderer3dBarrier(inputs.previousBottomSourceImage);

    if (renderer3dBarrierCount > 0)
    {
        vkCmdPipelineBarrier(
            resource.commandBuffer,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            renderer3dBarrierCount,
            renderer3dReadableBarriers.data()
        );
    }

    VkBufferMemoryBarrier topPackedBarrier{};
    topPackedBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    topPackedBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    topPackedBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    topPackedBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    topPackedBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    topPackedBarrier.buffer = resource.topPackedBuffer;
    topPackedBarrier.offset = 0;
    topPackedBarrier.size = resource.packedBufferSize;

    VkBufferMemoryBarrier bottomPackedBarrier{};
    bottomPackedBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bottomPackedBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    bottomPackedBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bottomPackedBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bottomPackedBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bottomPackedBarrier.buffer = resource.bottomPackedBuffer;
    bottomPackedBarrier.offset = 0;
    bottomPackedBarrier.size = resource.packedBufferSize;

    VkBufferMemoryBarrier capture3dBarrier{};
    capture3dBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    capture3dBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    capture3dBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    capture3dBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    capture3dBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    capture3dBarrier.buffer = resource.capture3dBuffer;
    capture3dBarrier.offset = 0;
    capture3dBarrier.size = kCapture3dBufferSize;

    std::array<VkBufferMemoryBarrier, 3> compositorBufferBarriers = {
        topPackedBarrier,
        bottomPackedBarrier,
        capture3dBarrier,
    };

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        static_cast<u32>(compositorBufferBarriers.size()),
        compositorBufferBarriers.data(),
        0,
        nullptr
    );

    VkDescriptorImageInfo outputImageInfo{};
    outputImageInfo.imageView = resource.imageView;
    outputImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorImageInfo input3dImageInfo{};
    input3dImageInfo.imageView = inputs.sourceImageView;
    input3dImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo previousTopInput3dImageInfo{};
    previousTopInput3dImageInfo.imageView = inputs.previousTopSourceImageView;
    previousTopInput3dImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo previousBottomInput3dImageInfo{};
    previousBottomInput3dImageInfo.imageView = inputs.previousBottomSourceImageView;
    previousBottomInput3dImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorBufferInfo topPackedBufferInfo{};
    topPackedBufferInfo.buffer = resource.topPackedBuffer;
    topPackedBufferInfo.offset = 0;
    topPackedBufferInfo.range = resource.packedBufferSize;

    VkDescriptorBufferInfo bottomPackedBufferInfo{};
    bottomPackedBufferInfo.buffer = resource.bottomPackedBuffer;
    bottomPackedBufferInfo.offset = 0;
    bottomPackedBufferInfo.range = resource.packedBufferSize;

    VkDescriptorBufferInfo capture3dBufferInfo{};
    capture3dBufferInfo.buffer = resource.capture3dBuffer;
    capture3dBufferInfo.offset = 0;
    capture3dBufferInfo.range = kCapture3dBufferSize;

    if (!resource.descriptorSetReady
        || resource.cachedRendererImageView != inputs.sourceImageView
        || resource.cachedPreviousTopRendererImageView != inputs.previousTopSourceImageView
        || resource.cachedPreviousBottomRendererImageView != inputs.previousBottomSourceImageView)
    {
        std::array<VkWriteDescriptorSet, 7> descriptorWrites{};
        descriptorWrites[0] = makeImageDescriptorWrite(resource.descriptorSet, 0, &outputImageInfo);
        descriptorWrites[1] = makeImageDescriptorWrite(resource.descriptorSet, 1, &input3dImageInfo);
        descriptorWrites[2] = makeBufferDescriptorWrite(resource.descriptorSet, 2, &topPackedBufferInfo);
        descriptorWrites[3] = makeBufferDescriptorWrite(resource.descriptorSet, 3, &bottomPackedBufferInfo);
        descriptorWrites[4] = makeImageDescriptorWrite(resource.descriptorSet, 4, &previousTopInput3dImageInfo);
        descriptorWrites[5] = makeBufferDescriptorWrite(resource.descriptorSet, 5, &capture3dBufferInfo);
        descriptorWrites[6] = makeImageDescriptorWrite(resource.descriptorSet, 6, &previousBottomInput3dImageInfo);

        vkUpdateDescriptorSets(device, static_cast<u32>(descriptorWrites.size()), descriptorWrites.data(), 0, nullptr);
        resource.descriptorSetReady = true;
        resource.cachedRendererImageView = inputs.sourceImageView;
        resource.cachedPreviousTopRendererImageView = inputs.previousTopSourceImageView;
        resource.cachedPreviousBottomRendererImageView = inputs.previousBottomSourceImageView;
    }

    vkCmdBindPipeline(resource.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, compositorPipeline);
    vkCmdBindDescriptorSets(
        resource.commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        compositorPipelineLayout,
        0,
        1,
        &resource.descriptorSet,
        0,
        nullptr
    );

    CompositorPushConstants pushConstants{};
    pushConstants.outputWidth = resource.width;
    pushConstants.outputHeight = resource.height;
    pushConstants.scale = inputs.scale;
    pushConstants.rendererWidth = inputs.rendererWidth;
    pushConstants.rendererHeight = inputs.rendererHeight;
    pushConstants.packedStride = inputs.packedStride;
    pushConstants.screenSwap = inputs.screenSwap;
    pushConstants.filtering = static_cast<u32>(inputs.filtering);
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

    vkCmdPushConstants(
        resource.commandBuffer,
        compositorPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(pushConstants),
        &pushConstants
    );

    const u32 groupCountX = (resource.width + 7u) / 8u;
    const u32 groupCountY = (resource.height + 7u) / 8u;
    vkCmdDispatch(resource.commandBuffer, groupCountX, groupCountY, 1);

    auto replayPreviousComposedLcd = [&](Frame* sourceFrame, bool topLcd) {
        if (sourceFrame == nullptr || sourceFrame == frame)
            return false;

        const auto sourceIt = resources.find(sourceFrame);
        if (sourceIt == resources.end())
            return false;

        const FrameResource& sourceResource = sourceIt->second;
        if (!sourceResource.hasContent
            || sourceResource.image == VK_NULL_HANDLE
            || sourceResource.width != resource.width
            || sourceResource.height != resource.height)
        {
            return false;
        }

        const u32 safeScale = inputs.scale == 0u ? 1u : inputs.scale;
        const u32 copyWidth = kScreenWidth * safeScale;
        const u32 copyHeight = kScreenHeight * safeScale;
        const u32 copyY = topLcd ? 0u : ((kScreenHeight + 2u) * safeScale);
        if (copyWidth > resource.width || copyY + copyHeight > resource.height)
            return false;

        VkImageMemoryBarrier sourceToTransfer{};
        sourceToTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        sourceToTransfer.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        sourceToTransfer.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        sourceToTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        sourceToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceToTransfer.image = sourceResource.image;
        sourceToTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        sourceToTransfer.subresourceRange.levelCount = 1;
        sourceToTransfer.subresourceRange.layerCount = 1;

        VkImageMemoryBarrier destToTransfer{};
        destToTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        destToTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        destToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        destToTransfer.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        destToTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        destToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        destToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        destToTransfer.image = resource.image;
        destToTransfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        destToTransfer.subresourceRange.levelCount = 1;
        destToTransfer.subresourceRange.layerCount = 1;

        std::array<VkImageMemoryBarrier, 2> toTransferBarriers = {sourceToTransfer, destToTransfer};
        vkCmdPipelineBarrier(
            resource.commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            static_cast<u32>(toTransferBarriers.size()),
            toTransferBarriers.data()
        );

        VkImageCopy copyRegion{};
        copyRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.srcSubresource.layerCount = 1;
        copyRegion.srcOffset = {0, static_cast<int32_t>(copyY), 0};
        copyRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.dstSubresource.layerCount = 1;
        copyRegion.dstOffset = {0, static_cast<int32_t>(copyY), 0};
        copyRegion.extent = {copyWidth, copyHeight, 1};
        vkCmdCopyImage(
            resource.commandBuffer,
            sourceResource.image,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            resource.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &copyRegion
        );

        sourceToTransfer.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        sourceToTransfer.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        sourceToTransfer.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        sourceToTransfer.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        destToTransfer.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        destToTransfer.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        destToTransfer.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        destToTransfer.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        std::array<VkImageMemoryBarrier, 2> fromTransferBarriers = {sourceToTransfer, destToTransfer};
        vkCmdPipelineBarrier(
            resource.commandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            static_cast<u32>(fromTransferBarriers.size()),
            fromTransferBarriers.data()
        );
        return true;
    };

    Frame* topComposedReplaySource = resource.replayTopComposedFromLatest
        ? lastTopComposedFrame
        : resource.previousTopComposedFrame;
    if (topComposedReplaySource == frame)
        topComposedReplaySource = nullptr;

    const bool replayedTopComposed = resource.replayTopComposedFromPrevious
        && replayPreviousComposedLcd(topComposedReplaySource, true);
    const bool replayedBottomComposed = resource.replayBottomComposedFromPrevious
        && replayPreviousComposedLcd(resource.previousBottomComposedFrame, false);
    if ((resource.replayTopComposedFromPrevious || resource.replayBottomComposedFromPrevious)
        && (!replayedTopComposed || !replayedBottomComposed)
        && areRendererDebugBgObjLogsEnabled()
        && structuredComp7HandoffDebugLogsRemaining > 0)
    {
        const auto describeComposedSource = [&](Frame* sourceFrame, bool topLcd) {
            if (sourceFrame == nullptr)
                return 0u;
            const auto sourceIt = resources.find(sourceFrame);
            if (sourceIt == resources.end())
                return 1u;
            const FrameResource& sourceResource = sourceIt->second;
            if (!sourceResource.hasContent)
                return 2u;
            if (sourceResource.image == VK_NULL_HANDLE)
                return 3u;
            if (sourceResource.width != resource.width || sourceResource.height != resource.height)
                return 4u;
            const u32 safeScale = inputs.scale == 0u ? 1u : inputs.scale;
            const u32 copyWidth = kScreenWidth * safeScale;
            const u32 copyHeight = kScreenHeight * safeScale;
            const u32 copyY = topLcd ? 0u : ((kScreenHeight + 2u) * safeScale);
            if (copyWidth > resource.width || copyY + copyHeight > resource.height)
                return 5u;
            return 9u;
        };
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Warn,
            "VulkanLive3D[ReplayComposed]: frameId=%u topRequested=%u topCopied=%u topSourceFrame=%u topSourceState=%u bottomRequested=%u bottomCopied=%u bottomSourceFrame=%u bottomSourceState=%u remaining=%u",
            frame != nullptr ? static_cast<unsigned>(frame->frameId) : 0u,
            resource.replayTopComposedFromPrevious ? 1u : 0u,
            replayedTopComposed ? 1u : 0u,
            topComposedReplaySource != nullptr ? static_cast<unsigned>(topComposedReplaySource->frameId) : 0u,
            describeComposedSource(topComposedReplaySource, true),
            resource.replayBottomComposedFromPrevious ? 1u : 0u,
            replayedBottomComposed ? 1u : 0u,
            resource.previousBottomComposedFrame != nullptr ? static_cast<unsigned>(resource.previousBottomComposedFrame->frameId) : 0u,
            describeComposedSource(resource.previousBottomComposedFrame, false),
            structuredComp7HandoffDebugLogsRemaining);
        structuredComp7HandoffDebugLogsRemaining--;
    }

    if (resource.timestampQueryPool != VK_NULL_HANDLE)
        vkCmdWriteTimestamp(resource.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, resource.timestampQueryPool, 1);

    VkImageMemoryBarrier outputReadableBarrier{};
    outputReadableBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    outputReadableBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputReadableBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    outputReadableBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputReadableBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputReadableBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReadableBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReadableBarrier.image = resource.image;
    outputReadableBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    outputReadableBarrier.subresourceRange.baseMipLevel = 0;
    outputReadableBarrier.subresourceRange.levelCount = 1;
    outputReadableBarrier.subresourceRange.baseArrayLayer = 0;
    outputReadableBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &outputReadableBarrier
    );

    if (!submitFrameCommand(frame, resource, true))
        return false;

    resource.hasContent = true;
    resource.previousTopSourcePending = false;
    resource.previousBottomSourcePending = false;
    lastTopComposedFrame = frame;
    lastBottomComposedFrame = frame;
    return true;
}

bool VulkanOutput::validateCompositorSubmission(Frame* frame, const melonDS::VulkanRenderer3D& renderer3D, int scale, u64 waitTimeoutNs)
{
    if (!initialized || frame == nullptr || scale < 1 || !renderer3D.HasColorTarget())
        return false;

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;
    if (resource.topPackedMapped == nullptr || resource.bottomPackedMapped == nullptr || resource.packedBufferSize == 0)
        return false;
    std::memset(resource.topPackedMapped, 0, static_cast<size_t>(resource.packedBufferSize));
    std::memset(resource.bottomPackedMapped, 0, static_cast<size_t>(resource.packedBufferSize));
    resource.hasPreparedInputs = true;

    VulkanCompositionInputs inputs{};
    if (!buildCompositionInputs(frame, renderer3D, scale, VulkanFilterMode::Nearest, false, false, true, inputs))
        return false;

    if (!dispatchCompositor(frame, resource, inputs))
        return false;

    if (!waitForFrame(frame, waitTimeoutNs))
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanOutput: validateCompositorSubmission timed out (timeoutNs=%llu)",
            static_cast<unsigned long long>(waitTimeoutNs)
        );
        return false;
    }

    return true;
}

bool VulkanOutput::validateFrameSubmission(Frame* frame, u64 waitTimeoutNs)
{
    std::scoped_lock commandLock(commandPoolLock);

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(frame);
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;
    if (!beginFrameCommand(resource, waitTimeoutNs))
        return false;

    VkImageMemoryBarrier toTransferDstBarrier{};
    toTransferDstBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferDstBarrier.srcAccessMask = resource.hasContent ? (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT) : 0;
    toTransferDstBarrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferDstBarrier.oldLayout = resource.hasContent ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    toTransferDstBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransferDstBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDstBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDstBarrier.image = resource.image;
    toTransferDstBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toTransferDstBarrier.subresourceRange.baseMipLevel = 0;
    toTransferDstBarrier.subresourceRange.levelCount = 1;
    toTransferDstBarrier.subresourceRange.baseArrayLayer = 0;
    toTransferDstBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        resource.hasContent ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &toTransferDstBarrier
    );

    VkClearColorValue clearColor{};
    clearColor.float32[0] = 0.0f;
    clearColor.float32[1] = 0.0f;
    clearColor.float32[2] = 0.0f;
    clearColor.float32[3] = 1.0f;

    VkImageSubresourceRange clearRange{};
    clearRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    clearRange.baseMipLevel = 0;
    clearRange.levelCount = 1;
    clearRange.baseArrayLayer = 0;
    clearRange.layerCount = 1;
    vkCmdClearColorImage(
        resource.commandBuffer,
        resource.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        &clearColor,
        1,
        &clearRange
    );

    VkImageMemoryBarrier backToGeneralBarrier{};
    backToGeneralBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    backToGeneralBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    backToGeneralBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    backToGeneralBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    backToGeneralBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    backToGeneralBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    backToGeneralBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    backToGeneralBarrier.image = resource.image;
    backToGeneralBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    backToGeneralBarrier.subresourceRange.baseMipLevel = 0;
    backToGeneralBarrier.subresourceRange.levelCount = 1;
    backToGeneralBarrier.subresourceRange.baseArrayLayer = 0;
    backToGeneralBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &backToGeneralBarrier
    );

    if (!submitFrameCommand(frame, resource, true))
        return false;

    resource.hasContent = true;
    if (!waitForFrame(frame, waitTimeoutNs))
    {
        melonDS::Platform::Log(
            melonDS::Platform::LogLevel::Error,
            "VulkanOutput: validateFrameSubmission timed out (timeoutNs=%llu)",
            static_cast<unsigned long long>(waitTimeoutNs)
        );
        return false;
    }

    return true;
}

bool VulkanOutput::validateRuntimePath(u32 width, u32 height, const melonDS::VulkanRenderer3D& renderer3D, int scale)
{
    (void)renderer3D;
    if (!initialized || width == 0 || height == 0 || scale < 1)
        return false;

    Frame validationFrame{};
    validationFrame.backend = FrameBackend::VulkanImage;
    if (!ensureFrameResources(&validationFrame, width, height))
        return false;

    const bool validationResult = validateFrameSubmission(&validationFrame, kValidationWaitTimeoutNs);

    destroyFrameResource(&validationFrame);
    return validationResult;
}

bool VulkanOutput::waitForFrame(const Frame* frame, u64 timeoutNs)
{
    if (!initialized || frame == nullptr || frame->backend != FrameBackend::VulkanImage)
    {
        waitFailureInvalidFrame++;
        return false;
    }

    if (frame->renderTimelineValue == 0)
    {
        waitFailureTimelineZero++;
        return false;
    }

    const u64 waitStartNs = PerfNowNs();
    bool waitSucceeded = false;

    if (useTimelineSemaphores && waitSemaphores != nullptr && timelineSemaphore != VK_NULL_HANDLE)
    {
        VkSemaphoreWaitInfo waitInfo{};
        waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
        waitInfo.semaphoreCount = 1;
        waitInfo.pSemaphores = &timelineSemaphore;
        waitInfo.pValues = &frame->renderTimelineValue;
        waitSucceeded = waitSemaphores(device, &waitInfo, timeoutNs) == VK_SUCCESS;
    }
    else
    {
        auto iterator = resources.find(const_cast<Frame*>(frame));
        if (iterator == resources.end())
        {
            waitFailureResourceMissing++;
            return false;
        }
        waitSucceeded = vkWaitForFences(device, 1, &iterator->second.submitFence, VK_TRUE, timeoutNs) == VK_SUCCESS;
    }

    if (!waitSucceeded)
    {
        if (timeoutNs == UINT64_MAX)
            waitFailureInfinite++;
        else
            waitFailureFiniteTimeout++;
        return false;
    }

    waitCpuWindow.Add(PerfNowNs() - waitStartNs);

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator != resources.end())
        consumeFrameGpuTiming(iterator->second);

    logPerformanceIfNeeded();
    return true;
}

bool VulkanOutput::getPreparedRenderer3dDimensions(const Frame* frame, u32& outWidth, u32& outHeight) const
{
    outWidth = 0;
    outHeight = 0;

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    const FrameResource& resource = iterator->second;
    if (!resource.hasPreparedInputs || !resource.hasRenderer3dSnapshot)
        return false;

    outWidth = resource.snapshotWidth;
    outHeight = resource.snapshotHeight;
    return outWidth > 0 && outHeight > 0;
}

bool VulkanOutput::getPreparedRenderer3dCaptureFrame(
    const Frame* frame,
    const u32*& outPixels,
    u32& outWidth,
    u32& outHeight) const
{
    outPixels = nullptr;
    outWidth = 0;
    outHeight = 0;

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    const FrameResource& resource = iterator->second;
    if (!resource.hasPreparedInputs || !resource.hasPreparedCapture3dSource)
        return false;

    outPixels = resource.preparedCapture3dSource.data();
    outWidth = kScreenWidth;
    outHeight = kScreenHeight;
    return true;
}

bool VulkanOutput::getPreparedPackedBuffers(
    const Frame* frame,
    const u32*& outTopPacked,
    const u32*& outBottomPacked,
    u32& outPackedStride,
    u32& outPackedHeight,
    bool& outScreenSwap) const
{
    outTopPacked = nullptr;
    outBottomPacked = nullptr;
    outPackedStride = 0;
    outPackedHeight = 0;
    outScreenSwap = false;

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    const FrameResource& resource = iterator->second;
    if (!resource.hasPreparedInputs || resource.topPackedMapped == nullptr || resource.bottomPackedMapped == nullptr)
        return false;

    outTopPacked = static_cast<const u32*>(resource.topPackedMapped);
    outBottomPacked = static_cast<const u32*>(resource.bottomPackedMapped);
    outPackedStride = kAcceleratedStride;
    outPackedHeight = kScreenHeight;
    outScreenSwap = resource.screenSwap;
    return true;
}

bool VulkanOutput::getPreparedSoftPackedFrameDebugView(
    const Frame* frame,
    PreparedSoftPackedFrameDebugView& outView) const
{
    outView = {};

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    const FrameResource& resource = iterator->second;
    if (!resource.hasSoftPackedDebugData)
        return false;

    outView.frameId = resource.softPackedFrameId;
    outView.frontBufferLatched = resource.frontBufferLatched;
    outView.screenSwapLatched = resource.screenSwap;
    outView.captureBackedClass4Only = resource.captureBackedClass4Only;
    outView.capture3dSourceDsFrame = resource.capture3dSourceDsFrame.data();
    outView.captureLineUses3dMask = resource.captureLineUses3dMask.data();
    outView.captureFallbackLines = resource.captureFallbackLines.data();
    outView.comp4TopPlaceholder = resource.comp4TopPlaceholder.data();
    outView.comp4BottomPlaceholder = resource.comp4BottomPlaceholder.data();
    outView.topScreenStats = resource.topScreenStats;
    outView.bottomScreenStats = resource.bottomScreenStats;
    outView.valid = true;
    return true;
}

bool VulkanOutput::isFrameReady(const Frame* frame) const
{
    if (!initialized || frame == nullptr || frame->backend != FrameBackend::VulkanImage)
        return false;

    if (frame->renderTimelineValue == 0)
        return false;

    if (useTimelineSemaphores && getSemaphoreCounterValue != nullptr && timelineSemaphore != VK_NULL_HANDLE)
    {
        u64 completedValue = 0;
        if (getSemaphoreCounterValue(device, timelineSemaphore, &completedValue) == VK_SUCCESS)
            return completedValue >= frame->renderTimelineValue;
    }

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    if (iterator->second.submitFence != VK_NULL_HANDLE)
        return vkGetFenceStatus(device, iterator->second.submitFence) == VK_SUCCESS;

    return false;
}

bool VulkanOutput::isFrameReferencedAsPendingPreviousSource(const Frame* frame) const
{
    if (!initialized || frame == nullptr)
        return false;

    if (frame == lastTopRendererSourceFrame
        || frame == lastBottomRendererSourceFrame
        || frame == lastTopComposedFrame
        || frame == lastBottomComposedFrame)
    {
        return true;
    }

    for (const auto& [resourceFrame, resource] : resources)
    {
        if (resourceFrame == frame)
            continue;

        if (resource.previousTopSourcePending && resource.previousTopSourceFrame == frame)
            return true;
        if (resource.previousBottomSourcePending && resource.previousBottomSourceFrame == frame)
            return true;
    }

    return false;
}

void VulkanOutput::consumeFrameGpuTiming(FrameResource& resource)
{
    if (!resource.timestampPending || resource.timestampQueryPool == VK_NULL_HANDLE || timestampPeriodNs <= 0.0f)
        return;

    u64 timestamps[2]{};
    const VkResult queryResult = vkGetQueryPoolResults(
        device,
        resource.timestampQueryPool,
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
        compositorGpuWindow.Add(gpuTimeNs);
    }

    resource.timestampPending = false;
}

void VulkanOutput::logPerformanceIfNeeded()
{
    if (!areRendererDebugToolsEnabled())
        return;

    if (!composeCpuWindow.Ready())
        return;

    const PerfSampleWindow<120>::Summary composeSummary = composeCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary packedSummary = packedUploadCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary waitSummary = waitCpuWindow.SummarizeAndReset();
    const PerfSampleWindow<120>::Summary gpuSummary = compositorGpuWindow.SummarizeAndReset();

    melonDS::Platform::Log(
        melonDS::Platform::LogLevel::Warn,
        "VulkanPerf[Output]: compose cpu avg=%.3fms p95=%.3fms max=%.3fms packed avg=%.3fms p95=%.3fms max=%.3fms wait avg=%.3fms p95=%.3fms max=%.3fms gpu avg=%.3fms p95=%.3fms max=%.3fms waitFail(invalid=%llu timelineZero=%llu resourceMissing=%llu finiteTimeout=%llu infinite=%llu)",
        PerfNsToMs(composeSummary.MeanNs),
        PerfNsToMs(composeSummary.P95Ns),
        PerfNsToMs(composeSummary.MaxNs),
        PerfNsToMs(packedSummary.MeanNs),
        PerfNsToMs(packedSummary.P95Ns),
        PerfNsToMs(packedSummary.MaxNs),
        PerfNsToMs(waitSummary.MeanNs),
        PerfNsToMs(waitSummary.P95Ns),
        PerfNsToMs(waitSummary.MaxNs),
        PerfNsToMs(gpuSummary.MeanNs),
        PerfNsToMs(gpuSummary.P95Ns),
        PerfNsToMs(gpuSummary.MaxNs),
        static_cast<unsigned long long>(waitFailureInvalidFrame),
        static_cast<unsigned long long>(waitFailureTimelineZero),
        static_cast<unsigned long long>(waitFailureResourceMissing),
        static_cast<unsigned long long>(waitFailureFiniteTimeout),
        static_cast<unsigned long long>(waitFailureInfinite)
    );
    waitFailureInvalidFrame = 0;
    waitFailureTimelineZero = 0;
    waitFailureResourceMissing = 0;
    waitFailureFiniteTimeout = 0;
    waitFailureInfinite = 0;
}

bool VulkanOutput::readResourceImagePixels(
    FrameResource& resource,
    const Frame* frame,
    VkImage image,
    u32 width,
    u32 height,
    u32* destinationPixels,
    size_t destinationPixelCount,
    u64 waitTimeoutNs)
{
    std::scoped_lock commandLock(commandPoolLock);

    if (!initialized || frame == nullptr || destinationPixels == nullptr || image == VK_NULL_HANDLE || width == 0 || height == 0)
        return false;

    const size_t requiredPixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (destinationPixelCount < requiredPixels || resource.stagingSize < static_cast<VkDeviceSize>(requiredPixels * sizeof(u32)))
        return false;

    if (!waitForFrame(frame, waitTimeoutNs))
        return false;

    if (!beginFrameCommand(resource, waitTimeoutNs))
        return false;

    VkImageMemoryBarrier toCopyBarrier{};
    toCopyBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toCopyBarrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    toCopyBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toCopyBarrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toCopyBarrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toCopyBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toCopyBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toCopyBarrier.image = image;
    toCopyBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toCopyBarrier.subresourceRange.baseMipLevel = 0;
    toCopyBarrier.subresourceRange.levelCount = 1;
    toCopyBarrier.subresourceRange.baseArrayLayer = 0;
    toCopyBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &toCopyBarrier
    );

    VkBufferImageCopy copyRegion{};
    copyRegion.bufferOffset = 0;
    copyRegion.bufferRowLength = 0;
    copyRegion.bufferImageHeight = 0;
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.mipLevel = 0;
    copyRegion.imageSubresource.baseArrayLayer = 0;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageOffset = {0, 0, 0};
    copyRegion.imageExtent.width = width;
    copyRegion.imageExtent.height = height;
    copyRegion.imageExtent.depth = 1;

    vkCmdCopyImageToBuffer(
        resource.commandBuffer,
        image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        resource.stagingBuffer,
        1,
        &copyRegion
    );

    VkImageMemoryBarrier toGeneralBarrier{};
    toGeneralBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toGeneralBarrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toGeneralBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    toGeneralBarrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toGeneralBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toGeneralBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneralBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneralBarrier.image = image;
    toGeneralBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    toGeneralBarrier.subresourceRange.baseMipLevel = 0;
    toGeneralBarrier.subresourceRange.levelCount = 1;
    toGeneralBarrier.subresourceRange.baseArrayLayer = 0;
    toGeneralBarrier.subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &toGeneralBarrier
    );

    VkBufferMemoryBarrier toHostBarrier{};
    toHostBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toHostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHostBarrier.buffer = resource.stagingBuffer;
    toHostBarrier.offset = 0;
    toHostBarrier.size = resource.stagingSize;

    vkCmdPipelineBarrier(
        resource.commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        0,
        nullptr,
        1,
        &toHostBarrier,
        0,
        nullptr
    );

    if (!submitFrameCommand(nullptr, resource, false))
        return false;

    if (vkWaitForFences(device, 1, &resource.submitFence, VK_TRUE, waitTimeoutNs) != VK_SUCCESS)
        return false;

    void* mappedMemory = nullptr;
    if (vkMapMemory(device, resource.stagingMemory, 0, resource.stagingSize, 0, &mappedMemory) != VK_SUCCESS)
        return false;

    std::memcpy(destinationPixels, mappedMemory, requiredPixels * sizeof(u32));
    vkUnmapMemory(device, resource.stagingMemory);
    return true;
}

bool VulkanOutput::readPreparedRenderer3dPixels(
    const Frame* frame,
    u32* destinationPixels,
    size_t destinationPixelCount,
    u32& outWidth,
    u32& outHeight,
    u64 waitTimeoutNs)
{
    outWidth = 0;
    outHeight = 0;

    if (!initialized || frame == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;
    if (!resource.hasPreparedInputs || !resource.hasRenderer3dSnapshot)
        return false;

    outWidth = resource.snapshotWidth;
    outHeight = resource.snapshotHeight;
    return readResourceImagePixels(
        resource,
        frame,
        resource.renderer3dSnapshot,
        resource.snapshotWidth,
        resource.snapshotHeight,
        destinationPixels,
        destinationPixelCount,
        waitTimeoutNs);
}

bool VulkanOutput::readFramePixels(const Frame* frame, u32* destinationPixels, size_t destinationPixelCount, u64 waitTimeoutNs)
{
    if (!initialized || frame == nullptr || destinationPixels == nullptr)
        return false;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return false;

    FrameResource& resource = iterator->second;
    return readResourceImagePixels(
        resource,
        frame,
        resource.image,
        resource.width,
        resource.height,
        destinationPixels,
        destinationPixelCount,
        waitTimeoutNs);
}

VkImage VulkanOutput::getFrameImage(const Frame* frame) const
{
    if (frame == nullptr)
        return VK_NULL_HANDLE;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return VK_NULL_HANDLE;

    return iterator->second.image;
}

VkImageView VulkanOutput::getFrameImageView(const Frame* frame) const
{
    if (frame == nullptr)
        return VK_NULL_HANDLE;

    auto iterator = resources.find(const_cast<Frame*>(frame));
    if (iterator == resources.end())
        return VK_NULL_HANDLE;

    return iterator->second.imageView;
}

}
