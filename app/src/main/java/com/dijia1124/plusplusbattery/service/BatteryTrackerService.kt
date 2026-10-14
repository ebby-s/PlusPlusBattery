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
 * Design goals: accurate sessions with minimal power use.
 *  - Fully event driven: samples on ACTION_BATTERY_CHANGED, screen on/off and
 *    power connect/disconnect.
 *  - A slow safety-net tick (60s screen-on / 5min screen-off) that holds no
 *    wakelock, so it simply does not run while the CPU is suspended.
 *  - mAh is taken from the charge-counter delta (exact, sampling independent)
 *    with a current-integration fallback.
 *  - Deep sleep is derived from SystemClock.elapsedRealtime - uptimeMillis
 *    deltas, which stay correct across long suspended gaps.
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
        // Recover an in-progress session left over from a previous process/reboot.
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
        ongoing = existing
        isScreenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        screenOnSinceElapsed = if (isScreenOn) SystemClock.elapsedRealtime() else null
        // We cannot recover time deltas across a reboot; start fresh from now.
        lastElapsedMs = SystemClock.elapsedRealtime()
        lastUptimeMs = SystemClock.uptimeMillis()
        lastCurrentUa = readCurrentUa()
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
            lastSampleUptimeMs = nowUptime
        )

        lastElapsedMs = nowElapsed
        lastUptimeMs = nowUptime
        lastCurrentUa = st.currentUa

        val evCharging = updated.type == ChargeEvent.TYPE_CHARGE
        if (st.charging != evCharging) {
            val finished = finalizeEvent(updated, st, nowWall, nowElapsed)
            eventRepo.updateEvent(finished)
            ongoing = null
            startEvent(st, nowWall, nowElapsed, nowUptime)
        } else {
            ongoing = updated
            if (nowElapsed - lastPersistElapsedMs >= PERSIST_INTERVAL_MS) {
                lastPersistElapsedMs = nowElapsed
                eventRepo.updateEvent(updated)
                updateNotification(updated)
            }
        }
    }

    private suspend fun startEvent(
        st: BatteryState,
        nowWall: Long,
        nowElapsed: Long,
        nowUptime: Long
    ) {
        if (!designCapacityLoaded) {
            designCapacityMah = runCatching { readChargeMah("charge_full_design", BMS_PATH) }.getOrNull()
            designCapacityLoaded = true
        }
        val event = ChargeEvent(
            type = if (st.charging) ChargeEvent.TYPE_CHARGE else ChargeEvent.TYPE_DISCHARGE,
            startTime = nowWall,
            startLevel = st.level,
            startCounterUah = st.counterUah,
            screenOnMs = 0,
            deepSleepMs = 0,
            integratedUah = 0,
            designCapacityMah = designCapacityMah,
            isComplete = false,
            lastSampleElapsedMs = nowElapsed,
            lastSampleUptimeMs = nowUptime
        )
        lastElapsedMs = nowElapsed
        lastUptimeMs = nowUptime
        lastCurrentUa = st.currentUa
        screenOnSinceElapsed = if (isScreenOn) nowElapsed else null
        ongoing = event
        eventRepo.insertEvent(event)
    }

    private fun finalizeEvent(
        ev: ChargeEvent,
        st: BatteryState,
        nowWall: Long,
        nowElapsed: Long
    ): ChargeEvent {
        // Close an open screen-on interval.
        var screenOnMs = ev.screenOnMs
        screenOnSinceElapsed?.let { start ->
            if (nowElapsed > start) screenOnMs += nowElapsed - start
        }

        val duration = (nowWall - ev.startTime).coerceAtLeast(0L)
        val deepSleep = ev.deepSleepMs.coerceIn(0L, duration)
        val screenOff = (duration - screenOnMs - deepSleep).coerceAtLeast(0L)
        val deltaLevel = st.level - ev.startLevel

        val counterMah = counterDeltaMah(ev.startCounterUah, st.counterUah, ev.designCapacityMah)
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
            endTime = nowWall,
            durationMs = duration,
            endLevel = st.level,
            deltaLevel = deltaLevel,
            mah = mah,
            mahSource = source,
            screenOnMs = screenOnMs.coerceAtMost(duration),
            screenOffMs = screenOff,
            deepSleepMs = deepSleep,
            wearCycles = wear,
            endCounterUah = st.counterUah,
            isComplete = true
        )
    }

    private suspend fun finalizeOngoing() {
        val ev = ongoing ?: eventRepo.getOngoingEvent() ?: return
        val st = readState()
        val finished = finalizeEvent(ev, st, System.currentTimeMillis(), SystemClock.elapsedRealtime())
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
