package com.scr01.scroot

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

object AutoRootPreferences {
    private const val PREFS_NAME = "auto_root"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BOOT_ID = "attempt_boot_id"
    private const val KEY_STATUS = "attempt_status"
    private const val KEY_DETAIL = "attempt_detail"
    private const val KEY_STARTED_AT = "attempt_started_at"
    private const val KEY_FINISHED_AT = "attempt_finished_at"
    private const val KEY_EXPLOIT_BOOT_ID = "exploit_boot_id"
    private const val KEY_EXPLOIT_STATUS = "exploit_status"
    private const val KEY_EXPLOIT_DETAIL = "exploit_detail"
    private const val KEY_EXPLOIT_STARTED_AT = "exploit_started_at"
    private const val KEY_EXPLOIT_FINISHED_AT = "exploit_finished_at"
    private const val KEY_TRACE_BOOT_ID = "trace_boot_id"
    private const val KEY_TRACE_CREATED_AT = "trace_created_at"
    private const val KEY_TRACE_CREATED_COUNT = "trace_created_count"
    private const val KEY_TRACE_VISIBLE_AT = "trace_visible_at"
    private const val KEY_TRACE_VISIBLE_COUNT = "trace_visible_count"
    private const val SHARED_LOCK_PREFIX = "native-attempt-"
    private const val MAX_DETAIL_CHARS = 512
    private val BOOT_ID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val SHARED_LOCK_NAME_PATTERN =
        Regex(
            "^native-attempt-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.lock$"
        )

    const val STATUS_RUNNING = "running"
    const val STATUS_SUCCESS = "success"
    const val STATUS_SAFE_FAILURE = "safe_failure"
    const val STATUS_REBOOT_REQUIRED = "reboot_required"

    data class Attempt(
        val status: String,
        val detail: String
    )

    data class TraceReceipt(
        val createdAt: Long,
        val createdCount: Int,
        val visibleAt: Long,
        val visibleCount: Int
    )

    internal fun boundedDetail(value: String): String {
        val truncated = value.length > MAX_DETAIL_CHARS
        val sanitized = buildString(minOf(value.length, MAX_DETAIL_CHARS)) {
            value.take(MAX_DETAIL_CHARS).forEach { character ->
                append(
                    if (character.code in 0x00..0x1f || character.code == 0x7f) {
                        ' '
                    } else {
                        character
                    }
                )
            }
        }.trim()
        return if (truncated) sanitized.take(MAX_DETAIL_CHARS - 1) + "…" else sanitized
    }

    internal fun validBootId(value: String): Boolean = BOOT_ID_PATTERN.matches(value)

    internal fun incrementReceiptCount(value: Int): Int = when {
        value < 0 -> 1
        value == Int.MAX_VALUE -> Int.MAX_VALUE
        else -> value + 1
    }

    private fun isTerminalStatus(status: String): Boolean =
        status == STATUS_SUCCESS ||
            status == STATUS_SAFE_FAILURE ||
            status == STATUS_REBOOT_REQUIRED

    internal fun automaticFinishAllowed(currentStatus: String?, requestedStatus: String): Boolean =
        currentStatus == STATUS_RUNNING && isTerminalStatus(requestedStatus)

    internal fun exploitFinishAllowed(currentStatus: String?, requestedStatus: String): Boolean =
        currentStatus == STATUS_RUNNING &&
            (requestedStatus == STATUS_SUCCESS || requestedStatus == STATUS_REBOOT_REQUIRED)

    internal fun normalizedAttemptStatus(status: String?): String? = when {
        status == STATUS_RUNNING || status?.let(::isTerminalStatus) == true -> status
        status == null -> null
        else -> STATUS_SAFE_FAILURE
    }

