package com.scr01.scroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AutoRootService : Service() {

    companion object {
        const val ACTION_BOOT_ROOT = "com.scr01.scroot.action.BOOT_ROOT"

        private const val CHANNEL_ID = "auto_root_trace_v2"
        private const val NOTIFICATION_ID = 4101
        internal const val TRACE_LAUNCH_NOTIFICATION_ID = 4102
        private const val MAX_LOG_BYTES = 512 * 1024L

        @Volatile
        private var activeInProcess = false
        private val launchReserved = AtomicBoolean(false)

        internal fun reserveLaunch(): Boolean = launchReserved.compareAndSet(false, true)

        internal fun releaseLaunchReservation() {
            launchReserved.set(false)
        }

        internal fun isLaunchReservedInProcess(): Boolean = launchReserved.get()

        fun isActiveInProcess(): Boolean = activeInProcess || launchReserved.get()

        fun acknowledgeTracePresented(serviceContext: android.content.Context) {
            try {
                serviceContext.getSystemService(NotificationManager::class.java)
                    .cancel(TRACE_LAUNCH_NOTIFICATION_ID)
            } catch (_: RuntimeException) {
            }
        }

        internal fun shouldStopAfterRejectedStart(currentlyRunning: Boolean): Boolean =
            !currentlyRunning

        internal fun boundedAutoLogMessage(value: String): String {
            val truncated = value.length > 4_096
            val sanitized = buildString(minOf(value.length, 4_096)) {
                value.take(4_096).forEach { character ->
                    append(
                        if (character.code in 0x00..0x1f || character.code == 0x7f) {
                            ' '
                        } else {
                            character
                        }
                    )
                }
            }
            return if (truncated) sanitized.take(4_095) + "…" else sanitized
        }

        internal fun autoLogNeedsRotation(currentBytes: Long, entryBytes: Int): Boolean =
            currentBytes < 0L || entryBytes < 0 || entryBytes > MAX_LOG_BYTES ||
                currentBytes > MAX_LOG_BYTES - entryBytes
    }

    private val running = AtomicBoolean(false)
    private val destroying = AtomicBoolean(false)
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SCRoot-AutoRoot")
    }
    @Volatile
    private var workerFuture: Future<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private var foregroundReady = false

    override fun onCreate() {
        super.onCreate()
        foregroundReady = try {
            createNotificationChannel()
            startForeground(
                NOTIFICATION_ID,
                notification(
                    "Preparing automatic root",
                    "Checking the boot state.",
                    ongoing = true,
                    openTrace = false
                )
            )
            true
        } catch (_: RuntimeException) {
            false
        }
        activeInProcess = foregroundReady
        releaseLaunchReservation()
        if (!foregroundReady) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        releaseLaunchReservation()
        if (!foregroundReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_BOOT_ROOT || !AutoRootPreferences.isEnabled(this)) {
            if (shouldStopAfterRejectedStart(running.get())) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: RuntimeException) {
                }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        activeInProcess = true

        if (AutoRootPreferences.currentBootId() == null) {
            appendAutoLog("[ERROR] boot_id unavailable")
            finishService(
                "Automatic root stopped",
                "The boot identifier is unavailable.",
                false
            )
            return START_NOT_STICKY
        }

        if (!AutoRootPreferences.claimCurrentBoot(this)) {
            val existingAttempt = AutoRootPreferences.currentAttempt(this)
            if (existingAttempt?.status == AutoRootPreferences.STATUS_RUNNING) {
                val interruptionStatus = AutoRootPreferences.interruptionStatus(
                    exploitRecorded = AutoRootPreferences.currentExploitAttempt(this) != null,
                    moduleLoaded = RootFlow.isModuleLoaded()
                )
                val recovered = AutoRootPreferences.markInterruptedIfRunning(
                    this,
                    "orphaned automatic setup recovered after service restart",
                    interruptionStatus
                )
                if (recovered) {
                    appendAutoLog("[RECOVERY] An interrupted automatic setup was closed safely.")
                    finishService(
                        "Automatic setup interrupted",
                        if (interruptionStatus == AutoRootPreferences.STATUS_REBOOT_REQUIRED) {
                            "Reboot before trying root again."
                        } else {
                            "Open SCRoot to verify or repair setup."
                        },
                        false
                    )
                } else {
                    appendAutoLog("[ERROR] The interrupted automatic setup state could not be saved.")
                    finishService(
                        "Automatic setup state unavailable",
                        "Open SCRoot to verify the current state.",
                        false
                    )
                }
            } else if (existingAttempt != null) {
                appendAutoLog("[SKIP] Automatic setup was already handled for this boot.")
                finishService(
                    "Automatic setup skipped",
                    "Automatic setup runs only once per boot.",
                    false,
                    retainNotification = false
                )
            } else {
                appendAutoLog("[ERROR] The automatic setup state could not be created.")
                finishService(
                    "Automatic setup state unavailable",
                    "Open SCRoot to verify storage and boot state.",
                    false
                )
            }
            return START_NOT_STICKY
        }

        beginAutoLog()
        BootTraceBus.begin()
        try {
            showBootTrace()
        } catch (_: RuntimeException) {
            appendAutoLog("[WARNING] The live trace notification could not be shown.")
        }
        if (!acquireWakeLock()) {
            AutoRootPreferences.finishCurrentBoot(
                this,
                AutoRootPreferences.STATUS_SAFE_FAILURE,
                "wake lock unavailable"
            )
            appendAutoLog("[ERROR] The boot setup wake lock is unavailable.")
            BootTraceBus.complete(
                success = false,
                completionDetail = "Automatic setup could not keep the device awake"
            )
            finishService(
                "Automatic root stopped",
                "Open SCRoot and try setup again.",
                false
            )
            return START_NOT_STICKY
        }
        try {
            workerFuture = workerExecutor.submit {
                runAutoRoot()
            }
        } catch (_: RuntimeException) {
            AutoRootPreferences.finishCurrentBoot(
                this,
                AutoRootPreferences.STATUS_SAFE_FAILURE,
                "automatic worker unavailable"
            )
            appendAutoLog("[ERROR] The automatic setup worker could not start.")
            BootTraceBus.complete(
                success = false,
                completionDetail = "Automatic setup worker could not start"
            )
            finishService(
                "Automatic root stopped",
                "Open SCRoot and try setup again.",
                false
            )
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroying.set(true)
        val wasRunning = running.getAndSet(false)
        try {
            if (wasRunning) {
                workerFuture?.cancel(true)
                workerExecutor.shutdownNow()
            } else {
                workerExecutor.shutdown()
            }
            try {
                getSystemService(NotificationManager::class.java)
                    .cancel(TRACE_LAUNCH_NOTIFICATION_ID)
            } catch (_: RuntimeException) {
            }
            if (wasRunning) {
                val interruptionStatus = AutoRootPreferences.interruptionStatus(
                    exploitRecorded = AutoRootPreferences.currentExploitAttempt(this) != null,
                    moduleLoaded = RootFlow.isModuleLoaded()
                )
                val interruptionRecorded = AutoRootPreferences.markInterruptedIfRunning(
                    this,
                    "automatic service stopped before completion",
                    interruptionStatus
                )
                val finalAttempt = AutoRootPreferences.currentAttempt(this)
                val completedSuccessfully =
                    !interruptionRecorded &&
                        finalAttempt?.status == AutoRootPreferences.STATUS_SUCCESS
                BootTraceBus.complete(
                    success = completedSuccessfully,
                    completionDetail = if (completedSuccessfully) {
                        "Root, KernelSU and system UI are ready"
                    } else {
                        finalAttempt?.detail?.ifBlank {
                            "Automatic setup stopped before completion"
                        } ?: "Automatic setup stopped before completion"
                    }
                )
            }
        } finally {
            foregroundReady = false
            activeInProcess = false
            releaseLaunchReservation()
            releaseWakeLock()
            super.onDestroy()
        }
    }

    private fun runAutoRoot() {
        try {
            appendAutoLog("[START] Automatic boot root started")
            updateNotification(
                "Automatic root running",
                "Checking safety conditions.",
                true
            )
            Thread.sleep(1_500L)
            val result = RootFlow.run(
                applicationContext,
                maxExploitTries = 1,
                ui = { line ->
                    appendAutoLog(line)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotificationAt >= 1_500L) {
                        lastNotificationAt = now
                        updateNotification(
                            "Automatic root running",
                            notificationDetail(line),
                            true
                        )
                    }
                },
                launchManager = false,
                executionMode = RootFlow.ExecutionMode.AUTO
            )
            if (destroying.get()) return
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val complete = result.rooted &&
                result.moduleLoaded &&
                result.managerCrowned &&
                result.userspaceReady &&
                result.systemUiIntegrated
            when {
                complete -> {
                    if (!persistAutomaticOutcome(
                            AutoRootPreferences.STATUS_SUCCESS,
                            "KernelSU and SCR-01 system UI setup complete"
                        )
                    ) return
                    appendAutoLog("[OK] KernelSU and SCR-01 system UI setup complete")
                    BootTraceBus.complete(
                        success = true,
                        completionDetail = "Root, KernelSU and system UI are ready"
                    )
                    finishService(
                        "Automatic root complete",
                        "Root, KernelSU and the SCR-01 system UI are ready.",
                        false
                    )
                }
                !result.rooted && result.exploitAttempted && !result.moduleLoaded -> {
                    if (!persistAutomaticOutcome(
                            AutoRootPreferences.STATUS_REBOOT_REQUIRED,
                            "Exploit failed; reboot required"
                        )
                    ) return
                    appendAutoLog("[FAILED] Automatic and manual retries blocked until reboot")
                    BootTraceBus.complete(
                        success = false,
                        completionDetail = "Exploit stopped; reboot required"
                    )
                    finishService(
                        "Automatic root failed",
                        "Retries are blocked until reboot for safety.",
                        false
                    )
                }
                result.rooted && !result.moduleLoaded -> {
                    if (!persistAutomaticOutcome(
                            AutoRootPreferences.STATUS_REBOOT_REQUIRED,
                            "Root hook active without a verified KernelSU module; reboot required"
                        )
                    ) return
                    appendAutoLog("[FAILED] Root is active without a verified KernelSU module")
                    appendAutoLog("[CAUTION] Reboot is required to clear temporary kernel state")
                    BootTraceBus.complete(
                        success = false,
                        completionDetail = "Unsafe partial root state; reboot required"
                    )
                    finishService(
                        "Reboot required",
                        "KernelSU was not verified. Reboot before trying again.",
                        false
                    )
                }
                result.rooted && result.moduleLoaded && result.userspaceReady &&
                    result.managerCrowned && !result.systemUiIntegrated -> {
                    if (!persistAutomaticOutcome(
                            AutoRootPreferences.STATUS_SAFE_FAILURE,
                            "Launcher and SystemUI integration needs repair"
                        )
                    ) return
                    appendAutoLog("[FAILED] Launcher and SystemUI integration needs repair")
                    BootTraceBus.complete(
                        success = false,
                        completionDetail = "Root is active; launcher integration needs repair"
                    )
                    finishService(
                        "Launcher setup needs repair",
                        "Open SCRoot to repair the Apps screen and Root menu.",
                        false
                    )
                }
                else -> {
                    if (!persistAutomaticOutcome(
                            AutoRootPreferences.STATUS_SAFE_FAILURE,
                            "Userspace setup or safety conditions need attention"
                        )
                    ) return
                    appendAutoLog("[FAILED] Userspace setup or safety conditions need attention")
                    BootTraceBus.complete(
                        success = false,
                        completionDetail = "Userspace setup needs attention"
                    )
                    finishService(
                        "Automatic setup needs attention",
                        "Open SCRoot to review the detailed trace.",
                        false
                    )
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!destroying.get()) {
                val moduleLive = RootFlow.isModuleLoaded()
                val exploitRecorded = AutoRootPreferences.currentExploitAttempt(this) != null
                val status = AutoRootPreferences.interruptionStatus(
                    exploitRecorded = exploitRecorded,
                    moduleLoaded = moduleLive
                )
                if (!persistAutomaticOutcome(status, "automatic setup interrupted")) return
                appendAutoLog("[ERROR] Automatic setup interrupted")
                BootTraceBus.complete(
                    success = false,
                    completionDetail = "Automatic setup interrupted"
                )
                finishService(
                    "Automatic root stopped",
                    if (moduleLive || !exploitRecorded) {
                        "Check the kernel module state."
                    } else {
                        "Reboot the device for safety."
                    },
                    false
                )
            }
        } catch (error: Exception) {
            if (!destroying.get()) {
                val moduleLive = RootFlow.isModuleLoaded()
                val exploitRecorded = AutoRootPreferences.currentExploitAttempt(this) != null
                val status = AutoRootPreferences.interruptionStatus(
                    exploitRecorded = exploitRecorded,
                    moduleLoaded = moduleLive
                )
                if (!persistAutomaticOutcome(
                        status,
                        error.message ?: error.javaClass.simpleName
                    )
                ) return
                appendAutoLog("[ERROR] ${error.message ?: error.javaClass.simpleName}")
                BootTraceBus.complete(
                    success = false,
                    completionDetail = error.message ?: "Automatic setup stopped"
                )
                finishService(
                    "Automatic root stopped",
                    if (moduleLive || !exploitRecorded) {
                        "Check the kernel module state."
                    } else {
                        "Reboot the device for safety."
                    },
                    false
                )
            }
        }
    }

    private fun persistAutomaticOutcome(status: String, detail: String): Boolean {
        if (destroying.get()) return false
        if (AutoRootPreferences.finishCurrentBoot(this, status, detail)) return true
        if (destroying.get()) return false
        appendAutoLog("[ERROR] The automatic setup result could not be saved.")
        BootTraceBus.complete(
            success = false,
            completionDetail = "Automatic setup state could not be saved"
        )
        finishService(
            "Automatic setup state unavailable",
            "Open SCRoot to verify the current state.",
            false
        )
        return false
    }

    @Synchronized
    private fun beginAutoLog() {
        try {
            val log = File(filesDir, "auto-root.log")
            val previous = File(filesDir, "auto-root.log.1")
            if (previous.exists()) previous.delete()
            if (log.exists() && !log.renameTo(previous)) {
                log.writeText("")
            }
            if (!log.exists()) log.createNewFile()
        } catch (_: Exception) {

        }
    }

    @Synchronized
    private fun appendAutoLog(message: String) {
        val boundedMessage = boundedAutoLogMessage(message)
        try {
            BootTraceBus.emit(boundedMessage)
        } catch (_: RuntimeException) {
        }
        try {
            val log = File(filesDir, "auto-root.log")
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date())
            val encoded = "$timestamp $boundedMessage\n".toByteArray(Charsets.UTF_8)
            if (autoLogNeedsRotation(log.length(), encoded.size)) {
                val previous = File(filesDir, "auto-root.log.1")
                if (previous.exists()) previous.delete()
                if (!log.renameTo(previous)) FileOutputStream(log, false).use { }
            }
            FileOutputStream(log, true).use { output ->
                output.write(encoded)
            }
        } catch (_: Exception) {

        }
    }

    private fun notificationDetail(line: String): String {
        val clean = line.take(512).trim()
            .replace(Regex("\\s+"), " ")
            .take(90)
        return clean.ifBlank {
            "Automatic setup is in progress."
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automatic boot root trace",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "SCRoot automatic exploit and KernelSU setup"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(
        title: String,
        detail: String,
        ongoing: Boolean,
        openTrace: Boolean = ongoing,
        fullScreenTrace: Boolean = false
    ): Notification {
        val openApp = if (openTrace) {
            tracePendingIntent()
        } else {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setCategory(
                if (fullScreenTrace) Notification.CATEGORY_ALARM
                else Notification.CATEGORY_PROGRESS
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (fullScreenTrace) {

                    setFullScreenIntent(tracePendingIntent(), true)
                }
            }
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun updateNotification(title: String, detail: String, ongoing: Boolean) {
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(title, detail, ongoing, openTrace = ongoing)
            )
        } catch (_: RuntimeException) {
        }
    }

    private fun showBootTrace() {

        getSystemService(NotificationManager::class.java).notify(
            TRACE_LAUNCH_NOTIFICATION_ID,
            notification(
                "Automatic root running",
                "Opening the live exploit trace.",
                ongoing = false,
                openTrace = true,
                fullScreenTrace = true
            )
        )
    }

    private fun traceIntent(): Intent =
        Intent(this, BootTraceActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

    private fun tracePendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            1,
            traceIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun finishService(
        title: String,
        detail: String,
        ongoing: Boolean,
        retainNotification: Boolean = true
    ) {
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(TRACE_LAUNCH_NOTIFICATION_ID)
        } catch (_: RuntimeException) {
        }
        if (retainNotification) {
            try {
                updateNotification(title, detail, ongoing)
            } catch (_: RuntimeException) {
            }
        }
        releaseWakeLock()
        running.set(false)
        activeInProcess = false
        try {
            stopForeground(
                if (retainNotification) {
                    STOP_FOREGROUND_DETACH
                } else {
                    STOP_FOREGROUND_REMOVE
                }
            )
        } catch (_: RuntimeException) {
        }
        if (!retainNotification) {
            try {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } catch (_: RuntimeException) {
            }
        }
        stopSelf()
    }

    private fun acquireWakeLock(): Boolean = try {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AutoRoot"
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
