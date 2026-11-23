package com.fearmikey.camera.domain.model

import kotlin.math.abs

/** One constrained high-speed configuration a camera offers: a stream size at a fixed frame rate. */
data class HighSpeedConfig(val width: Int, val height: Int, val fps: Int)

/**
 * Slow-motion choices (pure Kotlin, unit-tested in SlowMotionTest). Clips are encoded at
 * [PLAYBACK_FPS], so a capture rate of 120 fps plays back at 1/4 speed and 240 fps at 1/8, in
 * any player.
 */
object SlowMotion {
    const val PLAYBACK_FPS = 30
    const val DEFAULT_FPS = 120
    private const val MIN_FPS = 120

    /** Larger high-speed sizes are skipped: encoders and players struggle above 1080p here. */
    private const val MAX_PIXELS = 1920 * 1080

    /** Bit rate of a 1080p clip at [PLAYBACK_FPS]; smaller sizes scale down with their pixel count. */
    private const val BIT_RATE_1080P = 20_000_000
    private const val MIN_BIT_RATE = 8_000_000

    private fun usable(c: HighSpeedConfig) = c.fps >= MIN_FPS && c.width * c.height <= MAX_PIXELS

    /** Capture rates that can be offered, ascending (e.g. [120, 240]). */
    fun rates(configs: List<HighSpeedConfig>): List<Int> =
        configs.filter(::usable).map { it.fps }.distinct().sorted()

    /** The rate to record at for the stored preference: [wanted] if offered, else the closest one. */
    fun resolveFps(wanted: Int, rates: List<Int>): Int? = rates.minByOrNull { abs(it - wanted) }

    /** Stream for [fps]: 16:9 preferred, then the most pixels. */
    fun pick(configs: List<HighSpeedConfig>, fps: Int): HighSpeedConfig? =
        configs.filter { usable(it) && it.fps == fps }
            .maxWithOrNull(compareBy<HighSpeedConfig> { it.width * 9 == it.height * 16 }.thenBy { it.width * it.height })

    fun bitRate(config: HighSpeedConfig): Int =
        (BIT_RATE_1080P.toLong() * config.width * config.height / MAX_PIXELS).toInt().coerceIn(MIN_BIT_RATE, BIT_RATE_1080P)

    /** Playback speed, e.g. "1/4x" for 120 fps. */
    fun speedLabel(fps: Int): String = "1/${fps / PLAYBACK_FPS}x"
}
