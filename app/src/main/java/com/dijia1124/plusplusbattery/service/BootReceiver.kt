package com.dijia1124.plusplusbattery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dijia1124.plusplusbattery.data.repository.PrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts battery tracking after a reboot when tracking is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PrefsRepository(context.applicationContext)
                if (prefs.trackingEnabled.first()) {
                    ContextCompat.startForegroundService(
                        context.applicationContext,
                        Intent(context.applicationContext, BatteryTrackerService::class.java)
                    )
                }
            } catch (_: Exception) {
                // ignore
            } finally {
                pending.finish()
            }
        }
    }
}
