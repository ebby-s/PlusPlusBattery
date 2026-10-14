package com.dijia1124.plusplusbattery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.dijia1124.plusplusbattery.MainActivity
import com.dijia1124.plusplusbattery.R
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import com.dijia1124.plusplusbattery.data.repository.ChargeEventRepository
import com.dijia1124.plusplusbattery.data.repository.PrefsRepository
import com.dijia1124.plusplusbattery.data.util.BMS_PATH
import com.dijia1124.plusplusbattery.data.util.readChargeMah
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Always-on service that records charge and discharge sessions.
 *
 * Power strategy: fully event driven (battery/screen/power broadcasts) plus a slow
 * safety tick (60s screen-on / 5min screen-off) that holds no wakelock, so it simply
 * does not run while the CPU is suspended. Flash writes are throttled.
 *
 * Session coalescing: brief opposite-state blips (for example a charge-limit
 * controller flickering between charging and not-charging at the top of the range)
 * do not split a session. A new session only begins when the opposite state is
 * genuinely sustained, or when tracking resumes after a long gap with a materially
 * different charge level/counter.
 */
class BatteryTrackerService : Service() {

    companion object {
        const val ACTION_START = "com.dijia1124.plusplusbattery.action.START_TRACKING"
        const val ACTION_STOP = "com.dijia1124.plusplusbattery.action.STOP_TRACKING"
        private const val CHANNEL_ID = "battery_tracking"
        private const val NOTIF_ID = 1002
        private const val DEFAULT_INTERVAL_MS = 60_000L
        private const val SCREEN_OFF_MULTIPLIER = 5
        // Throttle: ignore bursts of battery broadcasts and limit flash writes.
        private const val MIN_SAMPLE_GAP_MS = 2_000L
        private const val PERSIST_INTERVAL_MS = 15_000L
        // A flip counts as a real boundary once the charge level has moved in the
        // new direction, or enough charge has moved, or it has persisted far too
        // long to still be a blip.
        private const val FLIP_MIN_LEVEL_DELTA = 2
        private const val FLIP_MIN_MAH_UAH = 50_000L
        private const val FLIP_MAX_MS = 10 * 60_000L
        // On restart, resume the session unless it has been idle this long AND the
        // level/counter moved materially.
        private const val LONG_GAP_MS = 30 * 60_000L
        private const val LONG_GAP_LEVEL_DELTA = 3
        private const val LONG_GAP_MAH_UAH = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var batteryManager: BatteryManager
    private lateinit var eventRepo: ChargeEventRepository
    private lateinit var prefsRepo: PrefsRepository
    private lateinit var notificationManager: NotificationManager

    private var tickJob: Job? = null

    @Volatile private var ongoing: ChargeEvent? = null
    @Volatile private var screenOnSinceElapsed: Long? = null
    @Volatile private var isScreenOn = true
    private var lastElapsedMs = 0L
    private var lastUptimeMs = 0L
    private var lastCurrentUa = 0L
    private var lastSampleInvokeMs = 0L
    private var lastPersistElapsedMs = 0L
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var designCapacityMah: Int? = null
    private var designCapacityLoaded = false

    // Pending opposite-state observation, used to coalesce blips.
    private var pendingFlipElapsed: Long? = null
    private var pendingFlipWall: Long? = null
    private var pendingFlipLevel: Int? = null
    private var pendingFlipCounter: Long? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    scope.launch { onScreenOn() }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    scope.launch { onScreenOff() }
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            scope.launch { sample() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        eventRepo = ChargeEventRepository(applicationContext as android.app.Application)
        prefsRepo = PrefsRepository(applicationContext)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                finalizeOngoing()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.tracking_events)))

        scope.launch {
            intervalMs = prefsRepo.trackingInterval.first().toLong()
        }

        if (tickJob?.isActive != true) {
            tickJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        tickJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- loop

    private suspend fun runLoop() {
        restoreOngoing()
        sample()
        while (scope.isActive) {
            val interval = if (isScreenOn) intervalMs else intervalMs * SCREEN_OFF_MULTIPLIER
            delay(interval)
            sample()
        }
    }

    private suspend fun restoreOngoing() {
        val existing = eventRepo.getOngoingEvent() ?: return
        // Drop any orphaned in-progress rows left behind by an earlier crash.
        eventRepo.deleteIncompleteExcept(existing.uid)

        isScreenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val st = readState()
        val referenceWall = existing.lastSampleWallMs ?: existing.startTime
        val gap = nowWall - referenceWall
        val levelMoved = abs(st.level - existing.startLevel) >= LONG_GAP_LEVEL_DELTA
        val counterMoved = if (existing.startCounterUah != null && st.counterUah != null) {
            abs(st.counterUah - existing.startCounterUah) >= LONG_GAP_MAH_UAH
        } else false

        if (gap >= LONG_GAP_MS && (levelMoved || counterMoved)) {
            // Too much changed while we were away to be one continuous session.
            finalizeEvent(existing, referenceWall, existing.startLevel, existing.startCounterUah, nowElapsed)
            ongoing = null
            startEvent(st, nowWall, nowElapsed, SystemClock.uptimeMillis())
            return
        }

        ongoing = existing
        screenOnSinceElapsed = if (isScreenOn) nowElapsed else null
        // We cannot recover time deltas across a reboot; start fresh from now.
        lastElapsedMs = nowElapsed
        lastUptimeMs = SystemClock.uptimeMillis()
        lastCurrentUa = st.currentUa
    }

    // ---------------------------------------------------------------- sampling

    private suspend fun sample() {
        val invokeNow = SystemClock.elapsedRealtime()
        if (invokeNow - lastSampleInvokeMs < MIN_SAMPLE_GAP_MS) return
        lastSampleInvokeMs = invokeNow

        val st = readState()
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val nowWall = System.currentTimeMillis()

        val ev = ongoing
        if (ev == null) {
            startEvent(st, nowWall, nowElapsed, nowUptime)
            return
        }

        val dtElapsed = (nowElapsed - lastElapsedMs).coerceAtLeast(0L)
        val dtUptime = (nowUptime - lastUptimeMs).coerceAtLeast(0L)
        val deepSleepDelta = (dtElapsed - dtUptime).coerceAtLeast(0L)
        val integratedDelta = if (dtElapsed > 0L) {
            (lastCurrentUa + st.currentUa) / 2.0 * dtElapsed / 3_600_000.0
        } else 0.0

        val updated = ev.copy(
            deepSleepMs = ev.deepSleepMs + deepSleepDelta,
            integratedUah = (ev.integratedUah ?: 0L) + integratedDelta.toLong(),
            lastSampleElapsedMs = nowElapsed,
            lastSampleUptimeMs = nowUptime,
            lastSampleWallMs = nowWall
        )

        lastElapsedMs = nowElapsed
        lastUptimeMs = nowUptime
        lastCurrentUa = st.currentUa

        val evCharging = updated.type == ChargeEvent.TYPE_CHARGE
        if (st.charging == evCharging) {
            // Same direction: continue the session and clear any pending flip.
            clearPendingFlip()
            ongoing = updated
            persistThrottled(updated, nowElapsed)
            return
        }

        // Opposite state observed.
        if (pendingFlipElapsed == null) {
            pendingFlipElapsed = nowElapsed
            pendingFlipWall = nowWall
            pendingFlipLevel = st.level
            pendingFlipCounter = st.counterUah
        }
        val persisted = nowElapsed - (pendingFlipElapsed ?: nowElapsed)
        val flipLevel = pendingFlipLevel ?: st.level
        val levelDelta = if (st.charging) st.level - flipLevel else flipLevel - st.level
        val hasCounter = pendingFlipCounter != null && st.counterUah != null
        val movedUah = if (hasCounter) {
            abs(st.counterUah!! - pendingFlipCounter!!)
        } else 0L
        val significant = levelDelta >= FLIP_MIN_LEVEL_DELTA ||
            movedUah >= FLIP_MIN_MAH_UAH ||
            persisted >= FLIP_MAX_MS

        if (significant) {
            // Genuine boundary: close the session at the moment the flip began and
            // open the new one there.
            val endWall = pendingFlipWall ?: nowWall
            val endLevel = pendingFlipLevel ?: st.level
            val endCounter = pendingFlipCounter
            val endElapsed = pendingFlipElapsed ?: nowElapsed
            val finished = finalizeEvent(updated, endWall, endLevel, endCounter, endElapsed)
            eventRepo.updateEvent(finished)
            ongoing = null
            clearPendingFlip()
            startEvent(
                charging = st.charging,
                level = endLevel,
                counterUah = endCounter,
                currentUa = st.currentUa,
                startWall = endWall,
                startElapsed = endElapsed,
                startUptime = nowUptime
            )
        } else {
            // Blip: keep accumulating into the current session.
            ongoing = updated
            persistThrottled(updated, nowElapsed)
        }
    }

    private fun clearPendingFlip() {
        pendingFlipElapsed = null
        pendingFlipWall = null
        pendingFlipLevel = null
        pendingFlipCounter = null
    }

    private suspend fun persistThrottled(ev: ChargeEvent, nowElapsed: Long) {
        if (nowElapsed - lastPersistElapsedMs >= PERSIST_INTERVAL_MS) {
            lastPersistElapsedMs = nowElapsed
            eventRepo.updateEvent(ev)
            updateNotification(ev)
        }
    }

    private suspend fun startEvent(
        st: BatteryState,
        startWall: Long,
        startElapsed: Long,
        startUptime: Long
    ) = startEvent(
        charging = st.charging,
        level = st.level,
        counterUah = st.counterUah,
        currentUa = st.currentUa,
        startWall = startWall,
        startElapsed = startElapsed,
        startUptime = startUptime
    )

    private suspend fun startEvent(
        charging: Boolean,
        level: Int,
        counterUah: Long?,
        currentUa: Long,
        startWall: Long,
        startElapsed: Long,
        startUptime: Long
    ) {
        if (!designCapacityLoaded) {
            designCapacityMah = runCatching { readChargeMah("charge_full_design", BMS_PATH) }.getOrNull()
            designCapacityLoaded = true
        }
        val event = ChargeEvent(
            type = if (charging) ChargeEvent.TYPE_CHARGE else ChargeEvent.TYPE_DISCHARGE,
            startTime = startWall,
            startLevel = level,
            startCounterUah = counterUah,
            screenOnMs = 0,
            deepSleepMs = 0,
            integratedUah = 0,
            designCapacityMah = designCapacityMah,
            isComplete = false,
            lastSampleElapsedMs = startElapsed,
            lastSampleUptimeMs = startUptime,
            lastSampleWallMs = startWall
        )
        lastElapsedMs = startElapsed
        lastUptimeMs = startUptime
        lastCurrentUa = currentUa
        lastPersistElapsedMs = startElapsed
        screenOnSinceElapsed = if (isScreenOn) startElapsed else null
        val rowId = eventRepo.insertEvent(event)
        ongoing = event.copy(uid = rowId)
    }

    private fun finalizeEvent(
        ev: ChargeEvent,
        endWall: Long,
        endLevel: Int,
        endCounterUah: Long?,
        endElapsed: Long
    ): ChargeEvent {
        var screenOnMs = ev.screenOnMs
        screenOnSinceElapsed?.let { start ->
            if (endElapsed > start) screenOnMs += endElapsed - start
        }

        val duration = (endWall - ev.startTime).coerceAtLeast(0L)
        val deepSleep = ev.deepSleepMs.coerceIn(0L, duration)
        val screenOff = (duration - screenOnMs - deepSleep).coerceAtLeast(0L)
        val deltaLevel = endLevel - ev.startLevel

        val counterMah = counterDeltaMah(ev.startCounterUah, endCounterUah, ev.designCapacityMah)
        val integratedMah = (ev.integratedUah ?: 0L).let { if (it == 0L) null else abs(it) / 1000.0 }
        val mah: Double?
        val source: String?
        when {
            counterMah != null && counterMah > 0.0 -> { mah = counterMah; source = "counter" }
            integratedMah != null && integratedMah > 0.0 -> { mah = integratedMah; source = "integrated" }
            else -> { mah = null; source = null }
        }

        val wear = if (mah != null && ev.designCapacityMah != null && ev.designCapacityMah > 0) {
            mah / ev.designCapacityMah
        } else null

        return ev.copy(
            endTime = endWall,
            durationMs = duration,
            endLevel = endLevel,
            deltaLevel = deltaLevel,
            mah = mah,
            mahSource = source,
            screenOnMs = screenOnMs.coerceAtMost(duration),
            screenOffMs = screenOff,
            deepSleepMs = deepSleep,
            wearCycles = wear,
            endCounterUah = endCounterUah,
            lastSampleWallMs = endWall,
            isComplete = true
        )
    }

    private suspend fun finalizeOngoing() {
        val ev = ongoing ?: eventRepo.getOngoingEvent() ?: return
        val st = readState()
        val nowWall = System.currentTimeMillis()
        val finished = finalizeEvent(ev, nowWall, st.level, st.counterUah, SystemClock.elapsedRealtime())
        eventRepo.updateEvent(finished)
        ongoing = null
    }

    private fun counterDeltaMah(start: Long?, end: Long?, design: Int?): Double? {
        if (start == null || end == null || start <= 0L || end <= 0L) return null
        val delta = abs(end - start)
        if (delta == 0L) return null
        // Guard against counter resets/wraps.
        val cap = (design ?: 5000).toLong() * 4
        if (delta > cap * 1000) return null
        return delta / 1000.0
    }

    // ---------------------------------------------------------------- screen

    private suspend fun onScreenOn() {
        if (ongoing == null) { sample(); return }
        if (screenOnSinceElapsed == null) screenOnSinceElapsed = SystemClock.elapsedRealtime()
        sample()
    }

    private suspend fun onScreenOff() {
        val ev = ongoing
        screenOnSinceElapsed?.let { start ->
            val now = SystemClock.elapsedRealtime()
            if (ev != null && now > start) {
                val withScreen = ev.copy(screenOnMs = ev.screenOnMs + (now - start))
                ongoing = withScreen
                eventRepo.updateEvent(withScreen)
            }
        }
        screenOnSinceElapsed = null
        sample()
    }

    // ---------------------------------------------------------------- state

    private data class BatteryState(
        val level: Int,
        val charging: Boolean,
        val counterUah: Long?,
        val currentUa: Long
    )

    private fun readState(): BatteryState {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val counter = readChargeCounterUah()
        return BatteryState(level, charging, counter, readCurrentUa())
    }

    private fun readChargeCounterUah(): Long? {
        val v = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        return if (v > 0L) v else null
    }

    private fun readCurrentUa(): Long =
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.battery_tracking),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(chan)
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_battery_saver_24)
            .setContentTitle(getString(R.string.battery_tracking))
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(ev: ChargeEvent) {
        val label = if (ev.type == ChargeEvent.TYPE_CHARGE) {
            getString(R.string.charging)
        } else {
            getString(R.string.discharging)
        }
        notificationManager.notify(NOTIF_ID, buildNotification("$label: ${ev.startLevel}%"))
    }
}
