package com.lastwave.app.playback.vocal

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time, zero-latency stereo vocal and music separator with transient beat preservation
 * and 3-band spectral azimuth isolation.
 *
 * Solves the two fundamental flaws of basic mid/side processing:
 * 1. Beat Loss in Karaoke: Uses a fast differential envelope tracker (transient detector)
 *    on the center channel. Percussive onsets (snare crack, kick click, claps, drum hits)
 *    are recognized as rhythmic transients and preserved in the music path, while sustained
 *    vocal formants are isolated for attenuation. Sub-bass (< 140 Hz) and cymbal air (> 7.5 kHz)
 *    are routed cleanly into the music path.
 * 2. Incomplete Vocal Solo: Rather than playing the full mono mix, vocal solo mode strips
 *    the sub-bass, silences percussive drum transients, and applies azimuth side-correlation
 *    to suppress stereo-spread instruments.
 *
 * Mathematical guarantee: When both gains are unity (or bypassed), the reconstruction
 * is bit-for-bit identical to the original stereo input.
 */
class VocalSeparatorDsp {

    // ── Crossover Filters ──
    // 2nd-order Butterworth LPF at 140 Hz (Bass: kick body, sub-bass)
    private var bassB0 = 0f
    private var bassB1 = 0f
    private var bassB2 = 0f
    private var bassA1 = 0f
    private var bassA2 = 0f

    private var bassX1 = 0f
    private var bassX2 = 0f
    private var bassY1 = 0f
    private var bassY2 = 0f

    // 2nd-order Butterworth HPF at 7500 Hz (Air: cymbal sheen, hi-hats, ambiance)
    private var airB0 = 0f
    private var airB1 = 0f
    private var airB2 = 0f
    private var airA1 = 0f
    private var airA2 = 0f

    private var airX1 = 0f
    private var airX2 = 0f
    private var airY1 = 0f
    private var airY2 = 0f

    // ── Transient Beat Detector States ──
    private var envFast = 0f
    private var envSlow = 0f

    private var coeffFastAttack = 0.02f
    private var coeffFastDecay = 0.0015f
    private var coeffSlowAttack = 0.001f
    private var coeffSlowDecay = 0.00025f

    // ── Gain Smoothing ──
    private var smoothedVocalGain = 1f
    private var smoothedMusicGain = 1f
    private var smoothingCoeff = 0.002f

    private var configuredSampleRate = 0

    fun configure(sampleRateHz: Int) {
        val sr = sampleRateHz.coerceIn(8000, 384000)
        if (sr == configuredSampleRate) return
        configuredSampleRate = sr

        val fs = sr.toDouble()
        val q = 1.0 / sqrt(2.0) // Butterworth Q ≈ 0.7071

        // 1. Low-Pass at 140 Hz (protects kick fundamental & bass guitar)
        val fcBass = 140.0.coerceAtMost(fs * 0.45)
        val w0Bass = 2.0 * PI * fcBass / fs
        val cosBass = cos(w0Bass)
        val alphaBass = sin(w0Bass) / (2.0 * q)
        val a0Bass = 1.0 + alphaBass

        bassB0 = ((1.0 - cosBass) / (2.0 * a0Bass)).toFloat()
        bassB1 = ((1.0 - cosBass) / a0Bass).toFloat()
        bassB2 = ((1.0 - cosBass) / (2.0 * a0Bass)).toFloat()
        bassA1 = ((-2.0 * cosBass) / a0Bass).toFloat()
        bassA2 = ((1.0 - alphaBass) / a0Bass).toFloat()

        // 2. High-Pass at 7500 Hz (protects cymbals, hi-hats, room air)
        val fcAir = 7500.0.coerceAtMost(fs * 0.45)
        val w0Air = 2.0 * PI * fcAir / fs
        val cosAir = cos(w0Air)
        val alphaAir = sin(w0Air) / (2.0 * q)
        val a0Air = 1.0 + alphaAir

        airB0 = ((1.0 + cosAir) / (2.0 * a0Air)).toFloat()
        airB1 = (-(1.0 + cosAir) / a0Air).toFloat()
        airB2 = ((1.0 + cosAir) / (2.0 * a0Air)).toFloat()
        airA1 = ((-2.0 * cosAir) / a0Air).toFloat()
        airA2 = ((1.0 - alphaAir) / a0Air).toFloat()

        // 3. Transient detector time constants
        // Fast attack ~1.2 ms, fast decay ~18 ms
        coeffFastAttack = (1.0 - exp(-1.0 / (0.0012 * fs))).toFloat()
        coeffFastDecay = (1.0 - exp(-1.0 / (0.018 * fs))).toFloat()

        // Slow attack ~30 ms, slow decay ~120 ms
        coeffSlowAttack = (1.0 - exp(-1.0 / (0.030 * fs))).toFloat()
        coeffSlowDecay = (1.0 - exp(-1.0 / (0.120 * fs))).toFloat()

        // Gain smoothing ~10 ms
        smoothingCoeff = (1.0 - exp(-1.0 / (0.010 * fs))).toFloat()

        resetStates()
    }

