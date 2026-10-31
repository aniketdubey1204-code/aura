package com.lastwave.app.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.recoverPreferences
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AuraUserProfile(
    val avatarUrl: String? = null,
    val isCustomAvatar: Boolean = false,
    val ytAccountName: String? = null,
    val channelHandle: String? = null,
    val lastFmUsername: String? = null,
)

@Singleton
class UserProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val ytAuthManager: YtMusicAuthManager,
    private val sessionPreferences: SessionPreferences,
    private val externalScope: CoroutineScope,
) {
    private object Keys {
        val CUSTOM_AVATAR_PATH = stringPreferencesKey("aura_custom_avatar_path")
        val LASTFM_AVATAR_URL = stringPreferencesKey("aura_lastfm_avatar_url")
    }

    private val customAvatarFlow = dataStore.data
        .recoverPreferences("UserProfileRepository.customAvatar")
        .map { it[Keys.CUSTOM_AVATAR_PATH] }

    private val lastFmAvatarFlow = dataStore.data
        .recoverPreferences("UserProfileRepository.lastFmAvatar")
        .map { it[Keys.LASTFM_AVATAR_URL] }

    val userProfile: StateFlow<AuraUserProfile> = combine(
        customAvatarFlow,
        ytAuthManager.connection,
        lastFmAvatarFlow,
        sessionPreferences.session,
    ) { customPath, ytConn, lastFmUrl, session ->
        val effectiveAvatar = when {
            !customPath.isNullOrBlank() && File(customPath).exists() -> "file://$customPath"
            !ytConn.photoUrl.isNullOrBlank() -> ytConn.photoUrl
            !lastFmUrl.isNullOrBlank() -> lastFmUrl
            else -> null
        }
        val isCustom = !customPath.isNullOrBlank() && File(customPath).exists()
        val ytName = ytConn.accountName.takeIf { it.isNotBlank() && !it.equals("Google account", ignoreCase = true) }
        val handle = ytConn.channelHandle.takeIf { !it.isNullOrBlank() }
        val lfmUser = session.username.takeIf { it.isNotBlank() && !it.equals("Guest User", ignoreCase = true) }

        AuraUserProfile(
            avatarUrl = effectiveAvatar,
            isCustomAvatar = isCustom,
            ytAccountName = ytName,
            channelHandle = handle,
            lastFmUsername = lfmUser,
        )
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = AuraUserProfile(),
    )

    val effectiveAvatarUrl: StateFlow<String?> = userProfile.map { it.avatarUrl }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val isCustomAvatar: StateFlow<Boolean> = userProfile.map { it.isCustomAvatar }.stateIn(
        scope = externalScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    suspend fun setCustomAvatarFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext false
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return@withContext false

            val size = minOf(originalBitmap.width, originalBitmap.height)
            val x = (originalBitmap.width - size) / 2
            val y = (originalBitmap.height - size) / 2
            val cropped = Bitmap.createBitmap(originalBitmap, x, y, size, size)
            val scaled = if (size > 512) {
                Bitmap.createScaledBitmap(cropped, 512, 512, true)
            } else {
                cropped
            }

            val avatarFile = File(context.filesDir, "aura_profile_avatar.png")
            FileOutputStream(avatarFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 95, out)
            }

            dataStore.edit {
                it[Keys.CUSTOM_AVATAR_PATH] = avatarFile.absolutePath
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clearCustomAvatar() = withContext(Dispatchers.IO) {
        try {
            val avatarFile = File(context.filesDir, "aura_profile_avatar.png")
            if (avatarFile.exists()) {
                avatarFile.delete()
            }
            dataStore.edit {
                it.remove(Keys.CUSTOM_AVATAR_PATH)
            }
        } catch (_: Exception) { }
    }

    suspend fun updateLastFmAvatar(url: String?) = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext
        try {
            dataStore.edit {
                it[Keys.LASTFM_AVATAR_URL] = url
            }
        } catch (_: Exception) { }
    }
}
