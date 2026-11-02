package com.lastwave.app.playback.vocal

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-time, zero-latency in-place PCM DSP for vocal and instrumental music separation.
 *
 * Technique: 4th-order Linkwitz-Riley (cascaded dual-biquad) mid/side decomposition.
 *
 * 1. Mid/Side stereo split: M = 0.5 * (L + R), S = 0.5 * (L - R)
 * 2. Vocal band isolation on M using cascaded 4th-order Linkwitz-Riley bandpass
 *    (200 Hz HPF → 4500 Hz LPF, each stage is two cascaded Butterworth biquads
 *    giving −24 dB/oct slopes)
 * 3. Complementary extraction: M_music = M − M_vocal_band
 * 4. Independent gain mixing with cross-feed compensation:
 *      L_out = vocalGain * (M_vocal + S_vocal) + musicGain * (M_music + S_music)
 *      R_out = vocalGain * (M_vocal − S_vocal) + musicGain * (M_music − S_music)
 *    where S_vocal is the side signal filtered through the same vocal bandpass,
 *    and S_music = S − S_vocal.
 * 5. Gain-dependent smoothing to eliminate zipper noise on slider moves.
 *
 * Filter states are persistent across buffer boundaries — never reset mid-stream.
 * Calling [reset] is only appropriate on track changes or seek discontinuities.
 */
class VocalSeparatorDsp {

    // ── Two cascaded biquad stages per filter (4th-order Linkwitz-Riley) ──

    // High-Pass 200 Hz – Stage 1 (Mid channel)
    private var hp1MidB0 = 1f; private var hp1MidB1 = -2f; private var hp1MidB2 = 1f
    private var hp1MidA1 = 0f; private var hp1MidA2 = 0f
    private var hp1MidX1 = 0f; private var hp1MidX2 = 0f
    private var hp1MidY1 = 0f; private var hp1MidY2 = 0f

    // High-Pass 200 Hz – Stage 2 (Mid channel)
    private var hp2MidX1 = 0f; private var hp2MidX2 = 0f
    private var hp2MidY1 = 0f; private var hp2MidY2 = 0f

    // Low-Pass 4500 Hz – Stage 1 (Mid channel)
    private var lp1MidB0 = 1f; private var lp1MidB1 = 2f; private var lp1MidB2 = 1f
    private var lp1MidA1 = 0f; private var lp1MidA2 = 0f
    private var lp1MidX1 = 0f; private var lp1MidX2 = 0f
    private var lp1MidY1 = 0f; private var lp1MidY2 = 0f

    // Low-Pass 4500 Hz – Stage 2 (Mid channel)
    private var lp2MidX1 = 0f; private var lp2MidX2 = 0f
    private var lp2MidY1 = 0f; private var lp2MidY2 = 0f

    // High-Pass 200 Hz – Stage 1 (Side channel)
    private var hp1SideX1 = 0f; private var hp1SideX2 = 0f
    private var hp1SideY1 = 0f; private var hp1SideY2 = 0f

    // High-Pass 200 Hz – Stage 2 (Side channel)
    private var hp2SideX1 = 0f; private var hp2SideX2 = 0f
    private var hp2SideY1 = 0f; private var hp2SideY2 = 0f

    // Low-Pass 4500 Hz – Stage 1 (Side channel)
    private var lp1SideX1 = 0f; private var lp1SideX2 = 0f
    private var lp1SideY1 = 0f; private var lp1SideY2 = 0f

    // Low-Pass 4500 Hz – Stage 2 (Side channel)
    private var lp2SideX1 = 0f; private var lp2SideX2 = 0f
    private var lp2SideY1 = 0f; private var lp2SideY2 = 0f

    // Gain smoothing (exponential, ~5 ms time constant)
    private var smoothedVocalGain = 1f
    private var smoothedMusicGain = 1f
    private var smoothingCoeff = 0.002f // updated in configure()

    private var configuredSampleRate = 0

