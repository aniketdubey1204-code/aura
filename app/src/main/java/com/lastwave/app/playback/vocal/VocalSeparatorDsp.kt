package com.lastwave.app.playback.vocal

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time zero-latency stereo vocal/music separator using full-spectrum
 * mid/side decomposition with bass-protected crossover.
 *
 * Approach:
 *   1. Decode stereo into Mid (center-panned) and Side (stereo-panned) signals.
 *   2. Split Mid through a 120 Hz bass crossover (2nd-order Butterworth LPF).
 *      MidBass  = LPF(Mid)        — kick, sub-bass, bass guitar fundamentals
 *      MidUpper = Mid − MidBass   — vocals, snare, center instruments
 *   3. Reconstruct with independent gain control:
 *      OutputMid  = MidBass + vocalGain × MidUpper
 *      OutputSide = musicGain × Side
 *      L_out = OutputMid + OutputSide
 *      R_out = OutputMid − OutputSide
 *
 * Bass below 120 Hz always passes through at unity regardless of slider
 * position, so the bottom end never disappears. The complementary filter
 * pair (LPF + subtraction) sums flat at all frequencies — at unity gains
 * the output is bit-identical to the input.
 *
 * Exponential gain smoothing (~10 ms time constant) eliminates zipper noise
 * when moving sliders. Filter states persist across buffer boundaries and
 * are only reset on track changes or seeks.
 */
class VocalSeparatorDsp {

    // ── 2nd-order Butterworth LPF coefficients (bass crossover at 120 Hz) ──
    private var lpB0 = 0f
    private var lpB1 = 0f
    private var lpB2 = 0f
    private var lpA1 = 0f
    private var lpA2 = 0f

    // Filter delay-line states for the bass crossover on the Mid channel.
    // Only the Mid needs filtering; Side passes through unfiltered.
    private var lpX1 = 0f
    private var lpX2 = 0f
    private var lpY1 = 0f
    private var lpY2 = 0f

    // Exponential gain smoothing to prevent zipper noise on slider moves
    private var smoothedVocalGain = 1f
    private var smoothedMusicGain = 1f
    private var smoothingCoeff = 0.001f  // recalculated in configure()

    private var configuredSampleRate = 0

    /**
     * Compute LPF coefficients for the bass-protection crossover and the
     * gain-smoothing time constant. Called automatically when the sample
     * rate changes; safe to call repeatedly for the same rate.
     */
    fun configure(sampleRateHz: Int) {
        val sr = sampleRateHz.coerceIn(8000, 384000)
        if (sr == configuredSampleRate) return
        configuredSampleRate = sr

        val fs = sr.toDouble()
        val q = 1.0 / sqrt(2.0) // Butterworth Q ≈ 0.7071

        // 2nd-order Butterworth low-pass at 120 Hz
        val fc = 120.0.coerceAtMost(fs * 0.45)
        val w0 = 2.0 * PI * fc / fs
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha

        lpB0 = ((1.0 - cosW) / (2.0 * a0)).toFloat()
        lpB1 = ((1.0 - cosW) / a0).toFloat()
        lpB2 = ((1.0 - cosW) / (2.0 * a0)).toFloat()
        lpA1 = ((-2.0 * cosW) / a0).toFloat()
        lpA2 = ((1.0 - alpha) / a0).toFloat()

        // Gain smoothing: ~10 ms exponential time constant
        smoothingCoeff = (1.0 - exp(-1.0 / (0.010 * fs))).toFloat()

        resetStates()
    }

    fun reset() {
        resetStates()
    }

    private fun resetStates() {
        lpX1 = 0f; lpX2 = 0f
        lpY1 = 0f; lpY2 = 0f
        smoothedVocalGain = 1f
        smoothedMusicGain = 1f
    }

    /**
     * Run the bass-protection LPF on one sample of the Mid channel.
     * Returns the low-frequency component (below ~120 Hz).
     */
    private fun lowpassMid(input: Float): Float {
        val y = lpB0 * input + lpB1 * lpX1 + lpB2 * lpX2 -
                lpA1 * lpY1 - lpA2 * lpY2
        lpX2 = lpX1; lpX1 = input
        lpY2 = lpY1; lpY1 = y
        return y
    }

    /**
     * Process one stereo sample pair and return the result packed as a Long:
     * upper 32 bits = outL (Float.toBits), lower 32 bits = outR (Float.toBits).
     */
    private fun processStereoSample(
        l: Float,
        r: Float,
        targetVocalGain: Float,
        targetMusicGain: Float,
    ): Long {
        // Exponential gain smoothing (prevents clicks/zipper noise on slider moves)
        smoothedVocalGain += smoothingCoeff * (targetVocalGain - smoothedVocalGain)
        smoothedMusicGain += smoothingCoeff * (targetMusicGain - smoothedMusicGain)

        // Mid/Side stereo decomposition
        val mid = 0.5f * (l + r)    // center-panned content: vocals, bass, kick, snare
        val side = 0.5f * (l - r)   // stereo-panned content: guitars, keys, pads, effects

        // Bass crossover on mid: protect sub-bass and kick fundamentals
        val midBass = lowpassMid(mid)       // below ~120 Hz (always passes through)
        val midUpper = mid - midBass        // above ~120 Hz (vocal-heavy, gain-controlled)

        // Apply independent gains
        // Bass always at unity. Upper center controlled by vocal slider.
        // Side signal controlled by music slider.
        val outMid = midBass + smoothedVocalGain * midUpper
        val outSide = smoothedMusicGain * side

        // Reconstruct stereo
        val outL = (outMid + outSide).coerceIn(-1.0f, 1.0f)
        val outR = (outMid - outSide).coerceIn(-1.0f, 1.0f)

        return (outL.toBits().toLong() shl 32) or (outR.toBits().toLong() and 0xFFFFFFFFL)
    }

