package io.github.drolevar.audiotales.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drolevar.audiotales.data.preferences.ThemeMode
import io.github.drolevar.audiotales.data.preferences.UserPreferencesRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val themeMode = preferences.themeMode.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ThemeMode.SYSTEM,
    )
    val dynamicColor = preferences.dynamicColor.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )
    private val _preferenceSaveFailed = MutableStateFlow(false)
    val preferenceSaveFailed = _preferenceSaveFailed.asStateFlow()

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                preferences.setThemeMode(themeMode)
                _preferenceSaveFailed.value = false
            } catch (_: IOException) {
                _preferenceSaveFailed.value = true
            }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.setDynamicColor(enabled)
                _preferenceSaveFailed.value = false
            } catch (_: IOException) {
                _preferenceSaveFailed.value = true
            }
        }
    }
}
