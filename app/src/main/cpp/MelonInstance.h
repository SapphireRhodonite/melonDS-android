#ifndef MELONINSTANCE_H
#define MELONINSTANCE_H

#include <string>
#include <atomic>
#include <mutex>
#include "Args.h"
#include "Configuration.h"
#include "NDS.h"
#include "MelonDS.h"
#include "SaveManager.h"
#include "VulkanPerfStats.h"
#include "RewindManager.h"
#include "renderer/FrameQueue.h"
#include "renderer/Renderer.h"
#include "renderer/ScreenshotRenderer.h"
#include "renderer/VulkanOutput.h"
#include "renderer/VulkanSurfacePresenter.h"
#include "retroachievements/RetroAchievementsManager.h"
#include "net/Net.h"

using namespace melonDS;

namespace MelonDSAndroid
{

class MelonInstance
{

public:
    MelonInstance(int instanceId, std::shared_ptr<EmulatorConfiguration> configuration, std::unique_ptr<melonDS::NDSArgs> args, std::shared_ptr<Net> net, std::unique_ptr<ScreenshotRenderer> screenshotRenderer, int consoleType);
    ~MelonInstance();

    int getInstanceId() { return instanceId; };
    Renderer getCurrentRenderer() const { return currentRenderer; }

    bool loadRom(std::string romPath, std::string sramPath);
    bool loadGbaRom(std::string romPath, std::string sramPath);
    void loadRumblePak();
    void loadGbaMemoryExpansion();
    void loadGbaAnalogInput();
    void loadGbaRumblePak();
    bool bootFirmware();
    bool precompileVulkanPipelines(const VulkanSurfaceConfig& retroArchConfig);
    void start();
    void reset();
    melonDS::u32 runFrame();
    void stop();

    void touchScreen(u16 x, u16 y);
    void releaseScreen();
    void pressKey(u32 key);
    void releaseKey(u32 key);
    void setSlot2AnalogInput(float x, float y);
    int readAudioOutput(s16* buffer, int length);
    void setAudioOutputSkew(double skew);
    bool takeScreenshot();
    void loadCheats(std::list<Cheat> cheats);
    int sendNetPacket(u8* data, int length);
    int receiveNetPacket(u8* data);

    Frame* getPresentationFrame(std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline);
    bool waitForPresentationFrame(Frame* frame, u64 timeoutNs);
    int attachVulkanSurface(ANativeWindow* window, u32 width, u32 height);
    bool resizeVulkanSurface(int surfaceId, u32 width, u32 height);
    bool configureVulkanSurface(int surfaceId, const VulkanSurfaceConfig& config, const VulkanBackgroundImage& backgroundImage);
    void detachVulkanSurface(int surfaceId);
    bool presentVulkanFrame(
        std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline,
        std::optional<std::chrono::time_point<std::chrono::steady_clock>> budgetDeadline);
    void requestVulkanPresentationResync();
    void requestVulkanFastForwardPresentationTransition();
    std::vector<u32> captureCurrentFrameForDebug();
    std::vector<u32> captureCurrentPackedTopPrimaryForDebug();
    std::vector<u32> captureCurrentPackedBottomPrimaryForDebug();
    std::vector<u32> captureCurrentPackedPlaneForDebug(int screenIndex, int planeIndex);
    std::vector<u32> captureCurrentCapture3dSourceForDebug();
    std::vector<u32> captureCurrentCaptureLineUses3dMaskForDebug();
    std::vector<u32> captureCurrentComp4TopPlaceholderForDebug();
    std::vector<u32> captureCurrentComp4BottomPlaceholderForDebug();
    std::vector<u32> captureCurrentCaptureFallbackMaskForDebug();
    std::string captureCurrentSoftPackedFrameMetaJsonForDebug();
    std::vector<u32> captureCurrentCompositedDimensionsForDebug();
    std::vector<u32> captureCurrentCompositedFrameForDebug();
    std::vector<u32> captureCurrent3dDimensionsForDebug();
    std::vector<u32> captureCurrent3dFrameForDebug();
    std::vector<u32> captureCurrent3dCaptureFrameForDebug();
    std::vector<u32> captureCurrent3dDepthForDebug();
    std::vector<u32> captureCurrent3dAttrForDebug();
    std::vector<u32> captureCurrent3dCoverageForDebug();
    bool isCurrentFrameReadyForDebug() const;
    int getCurrentFrameIndexForDebug() const;
    void requestPreparedRendererDebugSnapshotForDebug();
    void clearPreparedRendererDebugSnapshotForDebug();
    void startDenseScreenBurstCaptureForDebug(int frameCount, int stepFrames, u32 captureKindsMask);
    bool isDenseScreenBurstCaptureCompleteForDebug() const;
    int getDenseScreenBurstCaptureFrameCountForDebug() const;
    int getDenseScreenBurstCaptureFrameIdForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstCaptureFrameForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstPackedTopFrameForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstPackedBottomFrameForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstPackedPlaneFrameForDebug(int index, int screenIndex, int planeIndex) const;
    std::vector<u32> getDenseScreenBurstCapture3dSourceFrameForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstCaptureLineUses3dMaskFrameForDebug(int index) const;
    std::string getDenseScreenBurstSoftPackedFrameMetaJsonForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstRenderer3dFrameForDebug(int index) const;
    std::vector<u32> getDenseScreenBurstRenderer3dCaptureFrameForDebug(int index) const;
    void clearDenseScreenBurstCaptureForDebug();
    void dumpDebugSnapshot();

