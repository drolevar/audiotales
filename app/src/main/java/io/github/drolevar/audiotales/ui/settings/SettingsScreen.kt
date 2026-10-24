package io.github.drolevar.audiotales.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.drolevar.audiotales.BuildConfig
import io.github.drolevar.audiotales.R
import io.github.drolevar.audiotales.data.preferences.ThemeMode

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    preferenceSaveFailed: Boolean,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onAbout: () -> Unit,
) {
    SettingsPage(title = stringResource(R.string.settings), onBack = onBack) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.appearance_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.fillMaxWidth().selectableGroup().padding(vertical = 8.dp)) {
                ThemeMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(
                                selected = themeMode == option,
                                onClick = { onThemeModeChanged(option) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = themeMode == option, onClick = null)
                        Text(stringResource(when (option) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        }))
                    }
                }
            }
        }
        if (preferenceSaveFailed) {
            Text(
                stringResource(R.string.preference_save_error),
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about_audiotales)) }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SettingsPage(title = stringResource(R.string.about_audiotales), onBack = onBack) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.about_development_status),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.mit_license), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.copyright_notice), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                content()
            }
        }
    }
}
