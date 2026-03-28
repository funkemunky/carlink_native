package com.carlink.platform

import android.media.AudioTrack

/**
 * AudioConfig - Configuration for audio playback behavior.
 *
 * PURPOSE:
 * Provides platform-specific AudioTrack settings to optimize playback on GM AAOS and
 * Intel x86 platforms. Both GM AAOS and generic Intel systems deny AUDIO_OUTPUT_FLAG_FAST
 * for third-party apps and require compensating buffer/performance mode adjustments.
 *
 * GM AAOS / INTEL ISSUES:
 * - FAST track denial: AudioFlinger denies low-latency path for non-system apps
 * - Native sample rate: 48kHz; non-matching rates trigger resampling
 * - Resampling: Further degrades FAST track eligibility and increases latency
 * - Video competition: Intel CPUs under combined video+audio load cause scheduling jitter
 *   that can exceed 50ms, draining thin ring buffers and causing audible skips
 *
 * CONFIGURATION SELECTION:
 * - DEFAULT: Standard settings for ARM platforms
 * - INTEL: Optimized for any Intel x86/x86_64 platform (PERFORMANCE_MODE_NONE,
 *          larger buffers and minBufferLevelMs headroom to absorb video-decode CPU spikes)
 * - GM_AAOS: Optimized for Intel GM AAOS devices (same tuning as INTEL)
 *
 * Reference:
 * - https://source.android.com/docs/core/audio/latency/design
 * - https://developer.android.com/reference/android/media/AudioTrack
 */
data class AudioConfig(
    /** Target sample rate (48000Hz avoids resampling on GM AAOS). */
    val sampleRate: Int,
    /** Buffer multiplier on minBufferSize (higher = more AudioTrack jitter tolerance). */
    val bufferMultiplier: Int,
    /** AudioTrack performance mode (LOW_LATENCY or NONE for Intel/GM AAOS). */
    val performanceMode: Int,
    /** Min buffer level before playback starts (prevents initial underruns). */
    val prefillThresholdMs: Int,
    /** Media ring buffer capacity (larger on Intel/GM AAOS for CPU-spike absorption). */
    val mediaBufferCapacityMs: Int,
    /** Nav ring buffer capacity (lower latency requirements than media). */
    val navBufferCapacityMs: Int,
    /**
     * Minimum fill level kept in ring buffer during playback (reserve headroom).
     * Higher values tolerate longer scheduling gaps before underflow occurs.
     * Intel platforms under concurrent video load benefit from ≥80ms.
     */
    val minBufferLevelMs: Int,
) {
    companion object {
        /** ARM platforms. FAST track available, 4x buffer, 80ms prefill (P99 jitter ~7ms). */
        val DEFAULT =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 500,
                navBufferCapacityMs = 200,
                minBufferLevelMs = 50,
            )

        /**
         * Intel x86/x86_64 platforms (non-GM AAOS).
         *
         * PERFORMANCE_MODE_NONE is used because:
         * - FAST track is typically denied for third-party apps on Intel AAOS/x86 systems
         * - When denied, AudioFlinger's fallback from LOW_LATENCY is less predictable
         *   than explicitly using PERFORMANCE_MODE_NONE with properly-sized buffers
         *
         * Larger ring buffers (1000ms/400ms) and 6x AudioTrack multiplier absorb the
         * longer scheduling gaps that occur when the CPU is shared with the H.264 decoder.
         * minBufferLevelMs=80 keeps extra headroom so a 50-80ms scheduling preemption
         * (common on Intel under video load) does not drain the buffer to zero.
         */
        val INTEL =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 6,
                performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
                prefillThresholdMs = 100,
                mediaBufferCapacityMs = 1000,
                navBufferCapacityMs = 400,
                minBufferLevelMs = 80,
            )

        /**
         * Intel GM AAOS. 48kHz native, FAST denied, larger buffers for stall absorption.
         * Same tuning rationale as INTEL — GM AAOS adds AAOS-specific audio routing on top.
         */
        val GM_AAOS =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 6,
                performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
                prefillThresholdMs = 100,
                mediaBufferCapacityMs = 1000,
                navBufferCapacityMs = 400,
                minBufferLevelMs = 80,
            )

        /**
         * Select config based on platform.
         *
         * Priority order:
         * 1. Intel GM AAOS (Intel + GM AAOS device) → GM_AAOS config
         * 2. Any Intel x86/x86_64 platform → INTEL config
         *    Covers both Intel+IntelCodec and Intel+non-Intel-codec devices.
         *    All Intel platforms share the same CPU scheduling characteristics that
         *    cause audio skipping under concurrent video load.
         * 3. ARM → DEFAULT with larger ring buffers for platform variance
         */
        fun forPlatform(
            platformInfo: PlatformDetector.PlatformInfo,
            userSampleRate: Int? = null,
        ): AudioConfig {
            val effectiveSampleRate = userSampleRate ?: platformInfo.nativeSampleRate

            return when {
                platformInfo.requiresGmAaosAudioFixes() -> {
                    GM_AAOS.copy(sampleRate = effectiveSampleRate)
                }

                platformInfo.requiresIntelAudioFixes() -> {
                    INTEL.copy(sampleRate = effectiveSampleRate)
                }

                // ARM: larger ring buffers for platform variance
                else -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                        prefillThresholdMs = 80,
                        mediaBufferCapacityMs = 1000,
                        navBufferCapacityMs = 400,
                        minBufferLevelMs = 50,
                    )
                }
            }
        }
    }
}