    internal fun normalizedExploitStatus(status: String?): String? = when (status) {
        STATUS_RUNNING, STATUS_SUCCESS, STATUS_REBOOT_REQUIRED -> status
        null -> null
        else -> STATUS_REBOOT_REQUIRED
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sharedExploitLock(context: Context, bootId: String): File? =
        context.applicationContext.getExternalFilesDir(null)?.let { root ->
            File(root, "$SHARED_LOCK_PREFIX$bootId.lock")
        }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    @SuppressLint("ApplySharedPref")
    fun setEnabled(context: Context, enabled: Boolean): Boolean {

        return prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun currentBootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id")
            .readText()
            .trim()
            .takeIf(::validBootId)
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun claimCurrentBoot(context: Context): Boolean {
        val bootId = currentBootId() ?: return false
        val preferences = prefs(context)
        if (preferences.getString(KEY_BOOT_ID, null) == bootId) return false
        return preferences.edit()
            .putString(KEY_BOOT_ID, bootId)
            .putString(KEY_STATUS, STATUS_RUNNING)
            .putString(KEY_DETAIL, "auto_root_started")
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_FINISHED_AT)
            .remove(KEY_TRACE_BOOT_ID)
            .remove(KEY_TRACE_CREATED_AT)
            .remove(KEY_TRACE_CREATED_COUNT)
            .remove(KEY_TRACE_VISIBLE_AT)
            .remove(KEY_TRACE_VISIBLE_COUNT)
            .commit()
    }

    @Synchronized
    fun recordTraceCreated(context: Context): Int {
        val bootId = currentBootId() ?: return 0
        val preferences = prefs(context)
        val sameBoot = preferences.getString(KEY_TRACE_BOOT_ID, null) == bootId
        val count = if (sameBoot) {
            incrementReceiptCount(preferences.getInt(KEY_TRACE_CREATED_COUNT, 0))
        } else {
            1
        }
        val editor = preferences.edit()
            .putString(KEY_TRACE_BOOT_ID, bootId)
            .putLong(KEY_TRACE_CREATED_AT, System.currentTimeMillis())
            .putInt(KEY_TRACE_CREATED_COUNT, count)
        if (!sameBoot) {
            editor.remove(KEY_TRACE_VISIBLE_AT)
                .remove(KEY_TRACE_VISIBLE_COUNT)
        }
        return if (editor.commit()) count else 0
    }

    @Synchronized
    fun recordTraceVisible(context: Context): Int {
        val bootId = currentBootId() ?: return 0
        val preferences = prefs(context)
        val sameBoot = preferences.getString(KEY_TRACE_BOOT_ID, null) == bootId
        val count = if (sameBoot) {
            incrementReceiptCount(preferences.getInt(KEY_TRACE_VISIBLE_COUNT, 0))
        } else {
            1
        }
        val editor = preferences.edit()
            .putString(KEY_TRACE_BOOT_ID, bootId)
            .putLong(KEY_TRACE_VISIBLE_AT, System.currentTimeMillis())
            .putInt(KEY_TRACE_VISIBLE_COUNT, count)
        if (!sameBoot) {
            editor.remove(KEY_TRACE_CREATED_AT)
                .remove(KEY_TRACE_CREATED_COUNT)
        }
        return if (editor.commit()) count else 0
    }

    fun currentTraceReceipt(context: Context): TraceReceipt? {
        val bootId = currentBootId() ?: return null
        val preferences = prefs(context)
        if (preferences.getString(KEY_TRACE_BOOT_ID, null) != bootId) return null
        return TraceReceipt(
            createdAt = preferences.getLong(KEY_TRACE_CREATED_AT, 0L).coerceAtLeast(0L),
            createdCount = preferences.getInt(KEY_TRACE_CREATED_COUNT, 0).coerceAtLeast(0),
            visibleAt = preferences.getLong(KEY_TRACE_VISIBLE_AT, 0L).coerceAtLeast(0L),
            visibleCount = preferences.getInt(KEY_TRACE_VISIBLE_COUNT, 0).coerceAtLeast(0)
        )
    }

    @Synchronized
    fun finishCurrentBoot(context: Context, status: String, detail: String): Boolean {
        val bootId = currentBootId() ?: return false
        val preferences = prefs(context)
        if (preferences.getString(KEY_BOOT_ID, null) != bootId) return false
        if (!automaticFinishAllowed(preferences.getString(KEY_STATUS, null), status)) return false

        return preferences.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, boundedDetail(detail))
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .commit()
    }

