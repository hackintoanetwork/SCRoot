package com.scr01.scroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRootReceiver : BroadcastReceiver() {
    companion object {
        internal fun handlesBootAction(action: String?): Boolean =
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_BOOT_COMPLETED

        internal fun serviceActionForBoot(
            action: String?,
            uiDeferred: Boolean,
            userUnlocked: Boolean
        ): String? = when {
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                AutoRootService.ACTION_BOOT_ROOT
            action == Intent.ACTION_BOOT_COMPLETED && uiDeferred && userUnlocked ->
                AutoRootService.ACTION_BOOT_UI
            action == Intent.ACTION_BOOT_COMPLETED && uiDeferred -> null
            action == Intent.ACTION_BOOT_COMPLETED -> AutoRootService.ACTION_BOOT_ROOT
            else -> null
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!handlesBootAction(intent?.action)) return
        if (!AutoRootPreferences.isEnabled(context)) return
        val serviceAction = serviceActionForBoot(
            intent?.action,
            AutoRootPreferences.isUiDeferredForCurrentBoot(context),
            RootFlow.isUserUnlockedForSystemUi(context)
        ) ?: return
        if (!AutoRootService.reserveLaunch()) return

        val service = Intent(context, AutoRootService::class.java)
            .setAction(serviceAction)
        try {
            context.startForegroundService(service)
        } catch (error: RuntimeException) {
            AutoRootService.releaseLaunchReservation()

            val detail = "automatic service launch rejected: " +
                (error.message ?: error.javaClass.simpleName)
            if (AutoRootPreferences.claimCurrentBoot(context)) {
                AutoRootPreferences.finishCurrentBoot(
                    context,
                    AutoRootPreferences.STATUS_SAFE_FAILURE,
                    detail
                )
            } else {
                val interruptionStatus = AutoRootPreferences.interruptionStatus(
                    exploitRecorded =
                        AutoRootPreferences.currentExploitAttempt(context) != null,
                    moduleLoaded = RootFlow.isModuleLoaded()
                )
                AutoRootPreferences.markInterruptedIfRunning(
                    context,
                    detail,
                    interruptionStatus
                )
            }
            Log.e("SCRoot", detail, error)
        }
    }
}
