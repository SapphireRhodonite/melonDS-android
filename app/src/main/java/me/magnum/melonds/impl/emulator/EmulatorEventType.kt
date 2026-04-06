package me.magnum.melonds.impl.emulator

/**
 * Event types that can be emitted by the emulator. These constants must match the values defined in AndroidMelonEventMessenger.h
 */
enum class EmulatorEventType(val event: Int) {
    /**
     * Rumble start event. Data:
     * * rumble duration in ms (`i32`)
     */
    EventRumbleStart(100),
    /**
     * Rumble stop event. No data.
     */
    EventRumbleStop(101),
    /**
     * Emulator stop event. No data.
     */
    EventEmulatorStop(102),
    /**
     * Renderer init failed. Data:
     * * renderer value (`i32`)
     */
    EventRendererInitFailed(103),
    /**
     * Vulkan compile progress. Data:
     * * stage ID (`i32`)
     * * current step (`i32`)
     * * total steps (`i32`)
     */
    EventVulkanCompileProgress(104),

    /**
     * RA achievement primed. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementPrimed(200),

    /**
     * RA achievement triggered. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementTriggered(201),

    /**
     * RA achievement unprimed. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementUnprimed(202),

    /**
     * RA achievement progress updated. Data:
     * * achievement ID (`i64`)
     * * current progress value (`i32`)
     * * target progress value (`i32`)
     * * formated progress string size (`i32`)
     * * formated progress string (`u8[32]`)
     */
    EventRAAchievementProgressUpdated(203),

    /**
     * RA core game completed/mastered. Data:
     * * subset ID (`i64`)
     */
    EventRAGameCompleted(204),

    /**
     * RA non-core subset completed/mastered. Data:
     * * subset ID (`i64`)
     */
    EventRASubsetCompleted(205),

    /**
     * RA server error. Data:
     * * related ID (`i64`)
     * * result code (`i32`)
     * * API string size (`i32`)
     * * API string (`u8[32]`)
     * * message string size (`i32`)
     * * message string (`u8[64]`)
     */
    EventRAServerError(206),

    /**
     * RA runtime disconnected. No data.
     */
    EventRADisconnected(207),

    /**
     * RA runtime reconnected. No data.
     */
    EventRAReconnected(208),

    /**
     * RA runtime fallback to legacy. Data:
     * * fallback reason (`i32`)
     */
    EventRARuntimeFallback(209),

    /**
     * RA leaderboard attempt started. Data:
     * * leaderboard ID (`i64`)
     */
    EventRALeaderboardAttemptStarted(210),

    /**
     * RA leaderboard attempt updated. Data:
     * * leaderboard ID (`i64`)
     * * formated value string size (`i32`)
     * * formated value string (`u8[32]`)
     */
    EventRALeaderboardAttemptUpdated(211),

    /**
     * RA leaderboard attempt canceled. Data:
     * * leaderboard ID (`i64`)
     */
    EventRALeaderboardAttemptCanceled(212),

    /**
     * RA leaderboard attempt completed. Data:
     * * leaderboard ID (`i64`)
     * * leaderboard value (`i32`)
     * * formated value string size (`i32`)
     * * formated value string (`u8[32]`)
     */
    EventRALeaderboardAttemptCompleted(213),
}
