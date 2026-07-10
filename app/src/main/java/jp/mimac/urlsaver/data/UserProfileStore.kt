package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface UserProfileStore {
    fun observeProfile(): Flow<UserProfile>
    suspend fun saveDisplayName(displayName: String)
    suspend fun saveAvatarBase64(avatarBase64: String?)
}

class DataStoreUserProfileStore(
    private val context: Context,
) : UserProfileStore {

    override fun observeProfile(): Flow<UserProfile> {
        return context.userProfileDataStore.data.map { preferences ->
            UserProfile(
                displayName = preferences[DISPLAY_NAME_KEY].orEmpty(),
                avatarBase64 = preferences[AVATAR_BASE64_KEY],
            )
        }
    }

    override suspend fun saveDisplayName(displayName: String) {
        context.userProfileDataStore.edit { preferences ->
            preferences[DISPLAY_NAME_KEY] = displayName.trim().take(40)
        }
    }

    override suspend fun saveAvatarBase64(avatarBase64: String?) {
        context.userProfileDataStore.edit { preferences ->
            if (avatarBase64.isNullOrBlank()) {
                preferences.remove(AVATAR_BASE64_KEY)
            } else {
                preferences[AVATAR_BASE64_KEY] = avatarBase64
            }
        }
    }

    private companion object {
        val DISPLAY_NAME_KEY = stringPreferencesKey("user_profile_display_name")
        val AVATAR_BASE64_KEY = stringPreferencesKey("user_profile_avatar_base64")
    }
}

private val Context.userProfileDataStore by preferencesDataStore(name = "user_profile_preferences")
