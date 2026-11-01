package com.lastwave.app.playback.vocal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VocalSeparatorController @Inject constructor() {

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _vocalVolume = MutableStateFlow(1.0f)
    val vocalVolume: StateFlow<Float> = _vocalVolume.asStateFlow()

    private val _musicVolume = MutableStateFlow(1.0f)
    val musicVolume: StateFlow<Float> = _musicVolume.asStateFlow()

    val dsp = VocalSeparatorDsp()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            dsp.reset()
        }
    }

    fun setVocalVolume(volume: Float) {
        _vocalVolume.value = volume.coerceIn(0f, 1.5f)
        if (!_isEnabled.value && (_vocalVolume.value < 0.98f || _musicVolume.value < 0.98f)) {
            _isEnabled.value = true
        }
    }

    fun setMusicVolume(volume: Float) {
        _musicVolume.value = volume.coerceIn(0f, 1.5f)
        if (!_isEnabled.value && (_vocalVolume.value < 0.98f || _musicVolume.value < 0.98f)) {
            _isEnabled.value = true
        }
    }

    fun setKaraokeMode() {
        _isEnabled.value = true
        _vocalVolume.value = 0.0f
        _musicVolume.value = 1.0f
    }

    fun setVocalSoloMode() {
        _isEnabled.value = true
        _vocalVolume.value = 1.0f
        _musicVolume.value = 0.0f
    }

    fun resetToNormal() {
        _vocalVolume.value = 1.0f
        _musicVolume.value = 1.0f
        _isEnabled.value = false
        dsp.reset()
    }
}