    void updateConfiguration(std::shared_ptr<EmulatorConfiguration> newConfiguration);
    void requestNdsSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestGbaSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestFirmwareSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    bool areSaveStatesAllowed();
    bool saveState(Savestate* state, bool refreshScreenshot);
    bool loadState(Savestate* state);
    RewindWindow getRewindWindow();
    bool loadRewindState(RewindSaveState rewindSaveState);
    bool setupAchievements(
        std::list<RetroAchievements::RAAchievement> achievements,
        std::list<RetroAchievements::RALeaderboard> leaderboards,
        std::optional<std::string> richPresenceScript,
        std::optional<RetroAchievements::RARuntimeBridgeConfig> runtimeBridgeConfig
    );
    void unloadRetroAchievementsData();
    std::string getRichPresenceStatus();
    std::vector<RetroAchievements::RARuntimeAchievement> getRuntimeAchievements();
    std::vector<RetroAchievements::RARuntimeAchievementBucketEntry> getRuntimeAchievementBuckets();
    std::vector<long> getRuntimeSubsetIds();

private:
    struct PreparedVulkanDebugSnapshot
    {
        u64 frameId = 0;
        std::vector<u32> screenFrame;
        std::vector<u32> packedTopPrimary;
        std::vector<u32> packedBottomPrimary;
        std::vector<u32> packedTopPlane1;
        std::vector<u32> packedTopControl;
        std::vector<u32> packedBottomPlane1;
        std::vector<u32> packedBottomControl;
        std::vector<u32> capture3dSourceDsFrame;
        std::vector<u32> captureLineUses3dMask;
        std::vector<u32> comp4TopPlaceholder;
        std::vector<u32> comp4BottomPlaceholder;
        std::vector<u32> captureFallbackMask;
        std::string softPackedFrameMetaJson;
        std::vector<u32> captureFrame;
        std::vector<u32> depth;
        std::vector<u32> attr;
        std::vector<u32> coverage;
    };

    struct PreparedOpenGlDebugSnapshot
    {
        int frameId = -1;
        std::vector<u32> frame;
        std::vector<u32> captureFrame;
        std::vector<u32> depth;
        std::vector<u32> attr;
        std::vector<u32> coverage;
    };

    struct DenseScreenBurstFrame
    {
        int frameId = -1;
        std::vector<u32> screenFrame;
        std::vector<u32> packedTopPrimary;
        std::vector<u32> packedBottomPrimary;
        std::vector<u32> packedTopPlane1;
        std::vector<u32> packedTopControl;
        std::vector<u32> packedBottomPlane1;
        std::vector<u32> packedBottomControl;
        std::vector<u32> capture3dSourceDsFrame;
        std::vector<u32> captureLineUses3dMask;
        std::string softPackedFrameMetaJson;
        std::vector<u32> renderer3dFrame;
        std::vector<u32> renderer3dCaptureFrame;
    };

    struct DenseScreenBurstCapture
    {
        bool active = false;
        bool complete = false;
        int requestedFrameCount = 0;
        int captureStepFrames = 1;
        int nextCaptureFrame = 0;
        u32 captureKindsMask = 0;
        std::vector<DenseScreenBurstFrame> frames;
    };

    void updateRenderer();
    void updateVulkanFastForwardRenderScale();
    void handleVulkanRuntimeFailure(const char* reason);
    bool updateVulkanScreenshot(Frame* frame, int scale, bool clearOnFailure);
    void logVulkanPerformanceIfNeeded();
    void setBatteryLevels();
    void setDateTime();
    void saveRewindState(RewindSaveState* rewindSaveState);
    void clearLatchedSoftPackedFrameSnapshot();
    bool updateVulkanTemporal3dHistoryGate();
    bool isVulkanTemporal3dHistoryGateActive() const;
    bool latchSoftPackedFrameSnapshot(const Frame* frame, int frontBuffer, bool screenSwap, bool useStructuredVulkan2D);
    std::vector<u32> captureCurrentPackedPrimaryForDebug(bool topScreen);
    std::vector<u32> captureCurrentComp4PlaceholderForDebug(bool topScreen);
    std::vector<u32> captureLiveScreenFrameForDebug(Frame* frameOverride, int scaleOverride);
    void maybeCaptureDenseScreenBurstFrame(Frame* frameOverride, int scaleOverride, int completedFrame);
    void clearPreparedVulkanDebugSnapshot();
    void clearPreparedOpenGlDebugSnapshot();
    void prepareOpenGlDebugSnapshot(int completedFrame);
    bool ensurePreparedVulkanDebugSnapshot(Frame* frame, VulkanRenderer3D& renderer3D);
    bool hasPreparedVulkanDebugSnapshot(const Frame* frame) const;

private:
    int instanceId;
    int consoleType;
    NDS* nds;
    std::shared_ptr<Net> net;

