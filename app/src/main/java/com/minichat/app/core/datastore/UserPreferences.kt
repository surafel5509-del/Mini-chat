package com.minichat.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.minichat.app.design.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Local, on-device preferences. Nothing sensitive is stored here. */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val previewInNotifications: Boolean = true,
    val readReceiptsEnabled: Boolean = true,
    val typingIndicatorsEnabled: Boolean = true,
) {
    companion object {
        /** Used for the very first frame so a saved dark theme never flashes white. */
        val Default = UserSettings()
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "minichat_preferences",
)

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val Notifications = booleanPreferencesKey("notifications_enabled")
        val Sound = booleanPreferencesKey("notification_sound")
        val Vibration = booleanPreferencesKey("notification_vibration")
        val Previews = booleanPreferencesKey("notification_previews")
        val ReadReceipts = booleanPreferencesKey("read_receipts")
        val TypingIndicators = booleanPreferencesKey("typing_indicators")
    }

    val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            UserSettings(
                themeMode = preferences[Keys.ThemeMode]?.let { raw ->
                    runCatching { ThemeMode.valueOf(raw) }.getOrNull()
                } ?: ThemeMode.System,
                notificationsEnabled = preferences[Keys.Notifications] ?: true,
                soundEnabled = preferences[Keys.Sound] ?: true,
                vibrationEnabled = preferences[Keys.Vibration] ?: true,
                previewInNotifications = preferences[Keys.Previews] ?: true,
                readReceiptsEnabled = preferences[Keys.ReadReceipts] ?: true,
                typingIndicatorsEnabled = preferences[Keys.TypingIndicators] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.ThemeMode] = mode.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.Notifications] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.Sound] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.Vibration] = enabled }
    }

    suspend fun setPreviewsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.Previews] = enabled }
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ReadReceipts] = enabled }
    }

    suspend fun setTypingIndicatorsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.TypingIndicators] = enabled }
    }
}
