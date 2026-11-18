package dev.herdwatch

import android.app.Application
import dev.herdwatch.data.HostStore
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.PreferencesStore
import dev.herdwatch.i18n.Str

class HerdwatchApp : Application() {
    lateinit var repository: HerdRepository
        private set
    lateinit var hostStore: HostStore
        private set
    lateinit var preferencesStore: PreferencesStore
        private set

    /** Shown in Settings and compared against the latest GitHub release tag. */
    val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            ?.substringBefore('-')
            ?: "0.0.0"
    }

    override fun onCreate() {
        super.onCreate()
        hostStore = HostStore(this)
        preferencesStore = PreferencesStore(this)
        Str.install(this)
        repository = HerdRepository(hostStore)
    }
}
