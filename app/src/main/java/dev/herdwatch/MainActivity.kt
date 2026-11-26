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
    /**
     * Ask once, and only when there is something to notify about. Safe to call repeatedly: the
     * preference flag and the granted check make it a no-op after the first answer.
     */
    fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.herdwatch.i18n.Str.install(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Put the person back where Android took them from. Only the place is restored — the hosts
        // reconnect themselves, and nothing is sent to a remote host on our own initiative.
        savedInstanceState?.getStringArrayList(KEY_TRAIL)?.let { trail ->
            viewModel.restoreTrail(trail, savedInstanceState.getString(KEY_DRAFT))
        }
        // The notification prompt used to be the first thing a new user saw, before a single host
        // existed and before the app had anything to say. Ask only once there is a machine to watch:
        // ConnectionService calls this when the first host connects, which is the moment the answer
        // means something. A refusal is an answer — the keep-alive still works without it.
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
            // A notification about an agent opens that agent, not just the app. Handled here rather
            // than in a deep link because the payload is an in-process key, not a URL somebody could
            // forge from outside.
            LaunchedEffect(Unit) { openAlertedPane(intent) }
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

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_TRAIL, viewModel.pageTrail())
        outState.putString(KEY_DRAFT, viewModel.draftSnapshot())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // SINGLE_TOP: a second alert tapped while the app is open must reuse this activity.
        setIntent(intent)
        openAlertedPane(intent)
    }

    private fun openAlertedPane(intent: android.content.Intent?) {
        val key = intent?.getStringExtra(ConnectionService.EXTRA_PANE) ?: return
        // Consumed once: a redelivered intent must not pull the user back to a page they left.
        intent.removeExtra(ConnectionService.EXTRA_PANE)
        viewModel.openAlertedPane(key)
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

    private companion object {
        const val KEY_NOTIFICATION_ASKED = "notification_asked"
        private const val KEY_TRAIL = "herdly.trail"
        private const val KEY_DRAFT = "herdly.draft"
    }
}
