package com.lastwave.app.playback.automix.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignatureExtractor
import com.lastwave.app.playback.automix.api.AutoMixController
import com.lastwave.app.playback.automix.api.AutoMixPreferences
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.TrackChromeSummary
import com.lastwave.app.playback.automix.api.TransitionStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DualDeckTransportCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AutoMixPreferences,
    private val signatureExtractor: AcousticSignatureExtractor,
) : AutoMixController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var baseVolume: Float = 1.0f
        private set

    fun setBaseVolume(vol: Float) {
        baseVolume = vol.coerceIn(0f, 1f)
        if (activeSpliceJob == null || activeSpliceJob?.isActive != true) {
            currentPrimaryPlayer?.volume = baseVolume
        }
    }

    private val _telemetry = MutableStateFlow(DeckBlendTelemetry.IDLE)
    override val telemetry: StateFlow<DeckBlendTelemetry> = _telemetry.asStateFlow()

    private var currentSettings = AutoMixSettings()
    val isEnabled: Boolean get() = currentSettings.autoMixEnabled
    private var isHardwareBypassEngaged = false
    private var isJamRoomActive = false

    private var activeTrackKey: String? = null
    private var activeSpliceJob: Job? = null
    private var currentSplicePlan: SplicePlan? = null
    private var currentPrimaryPlayer: ExoPlayer? = null
    private var currentSecondaryPlayer: ExoPlayer? = null

    // Track reference callbacks into MusicPlayer
    var onPerformDeckSwap: ((outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit)? = null
    var onTransitionComplete: (() -> Unit)? = null

    init {
        scope.launch {
            preferences.settings.collectLatest { settings ->
                currentSettings = settings
                if (!settings.autoMixEnabled) {
                    cancelTransition(revertToPrimary = true)
                }
            }
        }
    }

    fun onTrackProgressTick(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer?,
        currentTrack: PlayableTrack?,
        nextTrack: PlayableTrack?,
        positionMs: Long,
        durationMs: Long,
        nextTrackStreamUrl: String? = null,
        isShuffleActive: Boolean = false,
    ) {
        if (!currentSettings.autoMixEnabled || isHardwareBypassEngaged || isJamRoomActive) {
            if (_telemetry.value.phase != BlendPhase.IDLE) {
                _telemetry.value = DeckBlendTelemetry.IDLE
            }
            return
        }

        currentPrimaryPlayer = primaryPlayer
        currentSecondaryPlayer = secondaryPlayer

        if (currentTrack == null || durationMs <= 0L) return

        // If a blend is actively running, do not interfere with the transition in progress
        if (activeSpliceJob?.isActive == true) {
            return
        }

        val trackKey = currentTrack.videoId ?: "${currentTrack.artist}|${currentTrack.title}"
        if (activeTrackKey != trackKey) {
            activeTrackKey = trackKey
            currentSplicePlan = null
            // Only reset telemetry if no blend is active or completing —
            // the deck swap changes the current track mid-blend, which must
            // not flicker the badge back to the quality label.
            val currentPhase = _telemetry.value.phase
            if (currentPhase != BlendPhase.BLENDING &&
                currentPhase != BlendPhase.LOOP_LOCKED &&
                currentPhase != BlendPhase.MIX_COMPLETED) {
                _telemetry.value = DeckBlendTelemetry.IDLE
            }
        }

        val remainingMs = durationMs - positionMs

        // 1. Look-ahead pre-analysis trigger: 25% progress or 90s remaining
        if (((durationMs > 20_000L && remainingMs in 1L..90_000L) || (durationMs in 1L..20_000L && remainingMs > 0L)) && nextTrack != null) {
            if (_telemetry.value.phase == BlendPhase.IDLE) {
                _telemetry.update {
                    it.copy(
                        phase = BlendPhase.PRE_ANALYZING,
                        incomingTrackPreview = TrackChromeSummary(
                            title = nextTrack.title,
                            artist = nextTrack.artist,
                            artworkUrl = nextTrack.artworkUrl,
                            durationMs = nextTrack.durationMs ?: 0L,
                        )
                    )
                }

                signatureExtractor.preAnalyzeTrack(nextTrack, nextTrackStreamUrl ?: nextTrack.playbackUrl) { sig ->
                    val plan = SpliceTrajectorySynthesizer.synthesizePlan(
                        outgoingTrack = currentTrack,
                        outgoingSignature = null,
                        incomingTrack = nextTrack,
                        incomingSignature = sig,
                        settings = currentSettings,
                        isShuffleActive = isShuffleActive,
                        outgoingDurationMs = durationMs,
                        incomingDurationMs = nextTrack.durationMs,
                    )
                    currentSplicePlan = plan

                    if (!plan.isBypass) {
                        _telemetry.update {
                            it.copy(
                                phase = BlendPhase.ARMED,
                                spliceStartPositionMs = plan.cueOutStartMs,
                                spliceDurationMs = plan.durationMs,
                                activeTransitionStyle = plan.transitionStyle,
                                harmonicKeyPair = sig.camelotKey.toString(),
                                syncedBpm = sig.beatGrid.bpm.value,
                            )
                        }
                    } else {
                        // Truly bypassed (e.g. same album gapless preservation)
                        _telemetry.update { it.copy(phase = BlendPhase.IDLE) }
                    }
                }
            }
        }

        // 2. Automatic Fallback Plan (Never miss a transition window)
        val fallbackDurationMs = (currentSettings.targetDurationSeconds * 1000L).coerceIn(8_000L, 25_000L)
        if (durationMs > 10_000L && remainingMs in 1L..60_000L && remainingMs <= fallbackDurationMs + 5000L && nextTrack != null) {
            val plan = currentSplicePlan
            if (plan == null) {
                val safePlan = SplicePlan(
                    cueOutStartMs = maxOf(0L, durationMs - fallbackDurationMs),
                    cueInStartMs = 0L,
                    durationMs = fallbackDurationMs,
                    transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                    isBypass = false,
                )
                currentSplicePlan = safePlan
                _telemetry.update {
                    it.copy(
                        phase = BlendPhase.ARMED,
                        spliceStartPositionMs = safePlan.cueOutStartMs,
                        spliceDurationMs = safePlan.durationMs,
                        activeTransitionStyle = safePlan.transitionStyle,
                    )
                }
            }
        }

        // 3. Trigger Splice at Splice Horizon
        val plan = currentSplicePlan
        if (plan != null && !plan.isBypass && (_telemetry.value.phase == BlendPhase.ARMED || _telemetry.value.phase == BlendPhase.PRE_ANALYZING || _telemetry.value.phase == BlendPhase.IDLE)) {
            val effectiveCueOut = if (durationMs > 40_000L && plan.cueOutStartMs < durationMs - 40_000L) {
                maxOf(0L, durationMs - plan.durationMs).coerceAtLeast(durationMs - 40_000L)
            } else {
                plan.cueOutStartMs
            }
            val isInEndingWindow = (durationMs > 20_000L && remainingMs in 1L..60_000L) || (durationMs in 1L..20_000L && remainingMs > 0L)
            val shouldTrigger = isInEndingWindow && (positionMs >= effectiveCueOut || remainingMs <= plan.durationMs)
            if (shouldTrigger && secondaryPlayer != null) {
                if (secondaryPlayer.playbackState == Player.STATE_IDLE && secondaryPlayer.mediaItemCount > 0) {
                    secondaryPlayer.prepare()
                }
                val isSecondaryReady = secondaryPlayer.playbackState == Player.STATE_READY ||
                    (secondaryPlayer.playbackState == Player.STATE_BUFFERING && secondaryPlayer.playerError == null)
                if (isSecondaryReady) {
                    startBlendExecution(primaryPlayer, secondaryPlayer, plan.copy(cueOutStartMs = effectiveCueOut))
                }
            }
        }
    }

    private fun startBlendExecution(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer,
        plan: SplicePlan,
    ) {
        activeSpliceJob?.cancel()
        currentPrimaryPlayer = primaryPlayer
        currentSecondaryPlayer = secondaryPlayer
        val durationMs = plan.durationMs.coerceAtLeast(1000L)

        primaryPlayer.repeatMode = Player.REPEAT_MODE_OFF

        _telemetry.update {
            it.copy(
                phase = if (plan.requiresPhraseLoop) BlendPhase.LOOP_LOCKED else BlendPhase.BLENDING,
                blendProgress = 0.0f,
                activeTransitionStyle = plan.transitionStyle,
                loopBarsActive = if (plan.requiresPhraseLoop) plan.loopBarsCount else null,
            )
        }

        primaryPlayer.setPlaybackSpeed(1.0f)
        val targetSpeed = plan.tempoRatio
        secondaryPlayer.setPlaybackSpeed(targetSpeed)

        secondaryPlayer.volume = 0.0f
        secondaryPlayer.seekTo(plan.cueInStartMs)
        secondaryPlayer.play()

        activeSpliceJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var swapped = false

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)

                _telemetry.update { it.copy(blendProgress = progress) }

                // DJ-style gains: outgoing holds groove for first 40%, incoming enters over first 60%
                val isDjMix = plan.transitionStyle == TransitionStyle.HARMONIC_BEATMIX ||
                    plan.transitionStyle == TransitionStyle.SPECTRAL_SWEEP
                val (gainOut, gainIn) = if (isDjMix) {
                    fun smooth(x: Float): Float {
                        val t = x.coerceIn(0f, 1f)
                        return t * t * (3f - 2f * t)
                    }
                    val gIn = kotlin.math.sin(smooth(progress / 0.6f) * Math.PI * 0.5).toFloat()
                    val gOut = kotlin.math.cos(smooth((progress - 0.4f) / 0.6f) * Math.PI * 0.5).toFloat()
                    Pair(gOut, gIn)
                } else {
                    val gOut = kotlin.math.cos(progress * Math.PI * 0.5).toFloat().coerceIn(0.0f, 1.0f)
                    val gIn = kotlin.math.sin(progress * Math.PI * 0.5).toFloat().coerceIn(0.0f, 1.0f)
                    Pair(gOut, gIn)
                }
                val sum = gainIn + gainOut
                val trim = if (sum <= 1f) 1f else 1f / kotlin.math.sqrt(sum)
                primaryPlayer.volume = (gainOut * trim * baseVolume).coerceIn(0f, 1f)
                secondaryPlayer.volume = (gainIn * trim * baseVolume).coerceIn(0f, 1f)

                // Dominant energy handoff point (65% through transition window):
                if (progress >= 0.65f && !swapped) {
                    swapped = true
                    runCatching {
                        onPerformDeckSwap?.invoke(primaryPlayer, secondaryPlayer)
                    }.onFailure { e ->
                        android.util.Log.e("DualDeckCoordinator", "Error during deck swap", e)
                    }
                }

                if (progress >= 1.0f) break
                delay(20L)
            }

            primaryPlayer.volume = 0.0f
            secondaryPlayer.volume = baseVolume
            runCatching { primaryPlayer.pause() }

            _telemetry.update {
                it.copy(
                    phase = BlendPhase.MIX_COMPLETED,
                    blendProgress = 1.0f,
                )
            }

            runCatching { onTransitionComplete?.invoke() }

            // Smooth tempo glide back to 1.0f over 5 seconds (Apple Music post-mix tempo restoration)
            val incomingPlayer = secondaryPlayer
            val fromRate = plan.tempoRatio
            if (kotlin.math.abs(fromRate - 1.0f) > 0.005f) {
                scope.launch {
                    val steps = 50
                    for (i in 1..steps) {
                        delay(100L)
                        val p = i.toFloat() / steps.toFloat()
                        val s = p * p * (3f - 2f * p)
                        val currentRate = fromRate + (1.0f - fromRate) * s
                        runCatching { incomingPlayer.setPlaybackSpeed(currentRate) }
                    }
                    runCatching { incomingPlayer.setPlaybackSpeed(1.0f) }
                }
            }

            // Display MIXED ✓ for 1,500 ms before settling to IDLE
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE }
            currentSplicePlan = null
            currentPrimaryPlayer = null
            currentSecondaryPlayer = null
        }
    }

    override fun cancelTransition(revertToPrimary: Boolean) {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        currentSplicePlan = null
        _telemetry.update { DeckBlendTelemetry.IDLE }
        if (revertToPrimary) {
            runCatching {
                currentPrimaryPlayer?.volume = baseVolume
                currentSecondaryPlayer?.volume = 0.0f
                currentSecondaryPlayer?.pause()
            }
        }
        currentPrimaryPlayer = null
        currentSecondaryPlayer = null
    }

    override fun forceCommitTransition() {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        val primary = currentPrimaryPlayer
        val secondary = currentSecondaryPlayer
        if (primary != null && secondary != null) {
            runCatching {
                onPerformDeckSwap?.invoke(primary, secondary)
                primary.volume = 0.0f
                secondary.volume = baseVolume
                primary.pause()
                onTransitionComplete?.invoke()
            }
        }
        _telemetry.update {
            it.copy(
                phase = BlendPhase.MIX_COMPLETED,
                blendProgress = 1.0f,
            )
        }
        scope.launch {
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE }
            currentSplicePlan = null
            currentPrimaryPlayer = null
            currentSecondaryPlayer = null
        }
    }

    fun toggleAutoMix() {
        scope.launch {
            preferences.setAutoMixEnabled(!currentSettings.autoMixEnabled)
        }
    }

    override fun triggerImmediateTransition(customDurationMs: Long?) {
        val duration = customDurationMs ?: (currentSettings.targetDurationSeconds * 1000L)
        val plan = SplicePlan(
            cueOutStartMs = 0L,
            cueInStartMs = 0L,
            durationMs = duration,
            transitionStyle = TransitionStyle.HARMONIC_BEATMIX,
        )
        currentSplicePlan = plan
    }

    override fun handleSeekEvent(targetPositionMs: Long) {
        val plan = currentSplicePlan ?: return
        if (_telemetry.value.phase == BlendPhase.BLENDING || _telemetry.value.phase == BlendPhase.LOOP_LOCKED) {
            if (targetPositionMs < plan.cueOutStartMs) {
                // Scrubbing before splice start: cancel incoming blend and restore primary to unity
                cancelTransition(revertToPrimary = true)
            } else if (targetPositionMs >= plan.cueOutStartMs + (plan.durationMs * 0.65f)) {
                // Scrubbing past dominant handoff: immediately commit transition
                forceCommitTransition()
            }
        }
    }

    override fun updateHardwareBypassState(isHardwareBypassActive: Boolean) {
        isHardwareBypassEngaged = isHardwareBypassActive
        if (isHardwareBypassActive) {
            cancelTransition(revertToPrimary = true)
        }
    }

    fun setJamRoomActive(active: Boolean) {
        isJamRoomActive = active
        if (active) {
            cancelTransition(revertToPrimary = true)
        }
    }
}