    fun configure(sampleRateHz: Int) {
        val sr = sampleRateHz.coerceIn(8000, 384000)
        if (sr == configuredSampleRate) return
        configuredSampleRate = sr

        val fs = sr.toDouble()
        val q = 1.0 / sqrt(2.0) // Butterworth Q = 0.7071

        // ── High-Pass at 200 Hz ──
        val hpFc = 200.0.coerceAtMost(fs * 0.45)
        val w0Hp = 2.0 * PI * hpFc / fs
        val cosHp = cos(w0Hp)
        val alphaHp = sin(w0Hp) / (2.0 * q)
        val a0Hp = 1.0 + alphaHp

        hp1MidB0 = ((1.0 + cosHp) / (2.0 * a0Hp)).toFloat()
        hp1MidB1 = (-(1.0 + cosHp) / a0Hp).toFloat()
        hp1MidB2 = ((1.0 + cosHp) / (2.0 * a0Hp)).toFloat()
        hp1MidA1 = ((-2.0 * cosHp) / a0Hp).toFloat()
        hp1MidA2 = ((1.0 - alphaHp) / a0Hp).toFloat()

        // ── Low-Pass at 4500 Hz ──
        val lpFc = 4500.0.coerceAtMost(fs * 0.45)
        val w0Lp = 2.0 * PI * lpFc / fs
        val cosLp = cos(w0Lp)
        val alphaLp = sin(w0Lp) / (2.0 * q)
        val a0Lp = 1.0 + alphaLp

        lp1MidB0 = ((1.0 - cosLp) / (2.0 * a0Lp)).toFloat()
        lp1MidB1 = ((1.0 - cosLp) / a0Lp).toFloat()
        lp1MidB2 = ((1.0 - cosLp) / (2.0 * a0Lp)).toFloat()
        lp1MidA1 = ((-2.0 * cosLp) / a0Lp).toFloat()
        lp1MidA2 = ((1.0 - alphaLp) / a0Lp).toFloat()

        // Gain smoothing: ~5 ms time constant
        smoothingCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.005 * fs))).toFloat()

        resetStates()
    }

    fun reset() {
        resetStates()
    }

    private fun resetStates() {
        hp1MidX1 = 0f; hp1MidX2 = 0f; hp1MidY1 = 0f; hp1MidY2 = 0f
        hp2MidX1 = 0f; hp2MidX2 = 0f; hp2MidY1 = 0f; hp2MidY2 = 0f
        lp1MidX1 = 0f; lp1MidX2 = 0f; lp1MidY1 = 0f; lp1MidY2 = 0f
        lp2MidX1 = 0f; lp2MidX2 = 0f; lp2MidY1 = 0f; lp2MidY2 = 0f

        hp1SideX1 = 0f; hp1SideX2 = 0f; hp1SideY1 = 0f; hp1SideY2 = 0f
        hp2SideX1 = 0f; hp2SideX2 = 0f; hp2SideY1 = 0f; hp2SideY2 = 0f
        lp1SideX1 = 0f; lp1SideX2 = 0f; lp1SideY1 = 0f; lp1SideY2 = 0f
        lp2SideX1 = 0f; lp2SideX2 = 0f; lp2SideY1 = 0f; lp2SideY2 = 0f

        smoothedVocalGain = 1f
        smoothedMusicGain = 1f
    }

    // ── Cascaded biquad: two identical second-order sections ──

    /**
     * Filters the mid-channel sample through the cascaded HP→LP bandpass (4th order total).
     * Returns the vocal-band component of the mid signal.
     */
    private fun filterMidVocalBand(input: Float): Float {
        // HP stage 1
        val hp1Out = hp1MidB0 * input + hp1MidB1 * hp1MidX1 + hp1MidB2 * hp1MidX2 -
                hp1MidA1 * hp1MidY1 - hp1MidA2 * hp1MidY2
        hp1MidX2 = hp1MidX1; hp1MidX1 = input
        hp1MidY2 = hp1MidY1; hp1MidY1 = hp1Out

        // HP stage 2 (same coefficients, independent state)
        val hp2Out = hp1MidB0 * hp1Out + hp1MidB1 * hp2MidX1 + hp1MidB2 * hp2MidX2 -
                hp1MidA1 * hp2MidY1 - hp1MidA2 * hp2MidY2
        hp2MidX2 = hp2MidX1; hp2MidX1 = hp1Out
        hp2MidY2 = hp2MidY1; hp2MidY1 = hp2Out

        // LP stage 1
        val lp1Out = lp1MidB0 * hp2Out + lp1MidB1 * lp1MidX1 + lp1MidB2 * lp1MidX2 -
                lp1MidA1 * lp1MidY1 - lp1MidA2 * lp1MidY2
        lp1MidX2 = lp1MidX1; lp1MidX1 = hp2Out
        lp1MidY2 = lp1MidY1; lp1MidY1 = lp1Out

        // LP stage 2 (same coefficients, independent state)
        val lp2Out = lp1MidB0 * lp1Out + lp1MidB1 * lp2MidX1 + lp1MidB2 * lp2MidX2 -
                lp1MidA1 * lp2MidY1 - lp1MidA2 * lp2MidY2
        lp2MidX2 = lp2MidX1; lp2MidX1 = lp1Out
        lp2MidY2 = lp2MidY1; lp2MidY1 = lp2Out

        return lp2Out
    }

    /**
     * Filters the side-channel sample through the cascaded HP→LP bandpass (4th order total).
     * Returns the vocal-band component of the side signal.
     */
    private fun filterSideVocalBand(input: Float): Float {
        // HP stage 1
        val hp1Out = hp1MidB0 * input + hp1MidB1 * hp1SideX1 + hp1MidB2 * hp1SideX2 -
                hp1MidA1 * hp1SideY1 - hp1MidA2 * hp1SideY2
        hp1SideX2 = hp1SideX1; hp1SideX1 = input
        hp1SideY2 = hp1SideY1; hp1SideY1 = hp1Out

        // HP stage 2
        val hp2Out = hp1MidB0 * hp1Out + hp1MidB1 * hp2SideX1 + hp1MidB2 * hp2SideX2 -
                hp1MidA1 * hp2SideY1 - hp1MidA2 * hp2SideY2
        hp2SideX2 = hp2SideX1; hp2SideX1 = hp1Out
        hp2SideY2 = hp2SideY1; hp2SideY1 = hp2Out

        // LP stage 1
        val lp1Out = lp1MidB0 * hp2Out + lp1MidB1 * lp1SideX1 + lp1MidB2 * lp1SideX2 -
                lp1MidA1 * lp1SideY1 - lp1MidA2 * lp1SideY2
        lp1SideX2 = lp1SideX1; lp1SideX1 = hp2Out
        lp1SideY2 = lp1SideY1; lp1SideY1 = lp1Out

        // LP stage 2
        val lp2Out = lp1MidB0 * lp1Out + lp1MidB1 * lp2SideX1 + lp1MidB2 * lp2SideX2 -
                lp1MidA1 * lp2SideY1 - lp1MidA2 * lp2SideY2
        lp2SideX2 = lp2SideX1; lp2SideX1 = lp1Out
        lp2SideY2 = lp2SideY1; lp2SideY1 = lp2Out

        return lp2Out
    }

    /**
     * Processes a stereo PCM ByteBuffer in place.
     *
     * Gain smoothing prevents zipper noise when sliders move. Filter states
     * persist across calls — do NOT call [reset] between consecutive buffers
     * of the same track.
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

        // Bypass: both gains at unity means the output equals the input.
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

        val vGain = vocalGain.coerceIn(0f, 1.5f)
        val mGain = musicGain.coerceIn(0f, 1.5f)

        try {
            when (pcmEncoding) {
                C.ENCODING_PCM_FLOAT -> processFloatInPlace(buffer, vGain, mGain)
                C.ENCODING_PCM_16BIT -> processPcm16InPlace(buffer, vGain, mGain)
                C.ENCODING_PCM_24BIT -> processPcm24InPlace(buffer, vGain, mGain)
                C.ENCODING_PCM_32BIT -> processPcm32InPlace(buffer, vGain, mGain)
            }
        } finally {
            buffer.order(originalOrder)
        }
    }

    /**
     * Core sample-pair processing. Applies mid/side decomposition, 4th-order
     * bandpass isolation on both mid and side, complementary subtraction, and
     * smoothed gain mixing. Returns the output (L, R) pair packed as a Long
     * using Float.toBits() in the upper and lower 32 bits.
     */
    private fun processStereoSample(l: Float, r: Float, targetVGain: Float, targetMGain: Float): Long {
        // Exponential gain smoothing (prevents zipper noise on slider changes)
        smoothedVocalGain += smoothingCoeff * (targetVGain - smoothedVocalGain)
        smoothedMusicGain += smoothingCoeff * (targetMGain - smoothedMusicGain)
        val vG = smoothedVocalGain
        val mG = smoothedMusicGain

        // Mid/Side decomposition
        val mid = 0.5f * (l + r)
        val side = 0.5f * (l - r)

        // 4th-order bandpass on mid (vocal center extraction)
        val midVocal = filterMidVocalBand(mid)
        val midMusic = mid - midVocal

        // 4th-order bandpass on side (vocal off-center / reverb tails)
        val sideVocal = filterSideVocalBand(side)
        val sideMusic = side - sideVocal

        // Reconstruct stereo with independent gains
        val outL = (vG * (midVocal + sideVocal) + mG * (midMusic + sideMusic))
            .coerceIn(-1.0f, 1.0f)
        val outR = (vG * (midVocal - sideVocal) + mG * (midMusic - sideMusic))
            .coerceIn(-1.0f, 1.0f)

        // Pack two floats into a Long
        return (outL.toBits().toLong() shl 32) or (outR.toBits().toLong() and 0xFFFFFFFFL)
    }

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
            val sampleL = (b1L or (b2L shl 8) or (b3L shl 16))
            val l = sampleL.toFloat() / 8388608.0f

            val b1R = buffer.get(pos + 3).toInt() and 0xFF
            val b2R = buffer.get(pos + 4).toInt() and 0xFF
            val b3R = buffer.get(pos + 5).toInt()
            val sampleR = (b1R or (b2R shl 8) or (b3R shl 16))
            val r = sampleR.toFloat() / 8388608.0f

            val packed = processStereoSample(l, r, vGain, mGain)
            val outL = Float.fromBits((packed ushr 32).toInt())
            val outR = Float.fromBits(packed.toInt())

            val scaledL = (outL * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
            buffer.put(pos, scaledL.toByte())
            buffer.put(pos + 1, (scaledL shr 8).toByte())
            buffer.put(pos + 2, (scaledL shr 16).toByte())

            val scaledR = (outR * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
            buffer.put(pos + 3, scaledR.toByte())
            buffer.put(pos + 4, (scaledR shr 8).toByte())
            buffer.put(pos + 5, (scaledR shr 16).toByte())

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