    /**
     * Processes a stereo PCM ByteBuffer in place. Requires at least 2 channels.
     * Filter states persist across calls — do NOT call [reset] between
     * consecutive buffers of the same track.
     */
    fun processPcm(
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
        vocalGain: Float,
        musicGain: Float,
    ) {
        if (channels < 2) return
        if (!buffer.hasRemaining()) return

        // Bypass: both gains at unity means output equals input
        if (kotlin.math.abs(vocalGain - 1.0f) < 0.005f &&
            kotlin.math.abs(musicGain - 1.0f) < 0.005f
        ) {
            return
        }

        if (configuredSampleRate != sampleRate) {
            configure(sampleRate)
        }

        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val vG = vocalGain.coerceIn(0f, 1.5f)
        val mG = musicGain.coerceIn(0f, 1.5f)

        try {
            when (pcmEncoding) {
                C.ENCODING_PCM_FLOAT -> processFloatInPlace(buffer, vG, mG)
                C.ENCODING_PCM_16BIT -> processPcm16InPlace(buffer, vG, mG)
                C.ENCODING_PCM_24BIT -> processPcm24InPlace(buffer, vG, mG)
                C.ENCODING_PCM_32BIT -> processPcm32InPlace(buffer, vG, mG)
            }
        } finally {
            buffer.order(originalOrder)
        }
    }

    // ── Format-specific in-place processors ──

    private fun processFloatInPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 7 < limit) {
            val l = buffer.getFloat(pos)
            val r = buffer.getFloat(pos + 4)
            val packed = processStereoSample(l, r, vGain, mGain)
            buffer.putFloat(pos, Float.fromBits((packed ushr 32).toInt()))
            buffer.putFloat(pos + 4, Float.fromBits(packed.toInt()))
            pos += 8
        }
    }

    private fun processPcm16InPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 3 < limit) {
            val l = buffer.getShort(pos).toFloat() / 32768.0f
            val r = buffer.getShort(pos + 2).toFloat() / 32768.0f
            val packed = processStereoSample(l, r, vGain, mGain)
            val outL = Float.fromBits((packed ushr 32).toInt())
            val outR = Float.fromBits(packed.toInt())
            buffer.putShort(pos, (outL * 32767.0f).toInt().coerceIn(-32767, 32767).toShort())
            buffer.putShort(pos + 2, (outR * 32767.0f).toInt().coerceIn(-32767, 32767).toShort())
            pos += 4
        }
    }

    private fun processPcm24InPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 5 < limit) {
            val b1L = buffer.get(pos).toInt() and 0xFF
            val b2L = buffer.get(pos + 1).toInt() and 0xFF
            val b3L = buffer.get(pos + 2).toInt()
            val l = (b1L or (b2L shl 8) or (b3L shl 16)).toFloat() / 8388608.0f

            val b1R = buffer.get(pos + 3).toInt() and 0xFF
            val b2R = buffer.get(pos + 4).toInt() and 0xFF
            val b3R = buffer.get(pos + 5).toInt()
            val r = (b1R or (b2R shl 8) or (b3R shl 16)).toFloat() / 8388608.0f

            val packed = processStereoSample(l, r, vGain, mGain)
            val outL = Float.fromBits((packed ushr 32).toInt())
            val outR = Float.fromBits(packed.toInt())

            val sL = (outL * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
            buffer.put(pos, sL.toByte())
            buffer.put(pos + 1, (sL shr 8).toByte())
            buffer.put(pos + 2, (sL shr 16).toByte())

            val sR = (outR * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
            buffer.put(pos + 3, sR.toByte())
            buffer.put(pos + 4, (sR shr 8).toByte())
            buffer.put(pos + 5, (sR shr 16).toByte())

            pos += 6
        }
    }

    private fun processPcm32InPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 7 < limit) {
            val l = buffer.getInt(pos).toFloat() / 2147483648.0f
            val r = buffer.getInt(pos + 4).toFloat() / 2147483648.0f
            val packed = processStereoSample(l, r, vGain, mGain)
            val outL = Float.fromBits((packed ushr 32).toInt())
            val outR = Float.fromBits(packed.toInt())
            buffer.putInt(pos, (outL.toDouble() * 2147483647.0).toLong().toInt())
            buffer.putInt(pos + 4, (outR.toDouble() * 2147483647.0).toLong().toInt())
            pos += 8
        }
    }
}
