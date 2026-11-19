package dev.herdwatch

import android.app.LocaleManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.herdwatch.data.LanguageMode
import dev.herdwatch.ui.AppViewModel
import dev.herdwatch.ui.HerdwatchRoot
import dev.herdwatch.data.ThemeMode
import dev.herdwatch.ui.theme.HerdwatchTheme
import dev.herdwatch.ui.design.DsSpacing

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(application as HerdwatchApp)
    }

    // The keep-alive service must post an ongoing notification; without this grant Android 13+
    // still runs the service but hides its notification, which looks like a silent background
    // process. Ask once, at first launch, and never block the UI on the answer.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * The platform recreates the activity on a locale change unless we opt out. We do opt out
     * (manifest configChanges) and re-read resources here, so switching language is a recomposition
     * rather than a window teardown — no black frame, and the ViewModel keeps its sessions.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.herdwatch.i18n.Str.install(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The status bar must follow the app's theme choice, not the system one: Settings can force
        // light while the device is dark (and vice versa), and enableEdgeToEdge would then keep the
        // system's icon/scrims over the opposite chassis.
        val initialDark = when (viewModel.preferences.value.theme) {
            ThemeMode.SYSTEM -> resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        enableEdgeToEdge(
            statusBarStyle = if (initialDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
            navigationBarStyle = if (initialDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
        )
        setContent {
            val preferences by viewModel.preferences.collectAsState()
            // A revision counter, not the language itself: the activity handles locale changes
            // in place (see configChanges), so Compose needs an explicit signal to re-read every
            // stringResource. Keying the whole tree on it is cheaper and calmer than a recreate.
            var localeRevision by remember { mutableIntStateOf(0) }
            LaunchedEffect(preferences.language) {
                applyLanguage(preferences.language)
                localeRevision++
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (preferences.theme) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            HerdwatchTheme(
                darkTheme = dark,
            ) {
                key(localeRevision) { HerdwatchRoot(viewModel) }
            }
        }
    }

    /**
     * Per-app language. Android 13+ has the platform service, older releases follow the device
     * language — we say so in Settings instead of faking a switch we cannot honour.
     */
    private fun applyLanguage(mode: LanguageMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(LocaleManager::class.java) ?: return
        val desired = when (mode) {
            LanguageMode.SYSTEM -> LocaleList.getEmptyLocaleList()
            LanguageMode.ZH -> LocaleList.forLanguageTags("zh-CN")
            LanguageMode.EN -> LocaleList.forLanguageTags("en")
        }
        if (manager.applicationLocales == desired) return
        // Changing the app locale recreates the activity. Without a cross-activity animation the
        // window is torn down before the new one draws, which reads as a black flash. The platform
        // transition keeps the old frame visible until the new content is ready.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        }
        manager.applicationLocales = desired
    }
}
