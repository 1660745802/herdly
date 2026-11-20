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
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdwatch.data.AppPresence
import dev.herdwatch.data.AttentionAlerts
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.RepoState
import dev.herdwatch.data.SeenMarks
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.ui.markSeen
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
    private var lastSummary: String? = null
    private var stopped = false
    private val alerts = AttentionAlerts()

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
            build(summaryOf(repository.state.value).also { lastSummary = it }),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        if (watcher == null) {
            watcher = scope.launch {
                // START_STICKY may recreate only the service in a fresh process. In that path no
                // Activity/ViewModel exists to bootstrap the application-scoped repository.
                repository.bootstrap()
                repository.state.collect { state ->
                    // Re-posting the same text every five seconds made the shade flicker on some
                    // OEMs and woke the notification pipeline for nothing. Post on change only.
                    val summary = summaryOf(state)
                    if (!stopped && summary != lastSummary) {
                        lastSummary = summary
                        // The notification is how the service stays legitimate, but the user can
                        // still refuse it on 13+: the connection keeps running, only updates go quiet.
                        if (canPostNotifications()) notify(build(summary))
                    }
                    // Alerts are a different question from the presence notification: only a
                    // transition we watched happen counts, and a finish the person did not see.
                    val news = alerts.transitions(
                        state.agents,
                        watching = AppPresence::watching,
                        foreground = AppPresence.foreground,
                    )
                    if (news.isNotEmpty() && !stopped && canPostNotifications()) {
                        news.forEach(::postAlert)
                    }
                    // Nothing to keep alive once the last host is gone.
                    if (state.hosts.isEmpty()) {
                        alerts.reset()
                        stopSelf()
                    }
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

    /**
     * Android 14+ budgets `dataSync` foreground services (~6 h cumulative) and calls this when the
     * budget runs out. The default implementation throws, which would crash the app in exactly the
     * long-session case this service exists for. Give up the foreground slot politely instead: the
     * repository drops to its normal background behaviour and the user sees no failure.
     */
    override fun onTimeout(startId: Int) {
        stopKeepAlive()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopKeepAlive()
    }

    private fun stopKeepAlive() {
        // Leave foreground first: stopSelf() alone can leave the notification behind when the
        // system has already revoked the type, and the connection is no longer ours to promise.
        // The watcher keeps collecting until onDestroy, so it must stop posting too: one more
        // emission would re-add an ongoing notification that nothing removes any more.
        stopped = true
        watcher?.cancel()
        watcher = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
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
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    Str.get(R.string.service_alert_channel_name),
                    // Default, not high: an agent that needs a decision should interrupt, but a
                    // finished turn is only a nudge — the channel-level choice keeps both honest.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = Str.get(R.string.service_alert_channel_desc)
                    setShowBadge(true)
                },
            )
        }
    }

    /** One alert per agent. The tag is the stable identity; unlike a truncated integer hash it
     * cannot make two simultaneous agents replace each other's notification. */
    @SuppressLint("MissingPermission")
    private fun postAlert(alert: AttentionAlerts.Alert) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.Builder()
                .scheme("herdly")
                .authority("agent")
                .appendPath(alert.agentKey)
                .build()
            putExtra(EXTRA_PANE, alert.agentKey)
        }
        val content = PendingIntent.getActivity(
            this,
            alert.agentKey.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val titleId = if (alert.needsYou) R.string.service_alert_needs_you else R.string.service_alert_finished
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_herd)
            .setContentTitle(Str.get(titleId, alert.name))
            .setContentText(Str.get(R.string.service_alert_body, alert.hostLabel, alert.paneId))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(
            alert.agentKey,
            ALERT_BASE_ID,
            notification,
        )
    }

    private fun build(summary: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    private fun summaryOf(state: RepoState): String = connectionSummary(state, SeenMarks.marks.value)

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val ALERT_CHANNEL_ID = "attention"
        private const val NOTIFICATION_ID = 42

        // Alert ids live above the presence notification so one never replaces the other.
        private const val ALERT_BASE_ID = 1000

        /** `hostId:paneId` of the agent an alert was about; MainActivity opens that page. */
        const val EXTRA_PANE = "dev.herdwatch.extra.PANE"

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

/**
 * What the keep-alive notification says. Pure so the three branches can be tested without a Service:
 * a notification that claims a connection while every host is offline is worse than none.
 */
internal fun connectionSummary(state: RepoState, seen: Map<String, Long> = emptyMap()): String {
    val connected = state.hosts.count { it.conn is ConnState.Connected }
    // A finished agent whose result has been read is idle, so the notification stops claiming work
    // that is already done and seen.
    val waiting = state.agents.markSeen(seen).count {
        it.status == AgentStatus.WAITING || it.status == AgentStatus.REVIEW || it.status == AgentStatus.UNKNOWN
    }
    return when {
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
}
