#include "AndroidMelonEventMessenger.h"
#include "EmulatorMessageQueueJNI.h"
#include <algorithm>
#include <cstring>

void AndroidMelonEventMessenger::onRumbleStart(int durationMs)
{
    MelonDSAndroid::fireEmulatorEvent(EVENT_RUMBLE_START, sizeof(durationMs), &durationMs);
}

void AndroidMelonEventMessenger::onRumbleStop()
{
    MelonDSAndroid::fireEmulatorEvent(EVENT_RUMBLE_STOP);
}

void AndroidMelonEventMessenger::onEmulatorStop(melonDS::Platform::StopReason reason)
{
    int32_t reasonInt = (int32_t) reason;
    MelonDSAndroid::fireEmulatorEvent(EVENT_EMULATOR_STOP, sizeof(reasonInt), &reasonInt);
}

void AndroidMelonEventMessenger::onRendererInitFailed(MelonDSAndroid::Renderer renderer)
{
    int32_t rendererInt = static_cast<int32_t>(renderer);
    MelonDSAndroid::fireEmulatorEvent(EVENT_RENDERER_INIT_FAILED, sizeof(rendererInt), &rendererInt);
}

void AndroidMelonEventMessenger::onVulkanCompileProgress(int stageId, int current, int total)
{
    struct
    {
        int32_t stageId;
        int32_t current;
        int32_t total;
    } data{
        .stageId = static_cast<int32_t>(stageId),
        .current = static_cast<int32_t>(current),
        .total = static_cast<int32_t>(total),
    };
    MelonDSAndroid::fireEmulatorEvent(EVENT_VULKAN_COMPILE_PROGRESS, sizeof(data), &data);
}

void AndroidMelonEventMessenger::onAchievementPrimed(long achievementId)
{
    int64_t achievementIdLong = (int64_t) achievementId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_ACHIEVEMENT_PRIMED, sizeof(achievementIdLong), &achievementIdLong);
}

void AndroidMelonEventMessenger::onAchievementTriggered(long achievementId)
{
    int64_t achievementIdLong = (int64_t) achievementId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_ACHIEVEMENT_TRIGGERED, sizeof(achievementIdLong), &achievementIdLong);
}

void AndroidMelonEventMessenger::onAchievementUnprimed(long achievementId)
{
    int64_t achievementIdLong = (int64_t) achievementId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_ACHIEVEMENT_UNPRIMED, sizeof(achievementIdLong), &achievementIdLong);
}

void AndroidMelonEventMessenger::onAchievementProgressUpdated(long achievementId, unsigned int current, unsigned int target, std::string progress)
{
    struct {
        int64_t achievementId;
        int32_t current;
        int32_t target;
        int32_t progressSize;
        char progress[32];
    } data = {
        .achievementId = (int64_t) achievementId,
        .current = (int32_t) current,
        .target = (int32_t) target,
        .progressSize = (int32_t) std::min(progress.size(), sizeof(data.progress)),
    };
    std::memset(data.progress, 0, sizeof(data.progress));
    std::memcpy(data.progress, progress.c_str(), (size_t) data.progressSize);

    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_ACHIEVEMENT_PROGRESS_UPDATED, sizeof(data), &data);
}

void AndroidMelonEventMessenger::onLeaderboardAttemptStarted(long leaderboardId)
{
    int64_t leaderboardIdLong = (int64_t) leaderboardId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_LBOARD_ATTEMPT_STARTED, sizeof(leaderboardIdLong), &leaderboardIdLong);
}

void AndroidMelonEventMessenger::onLeaderboardAttemptUpdated(long leaderboardId, std::string formattedValue)
{
    struct {
        int64_t leaderboardId;
        int32_t formattedValueSize;
        char formattedValue[32];
    } data = {
        .leaderboardId = (int64_t) leaderboardId,
        .formattedValueSize = (int32_t) std::min(formattedValue.size(), sizeof(data.formattedValue)),
    };
    std::memset(data.formattedValue, 0, sizeof(data.formattedValue));
    std::memcpy(data.formattedValue, formattedValue.c_str(), (size_t) data.formattedValueSize);

    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_LBOARD_ATTEMPT_UPDATED, sizeof(data), &data);
}

void AndroidMelonEventMessenger::onLeaderboardAttemptCanceled(long leaderboardId)
{
    int64_t leaderboardIdLong = (int64_t) leaderboardId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_LBOARD_ATTEMPT_CANCELED, sizeof(leaderboardIdLong), &leaderboardIdLong);
}

void AndroidMelonEventMessenger::onLeaderboardAttemptCompleted(long leaderboardId, int value)
{
    struct {
        int64_t leaderboardId;
        int32_t value;
    } data = {
        .leaderboardId = (int64_t) leaderboardId,
        .value = (int32_t) value,
    };

    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_LBOARD_ATTEMPT_COMPLETED, sizeof(data), &data);
}

void AndroidMelonEventMessenger::onAchievementGameCompleted(long subsetId)
{
    int64_t subsetIdLong = (int64_t) subsetId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_GAME_COMPLETED, sizeof(subsetIdLong), &subsetIdLong);
}

void AndroidMelonEventMessenger::onAchievementSubsetCompleted(long subsetId)
{
    int64_t subsetIdLong = (int64_t) subsetId;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_SUBSET_COMPLETED, sizeof(subsetIdLong), &subsetIdLong);
}

void AndroidMelonEventMessenger::onRetroAchievementsServerError(std::string api, long relatedId, int result, std::string message)
{
    struct {
        int64_t relatedId;
        int32_t result;
        int32_t apiSize;
        char api[32];
        int32_t messageSize;
        char message[64];
    } data = {
        .relatedId = (int64_t) relatedId,
        .result = (int32_t) result,
        .apiSize = (int32_t) std::min(api.size(), sizeof(data.api)),
        .messageSize = (int32_t) std::min(message.size(), sizeof(data.message)),
    };
    std::memset(data.api, 0, sizeof(data.api));
    std::memcpy(data.api, api.c_str(), (size_t) data.apiSize);
    std::memset(data.message, 0, sizeof(data.message));
    std::memcpy(data.message, message.c_str(), (size_t) data.messageSize);

    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_SERVER_ERROR, sizeof(data), &data);
}

void AndroidMelonEventMessenger::onRetroAchievementsDisconnected()
{
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_DISCONNECTED);
}

void AndroidMelonEventMessenger::onRetroAchievementsReconnected()
{
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_RECONNECTED);
}

void AndroidMelonEventMessenger::onRetroAchievementsRuntimeFallback(MelonDSAndroid::RetroAchievementsRuntimeFallbackReason reason)
{
    int32_t reasonInt = (int32_t) reason;
    MelonDSAndroid::fireEmulatorEvent(EVENT_RA_RUNTIME_FALLBACK, sizeof(reasonInt), &reasonInt);
}
