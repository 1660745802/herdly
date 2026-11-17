package dev.herdwatch

import android.app.Application
import dev.herdwatch.data.HostStore
import dev.herdwatch.data.HerdRepository

class HerdwatchApp : Application() {
    lateinit var repository: HerdRepository
        private set
    lateinit var hostStore: HostStore
        private set

    override fun onCreate() {
        super.onCreate()
        hostStore = HostStore(this)
        repository = HerdRepository(hostStore)
    }
}
