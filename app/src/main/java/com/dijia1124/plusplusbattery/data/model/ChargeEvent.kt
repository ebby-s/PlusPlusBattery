package com.dijia1124.plusplusbattery.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single charge or discharge session.
 *
 * While a session is in progress the row exists with [isComplete] = false and is
 * continuously updated, so a process death or reboot does not lose the session.
 */
@Entity(tableName = "charge_events")
data class ChargeEvent(
    @PrimaryKey(autoGenerate = true)
    val uid: Long = 0,
    /** [TYPE_CHARGE] or [TYPE_DISCHARGE]. */
    val type: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long? = null,
    val startLevel: Int,
    val endLevel: Int? = null,
    /** endLevel - startLevel (positive while charging, negative while discharging). */
    val deltaLevel: Int? = null,
    /** Charge moved during the session, in mAh (always positive). */
    val mah: Double? = null,
    /** Where [mah] came from: "counter", "root" or "integrated". */
    val mahSource: String? = null,
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    val deepSleepMs: Long = 0,
    /** Estimated battery wear for this session, in equivalent full cycles. */
    val wearCycles: Double? = null,
    val isComplete: Boolean = false,
    // Raw snapshots used to recover an in-progress session.
    val startCounterUah: Long? = null,
    val endCounterUah: Long? = null,
    val integratedUah: Long? = null,
    val designCapacityMah: Int? = null,
    val lastSampleElapsedMs: Long? = null,
    val lastSampleUptimeMs: Long? = null
) {
    companion object {
        const val TYPE_CHARGE = "CHARGE"
        const val TYPE_DISCHARGE = "DISCHARGE"
    }
}