    @Synchronized
    fun markInterruptedIfRunning(
        context: Context,
        detail: String,
        status: String = STATUS_REBOOT_REQUIRED
    ): Boolean {
        val bootId = currentBootId() ?: return false
        val preferences = prefs(context)
        if (preferences.getString(KEY_BOOT_ID, null) != bootId) return false
        if (preferences.getString(KEY_STATUS, null) != STATUS_RUNNING) return false
        if (status != STATUS_SAFE_FAILURE && status != STATUS_REBOOT_REQUIRED) return false
        return preferences.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, boundedDetail(detail))
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .commit()
    }

    fun currentAttempt(context: Context): Attempt? {
        val bootId = currentBootId() ?: return null
        val preferences = prefs(context)
        if (preferences.getString(KEY_BOOT_ID, null) != bootId) return null
        val storedStatus = preferences.getString(KEY_STATUS, null)
        val status = normalizedAttemptStatus(storedStatus) ?: return null
        return Attempt(
            status = status,
            detail = if (status == storedStatus) {
                boundedDetail(preferences.getString(KEY_DETAIL, "") ?: "")
            } else {
                "invalid persisted attempt status"
            }
        )
    }

    internal fun automaticAttemptIsOrphaned(
        status: String?,
        pipelineActive: Boolean
    ): Boolean = status == STATUS_RUNNING && !pipelineActive

    private fun pruneOldSharedExploitLocks(root: File, bootId: String) {
        val currentName = "$SHARED_LOCK_PREFIX$bootId.lock"
        try {
            root.listFiles()?.forEach { candidate ->
                if (candidate.isDirectory && candidate.name != currentName &&
                    SHARED_LOCK_NAME_PATTERN.matches(candidate.name)
                ) {
                    candidate.delete()
                }
            }
        } catch (_: SecurityException) {
        }
    }

    @Synchronized
    fun claimExploitForCurrentBoot(context: Context): Boolean {
        val bootId = currentBootId() ?: return false
        val preferences = prefs(context)
        if (preferences.getString(KEY_EXPLOIT_BOOT_ID, null) == bootId) return false

        val sharedRoot = context.getExternalFilesDir(null) ?: return false
        if (!sharedRoot.exists() && !sharedRoot.mkdirs()) return false
        pruneOldSharedExploitLocks(sharedRoot, bootId)
        val sharedLock = sharedExploitLock(context, bootId) ?: return false
        if (!sharedLock.mkdir()) return false
        val committed = preferences.edit()
            .putString(KEY_EXPLOIT_BOOT_ID, bootId)
            .putString(KEY_EXPLOIT_STATUS, STATUS_RUNNING)
            .putString(KEY_EXPLOIT_DETAIL, "native_mali_started")
            .putLong(KEY_EXPLOIT_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_EXPLOIT_FINISHED_AT)
            .commit()
        if (!committed) {

            sharedLock.delete()
        }
        return committed
    }

    @Synchronized
    fun finishExploitForCurrentBoot(
        context: Context,
        status: String,
        detail: String
    ): Boolean {
        val bootId = currentBootId() ?: return false
        val preferences = prefs(context)
        if (preferences.getString(KEY_EXPLOIT_BOOT_ID, null) != bootId) return false
        if (!exploitFinishAllowed(
                preferences.getString(KEY_EXPLOIT_STATUS, null),
                status
            )
        ) return false
        return preferences.edit()
            .putString(KEY_EXPLOIT_STATUS, status)
            .putString(KEY_EXPLOIT_DETAIL, boundedDetail(detail))
            .putLong(KEY_EXPLOIT_FINISHED_AT, System.currentTimeMillis())
            .commit()
    }

    fun currentExploitAttempt(context: Context): Attempt? {
        val bootId = currentBootId() ?: return null
        val preferences = prefs(context)
        if (preferences.getString(KEY_EXPLOIT_BOOT_ID, null) == bootId) {
            val storedStatus = preferences.getString(KEY_EXPLOIT_STATUS, null)
            val status = normalizedExploitStatus(storedStatus)
            if (status != null) {
                return Attempt(
                    status = status,
                    detail = if (status == storedStatus) {
                        boundedDetail(
                            preferences.getString(KEY_EXPLOIT_DETAIL, "") ?: ""
                        )
                    } else {
                        "invalid persisted exploit status"
                    }
                )
            }
        }

        return if (sharedExploitLock(context, bootId)?.isDirectory == true) {
            Attempt(
                status = STATUS_REBOOT_REQUIRED,
                detail = "shared native exploit lock is present"
            )
        } else {
            null
        }
    }

    internal fun interruptionStatus(
        exploitRecorded: Boolean,
        moduleLoaded: Boolean
    ): String = if (exploitRecorded && !moduleLoaded) {
        STATUS_REBOOT_REQUIRED
    } else {
        STATUS_SAFE_FAILURE
    }
}
