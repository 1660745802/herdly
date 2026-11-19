package dev.herdwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.RepoState
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the fleet connected while the app is not visible.
 *
 * Without this the repository deliberately tore everything down on `ON_STOP` — closing the bridge
 * SSH clients, cancelling polling and dropping every pane session — so returning to the app always
 * meant a visible reconnect. A foreground service is the only way Android lets a process keep
 * sockets open and keep polling while the user is elsewhere, and it obliges us to show a
 * notification saying so. The notification is not decoration: it is the price of the guarantee.
 *
 * The service owns nothing. [HerdRepository] lives in [HerdwatchApp]; this only holds the process
 * alive and mirrors the state it already maintains.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    private val repository: HerdRepository
        get() = (application as HerdwatchApp).repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote immediately with the current state: several OEMs kill a service that takes too
        // long to call startForeground after being started.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(repository.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        if (watcher == null) {
            watcher = scope.launch {
                repository.state.collect { state ->
                    val notification = build(state)
                    // The notification is how the service stays legitimate, but the user can still
                    // refuse it on 13+: the connection keeps running, only the update goes quiet.
                    if (canPostNotifications()) notify(notification)
                    // Nothing to keep alive once the last host is gone.
                    if (state.hosts.isEmpty()) stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        // Guarded by canPostNotifications() immediately above; lint cannot see across methods.
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                Str.get(R.string.service_channel_name),
                // Low: this is a presence indicator, not an alert. The task queue itself is what
                // asks for attention, and it already does so inside the app.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = Str.get(R.string.service_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun build(state: RepoState): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val connected = state.hosts.count { it.conn is ConnState.Connected }
        val waiting = state.agents.count {
            it.status == AgentStatus.WAITING || it.status == AgentStatus.REVIEW || it.status == AgentStatus.UNKNOWN
        }
        val summary = when {
            state.hosts.isEmpty() -> Str.get(R.string.service_summary_offline)
            connected == 0 -> Str.get(R.string.service_summary_offline)
            waiting > 0 -> listOf(
                Str.quantity(R.plurals.service_summary_hosts, connected, connected),
                Str.quantity(R.plurals.service_summary_waiting, waiting, waiting),
            ).joinToString(" · ")
            else -> listOf(
                Str.quantity(R.plurals.service_summary_hosts, connected, connected),
                Str.get(R.string.service_summary_idle),
            ).joinToString(" · ")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_herd)
            .setContentTitle(Str.get(R.string.service_title))
            .setContentText(summary)
            .setContentIntent(content)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConnectionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