    fun reset() {
        resetStates()
    }

    private fun resetStates() {
        bassX1 = 0f; bassX2 = 0f; bassY1 = 0f; bassY2 = 0f
        airX1 = 0f; airX2 = 0f; airY1 = 0f; airY2 = 0f
        envFast = 0f
        envSlow = 0f
        smoothedVocalGain = 1f
        smoothedMusicGain = 1f
    }

    private fun filterBass(input: Float): Float {
        val y = bassB0 * input + bassB1 * bassX1 + bassB2 * bassX2 - bassA1 * bassY1 - bassA2 * bassY2
        bassX2 = bassX1; bassX1 = input
        bassY2 = bassY1; bassY1 = y
        return y
    }

    private fun filterAir(input: Float): Float {
        val y = airB0 * input + airB1 * airX1 + airB2 * airX2 - airA1 * airY1 - airA2 * airY2
        airX2 = airX1; airX1 = input
        airY2 = airY1; airY1 = y
        return y
    }

    /**
     * Processes one stereo sample pair.
     * Preserves drum transients, protects low bass and cymbal air, and isolates center vocals.
     */
    private fun processStereoSample(
        l: Float,
        r: Float,
        targetVocalGain: Float,
        targetMusicGain: Float,
    ): Long {
        // Gain smoothing
        smoothedVocalGain += smoothingCoeff * (targetVocalGain - smoothedVocalGain)
        smoothedMusicGain += smoothingCoeff * (targetMusicGain - smoothedMusicGain)
        val vG = smoothedVocalGain
        val mG = smoothedMusicGain

        // Mid/Side stereo decomposition
        val mid = 0.5f * (l + r)
        val side = 0.5f * (l - r)

        // 3-band spectral decomposition of the center channel
        val midBass = filterBass(mid)  // < 140 Hz (kick drum body, sub-bass)
        val midAir = filterAir(mid)    // > 7500 Hz (cymbals, hi-hat shimmer)
        val midBand = mid - midBass - midAir // 140 Hz – 7500 Hz (vocal range + snare/beats)

        val absMidBand = abs(midBand)

        // Transient beat detection on the mid band
        if (absMidBand > envFast) {
            envFast += coeffFastAttack * (absMidBand - envFast)
        } else {
            envFast += coeffFastDecay * (absMidBand - envFast)
        }

        if (absMidBand > envSlow) {
            envSlow += coeffSlowAttack * (absMidBand - envSlow)
        } else {
            envSlow += coeffSlowDecay * (absMidBand - envSlow)
        }

        val diff = (envFast - envSlow).coerceAtLeast(0f)
        val transientWeight = (diff / (envSlow + 0.008f) * 2.2f).coerceIn(0f, 1f)

        // Azimuth side-correlation: wide instruments have strong side energy
        val absSide = abs(side)
        val centerPurity = (1f - (absSide * 1.5f / (absMidBand + 0.001f))).coerceIn(0f, 1f)

        // Vocal component: sustained center energy with low transient energy
        val vocalWeight = (1.0f - transientWeight) * centerPurity
        val midVocal = vocalWeight * midBand
        val midMusicBand = midBand - midVocal

        // Reconstruct Mid and Side
        // Music path retains bass, air, drum transients, and side instruments.
        // Vocal path receives isolated center formants.
        val outMid = mG * (midBass + midAir + midMusicBand) + vG * midVocal
        val outSide = mG * side

        val outL = (outMid + outSide).coerceIn(-1.0f, 1.0f)
        val outR = (outMid - outSide).coerceIn(-1.0f, 1.0f)

        return (outL.toBits().toLong() shl 32) or (outR.toBits().toLong() and 0xFFFFFFFFL)
    }

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

        // Bypass: both gains at unity within epsilon
        if (abs(vocalGain - 1.0f) < 0.005f && abs(musicGain - 1.0f) < 0.005f) {
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
