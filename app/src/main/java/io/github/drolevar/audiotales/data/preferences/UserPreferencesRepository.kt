package io.github.drolevar.audiotales.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsDataStore
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    private val preferences = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val themeMode: Flow<ThemeMode> = preferences
        .map { preferences ->
            ThemeMode.entries.firstOrNull { it.name == preferences[themeModeKey] }
                ?: ThemeMode.SYSTEM
        }
        .distinctUntilChanged()

    val dynamicColor: Flow<Boolean> = preferences
        .map { it[dynamicColorKey] ?: true }
        .distinctUntilChanged()

    suspend fun setThemeMode(themeMode: ThemeMode) {
        store.edit { it[themeModeKey] = themeMode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[dynamicColorKey] = enabled }
    }
}