    std::unique_ptr<RetroAchievements::RetroAchievementsManager> retroAchievementsManager;
    std::unique_ptr<SaveManager> ndsSave;
    std::unique_ptr<SaveManager> gbaSave;
    std::unique_ptr<SaveManager> firmwareSave;
    u32 inputMask;
    std::atomic<float> slot2AnalogX = 0.0f;
    std::atomic<float> slot2AnalogY = 0.0f;

    std::shared_ptr<EmulatorConfiguration> currentConfiguration;
    FrameQueue frameQueue;
    std::unique_ptr<VulkanOutput> vulkanOutput;
    std::unique_ptr<VulkanSurfacePresenter> vulkanSurfacePresenter;
    std::vector<u32> vulkanReadbackFrame;
    Frame* lastCompletedVulkanFrame;
    int lastCompletedVulkanScale;
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> lastValidTopScreenCapture3dDsFrame{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> lastValidBottomScreenCapture3dDsFrame{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> lastValidTopScreenResolvedPrimary{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> lastValidBottomScreenResolvedPrimary{};
    std::array<u8, SoftPackedFrameSnapshot::kLineCount> lastValidTopScreenResolvedPrimaryLines{};
    std::array<u8, SoftPackedFrameSnapshot::kLineCount> lastValidBottomScreenResolvedPrimaryLines{};
    bool hasLastValidTopScreenCapture3dDsFrame = false;
    bool hasLastValidBottomScreenCapture3dDsFrame = false;
    bool vulkanRegularCaptureTransitionResyncPending = false;
    int vulkanStructuredCaptureGateFrames = 0;
    int vulkanTemporal3dHistoryGateFrames = 0;
    int vulkanTemporal3dNotReadyFrames = 0;
    int vulkanTemporal3dHistoryDebugLogsRemaining = 0;
    bool lastVulkanFastForwardPresentationState = false;
    int vulkanFastForwardPreviousFrameFallbackFrames = 0;
    SoftPackedFrameSnapshot lastSoftPackedFrameSnapshot;
    SoftPackedFrameSnapshot previousSoftPackedFrameSnapshot;
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineATopPlane0{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineATopPlane1{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineATopControl{};
    std::array<u32, SoftPackedFrameSnapshot::kLineCount> cachedEngineATopLineMeta{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineABottomPlane0{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineABottomPlane1{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedEngineABottomControl{};
    std::array<u32, SoftPackedFrameSnapshot::kLineCount> cachedEngineABottomLineMeta{};
    bool cachedEngineATopValid = false;
    bool cachedEngineABottomValid = false;
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedAtypicalDisplayTopPrimary{};
    std::array<u32, SoftPackedFrameSnapshot::kPixelCount> cachedAtypicalDisplayBottomPrimary{};
    std::array<u8, SoftPackedFrameSnapshot::kLineCount> cachedAtypicalDisplayTopPrimaryLines{};
    std::array<u8, SoftPackedFrameSnapshot::kLineCount> cachedAtypicalDisplayBottomPrimaryLines{};
    int framesSinceLastScreenSwapToggle = 1024;
    bool wasInAlternatingMode = false;
    PreparedVulkanDebugSnapshot preparedVulkanDebugSnapshot;
    PreparedOpenGlDebugSnapshot preparedOpenGlDebugSnapshot;
    std::atomic_bool openGlDebugSnapshotRequested = false;
    mutable std::mutex denseScreenBurstCaptureMutex;
    DenseScreenBurstCapture denseScreenBurstCapture;
    std::unique_ptr<ScreenshotRenderer> screenshotRenderer;
    RewindManager rewindManager;
    Renderer currentRenderer;
    bool isRenderConfigurationDirty;
    bool vulkanRuntimeConfigLogged;
    bool vulkanRuntimeFailureHandled;
    int vulkanPrepareFailureCount;
    u64 vulkanSoftPackedMissingWindow = 0;
    u64 vulkanHeldPreviousFrameWindow = 0;
    u64 vulkanPrepareFailedWindow = 0;
    int frame;
    PerfSampleWindow<120> vulkanRunFrameCpuWindow;
    PerfSampleWindow<120> vulkanSetupCpuWindow;
    PerfSampleWindow<120> vulkanNdsRunCpuWindow;
    PerfSampleWindow<120> vulkanPostRunCpuWindow;
    PerfSampleWindow<120> vulkanComposeCpuWindow;
};

}

#endif
