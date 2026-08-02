package com.scr01.scroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AutoRootPreferences.isEnabled(context)) return
        if (!AutoRootService.reserveLaunch()) return

        val service = Intent(context, AutoRootService::class.java)
            .setAction(AutoRootService.ACTION_BOOT_ROOT)
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
