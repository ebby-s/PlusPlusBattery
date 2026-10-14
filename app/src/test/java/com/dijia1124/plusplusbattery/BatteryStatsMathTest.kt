package com.dijia1124.plusplusbattery

import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import com.dijia1124.plusplusbattery.data.util.computeHealth
import com.dijia1124.plusplusbattery.data.util.computeWear
import com.dijia1124.plusplusbattery.data.util.linearRegression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BatteryStatsMathTest {

    private fun chargeEvent(
        startLevel: Int,
        endLevel: Int,
        mah: Double?,
        startTime: Long = 0L,
        wearCycles: Double? = null,
        design: Int? = 3240
    ) = ChargeEvent(
        type = ChargeEvent.TYPE_CHARGE,
        startTime = startTime,
        endTime = startTime + 3_600_000,
        durationMs = 3_600_000,
        startLevel = startLevel,
        endLevel = endLevel,
        deltaLevel = endLevel - startLevel,
        mah = mah,
        wearCycles = wearCycles,
        designCapacityMah = design,
        isComplete = true
    )

    @Test
    fun healthEstimatesCapacityFromQualifyingSession() {
        // 2500 mAh filled 80% (10 -> 90) => 3125 mAh full capacity
        val summary = computeHealth(listOf(chargeEvent(10, 90, 2500.0)))
        assertNotNull(summary.estimatedCapacityMah)
        assertEquals(3125.0, summary.estimatedCapacityMah!!, 0.5)
        assertEquals(1, summary.dataPointCount)
        assertEquals(80, summary.totalPercent)
        assertEquals(2500.0, summary.totalMah, 0.5)
    }

    @Test
    fun healthIgnoresNonQualifyingSessions() {
        val events = listOf(
            chargeEvent(40, 90, 1500.0),   // started too high
            chargeEvent(10, 60, 1500.0),   // ended too low
            chargeEvent(10, 90, null),     // no mAh
            chargeEvent(5, 100, 3000.0)    // qualifies
        )
        val summary = computeHealth(events)
        assertEquals(1, summary.dataPointCount)
        assertEquals(3000.0 / 0.95, summary.estimatedCapacityMah!!, 0.5)
    }

    @Test
    fun healthRespectsCustomThresholds() {
        val events = listOf(
            chargeEvent(30, 60, 1000.0),
            chargeEvent(10, 90, 2500.0)
        )
        // Relaxed thresholds: both sessions qualify.
        assertEquals(2, computeHealth(events, minStartLevel = 35, minEndLevel = 50).dataPointCount)
        // Strict thresholds: neither qualifies.
        assertEquals(0, computeHealth(events, minStartLevel = 5, minEndLevel = 95).dataPointCount)
    }

    @Test
    fun linearRegressionFitsPerfectLine() {
        val points = (0..10).map { it.toDouble() to (2.0 * it + 1.0) }
        val reg = linearRegression(points)
        assertNotNull(reg)
        assertEquals(2.0, reg!!.slopePerMs, 1e-9)
        assertEquals(1.0, reg.rSquared, 1e-9)
    }

    @Test
    fun linearRegressionNeedsTwoPoints() {
        assertNull(linearRegression(listOf(1.0 to 1.0)))
    }

    @Test
    fun wearAccumulatesAndWindows() {
        val now = 1_000_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val events = listOf(
            chargeEvent(10, 90, 1000.0, startTime = now - 2 * day, wearCycles = 0.5),
            chargeEvent(10, 90, 1000.0, startTime = now - 1 * day, wearCycles = 0.3),
            chargeEvent(10, 90, 1000.0, startTime = now - 100 * day, wearCycles = 0.9)
        )
        val wear = computeWear(events, now = now)
        assertEquals(0.8, wear.totalWear7, 1e-6)
        assertEquals(1.7, wear.totalWear, 1e-6)
        assertEquals(3, wear.wearPoints.size)
        assertEquals(0.9, wear.wearPoints[0].cumulativeCycles, 1e-6)
        assertEquals(1.4, wear.wearPoints[1].cumulativeCycles, 1e-6)
        assertEquals(1.7, wear.wearPoints[2].cumulativeCycles, 1e-6)
    }
}
