package com.lastwave.app.playback.vocal

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time, zero-latency in-place PCM DSP for vocal and instrumental music separation.
 *
 * Uses phantom center isolation combined with dual-stage Linkwitz-Riley biquad filters:
 * 1. Mid/Side stereo decomposition: M = 0.5 * (L + R), S = 0.5 * (L - R)
 * 2. Vocal bandpass isolation on M (200 Hz HPF -> 4500 Hz LPF)
 * 3. Spectral subtraction: M_music = M - M_vocal
 * 4. Spatial reconstruction: L_music = M_music + S, R_music = M_music - S
 * 5. Dynamic dual-line gain:
 *      L_out = musicGain * L_music + vocalGain * L_vocal
 *      R_out = musicGain * R_music + vocalGain * R_vocal
 *
 * Runs entirely in-place with zero memory allocation inside the audio thread.
 */
class VocalSeparatorDsp {

    // Filter coefficients: High-Pass (200 Hz)
    private var hpB0 = 1f
    private var hpB1 = -2f
    private var hpB2 = 1f
    private var hpA1 = 0f
    private var hpA2 = 0f

    // Filter coefficients: Low-Pass (4500 Hz)
    private var lpB0 = 1f
    private var lpB1 = 2f
    private var lpB2 = 1f
    private var lpA1 = 0f
    private var lpA2 = 0f

    // Filter states
    private var hpX1 = 0f
    private var hpX2 = 0f
    private var hpY1 = 0f
    private var hpY2 = 0f

    private var lpX1 = 0f
    private var lpX2 = 0f
    private var lpY1 = 0f
    private var lpY2 = 0f

    private var configuredSampleRate = 0

    fun configure(sampleRateHz: Int) {
        val sr = sampleRateHz.coerceIn(8000, 384000)
        if (sr == configuredSampleRate) return
        configuredSampleRate = sr

        val fs = sr.toDouble()
        val q = 1.0 / sqrt(2.0) // 0.7071 Butterworth

        // 1. High-Pass at 200 Hz (cuts kick/bass guitar out of vocal detection)
        val hpFc = 200.0.coerceAtMost(fs * 0.45)
        val w0Hp = 2.0 * Math.PI * hpFc / fs
        val cosHp = cos(w0Hp)
        val alphaHp = sin(w0Hp) / (2.0 * q)
        val a0Hp = 1.0 + alphaHp

        hpB0 = ((1.0 + cosHp) / (2.0 * a0Hp)).toFloat()
        hpB1 = (-(1.0 + cosHp) / a0Hp).toFloat()
        hpB2 = ((1.0 + cosHp) / (2.0 * a0Hp)).toFloat()
        hpA1 = ((-2.0 * cosHp) / a0Hp).toFloat()
        hpA2 = ((1.0 - alphaHp) / a0Hp).toFloat()

        // 2. Low-Pass at 4500 Hz (cuts high cymbals/air out of vocal detection)
        val lpFc = 4500.0.coerceAtMost(fs * 0.45)
        val w0Lp = 2.0 * Math.PI * lpFc / fs
        val cosLp = cos(w0Lp)
        val alphaLp = sin(w0Lp) / (2.0 * q)
        val a0Lp = 1.0 + alphaLp

        lpB0 = ((1.0 - cosLp) / (2.0 * a0Lp)).toFloat()
        lpB1 = ((1.0 - cosLp) / a0Lp).toFloat()
        lpB2 = ((1.0 - cosLp) / (2.0 * a0Lp)).toFloat()
        lpA1 = ((-2.0 * cosLp) / a0Lp).toFloat()
        lpA2 = ((1.0 - alphaLp) / a0Lp).toFloat()

        reset()
    }

    fun reset() {
        hpX1 = 0f
        hpX2 = 0f
        hpY1 = 0f
        hpY2 = 0f
        lpX1 = 0f
        lpX2 = 0f
        lpY1 = 0f
        lpY2 = 0f
    }

    /**
     * Filters one mono sample through the cascaded bandpass (HP -> LP).
     */
    private inline fun filterVocalBand(m: Float): Float {
        // High-pass step
        val hpOut = hpB0 * m + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2
        hpX2 = hpX1
        hpX1 = m
        hpY2 = hpY1
        hpY1 = hpOut

        // Low-pass step
        val lpOut = lpB0 * hpOut + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
        lpX2 = lpX1
        lpX1 = hpOut
        lpY2 = lpY1
        lpY1 = lpOut

        return lpOut
    }

    /**
     * Processes a ByteBuffer in place.
     */
    fun processPcm(
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
        vocalGain: Float,
        musicGain: Float,
    ) {
        if (channels < 2) return // Vocal center extraction requires stereo
        if (!buffer.hasRemaining()) return

        // Bypass check: If both gains are unity (within epsilon), no processing needed
        if (kotlin.math.abs(vocalGain - 1.0f) < 0.005f && kotlin.math.abs(musicGain - 1.0f) < 0.005f) {
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

    private fun processFloatInPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 7 < limit) {
            val l = buffer.getFloat(pos)
            val r = buffer.getFloat(pos + 4)

            val m = 0.5f * (l + r)
            val s = 0.5f * (l - r)

            val vocal = filterVocalBand(m)
            val musicMid = m - vocal

            val outL = (mGain * (musicMid + s) + vGain * vocal).coerceIn(-1.0f, 1.0f)
            val outR = (mGain * (musicMid - s) + vGain * vocal).coerceIn(-1.0f, 1.0f)

            buffer.putFloat(pos, outL)
            buffer.putFloat(pos + 4, outR)
            pos += 8
        }
    }

    private fun processPcm16InPlace(buffer: ByteBuffer, vGain: Float, mGain: Float) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 3 < limit) {
            val l = buffer.getShort(pos).toFloat() / 32768.0f
            val r = buffer.getShort(pos + 2).toFloat() / 32768.0f

            val m = 0.5f * (l + r)
            val s = 0.5f * (l - r)

            val vocal = filterVocalBand(m)
            val musicMid = m - vocal

            val outL = (mGain * (musicMid + s) + vGain * vocal).coerceIn(-1.0f, 1.0f)
            val outR = (mGain * (musicMid - s) + vGain * vocal).coerceIn(-1.0f, 1.0f)

            buffer.putShort(pos, (outL * 32767.0f).toInt().toShort())
            buffer.putShort(pos + 2, (outR * 32767.0f).toInt().toShort())
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

            val m = 0.5f * (l + r)
            val s = 0.5f * (l - r)

            val vocal = filterVocalBand(m)
            val musicMid = m - vocal

            val outL = (mGain * (musicMid + s) + vGain * vocal).coerceIn(-1.0f, 1.0f)
            val outR = (mGain * (musicMid - s) + vGain * vocal).coerceIn(-1.0f, 1.0f)

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
            val l = buffer.getInt(pos).toDouble() / 2147483648.0
            val r = buffer.getInt(pos + 4).toDouble() / 2147483648.0

            val m = 0.5 * (l + r)
            val s = 0.5 * (l - r)

            val vocal = filterVocalBand(m.toFloat())
            val musicMid = m - vocal

            val outL = (mGain * (musicMid + s) + vGain * vocal).coerceIn(-1.0, 1.0)
            val outR = (mGain * (musicMid - s) + vGain * vocal).coerceIn(-1.0, 1.0)

            buffer.putInt(pos, (outL * 2147483647.0).toLong().toInt())
            buffer.putInt(pos + 4, (outR * 2147483647.0).toLong().toInt())
            pos += 8
        }
    }
}
