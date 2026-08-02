package com.scr01.scroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

internal const val ROOT_PIPELINE_WAKELOCK_TIMEOUT_MS = 25L * 60L * 1000L

class ManualFlowGuardService : Service() {
    companion object {
        private const val ACTION_GUARD = "com.scr01.scroot.action.MANUAL_FLOW_GUARD"
        private const val CHANNEL_ID = "manual_root_guard_v1"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_GUARD_START_WAIT_MS = 30_000L
        private val active = AtomicBoolean(false)
        private val pipelineReserved = AtomicBoolean(false)

        internal fun reservePipeline(): Boolean =
            pipelineReserved.compareAndSet(false, true)

        internal fun releasePipelineReservation() {
            pipelineReserved.set(false)
        }

        internal fun isPipelineReservedInProcess(): Boolean = pipelineReserved.get()

        fun start(context: Context): Boolean = try {
            context.startForegroundService(
                Intent(context, ManualFlowGuardService::class.java)
                    .setAction(ACTION_GUARD)
            )
            true
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ManualFlowGuardService::class.java))
            } catch (_: RuntimeException) {
            }
        }

        fun isActiveInProcess(): Boolean = active.get() || pipelineReserved.get()

        internal fun shouldStopAfterRejectedStart(currentlyActive: Boolean): Boolean =
            !currentlyActive

        fun awaitActive(timeoutMs: Long = 4_000L): Boolean {
            val waitMs = timeoutMs.coerceIn(0L, MAX_GUARD_START_WAIT_MS)
            val deadline = SystemClock.elapsedRealtime() + waitMs
            while (!active.get() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(25L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return active.get()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val initialized = try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, notification())
            acquireWakeLock()
        } catch (_: RuntimeException) {
            false
        }
        if (!initialized) {
            stopSelf()
            return
        }
        active.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_GUARD && shouldStopAfterRejectedStart(active.get())) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active.set(false)
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: RuntimeException) {
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Manual root setup",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SCRoot running while system UI components restart"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SCRoot setup running")
            .setContentText("Keep SCRoot open until setup is complete.")
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock(): Boolean = try {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ManualRoot"
        ).apply {
            setReferenceCounted(false)
            acquire(ROOT_PIPELINE_WAKELOCK_TIMEOUT_MS)
        }
        true
    } catch (_: RuntimeException) {
        wakeLock = null
        false
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: RuntimeException) {
        }
        wakeLock = null
    }
}
