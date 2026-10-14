package com.dijia1124.plusplusbattery.data.util

import com.dijia1124.plusplusbattery.data.model.ChargeEvent

/** A single health data point derived from a qualifying charge session. */
data class CapacityPoint(
    val timeMs: Long,
    val capacityMah: Double,
    val capacityPercent: Double
)

/** Result of a least-squares fit of capacity against time. */
data class Regression(
    val slopePerMs: Double,
    val slopePerYear: Double,
    val intercept: Double,
    val rSquared: Double
)

data class HealthSummary(
    val estimatedCapacityMah: Double?,
    val designCapacityMah: Int?,
    val dataPointCount: Int,
    val totalPercent: Int,
    val totalMah: Double,
    val capacityPoints: List<CapacityPoint>,
    val lossPerYearPercent: Double?,
    val rSquared: Double?,
    val regression: Regression?
)

data class WearPoint(val timeMs: Long, val cumulativeCycles: Double)

data class WearSummary(
    val totalWear7: Double,
    val cyclesPerDay7: Double,
    val totalWear30: Double,
    val cyclesPerDay30: Double,
    val totalWear365: Double,
    val cyclesPerDay365: Double,
    val totalWear: Double,
    val wearPoints: List<WearPoint>
)

private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
private const val MS_PER_YEAR = 365.25 * MS_PER_DAY

/**
 * Battery health is estimated from charge sessions that start low and end high
 * (below 15% to above 80%), the same approach AccuBattery documents. The full
 * capacity is charge added divided by the fraction of the battery it filled.
 */
fun computeHealth(
    events: List<ChargeEvent>,
    minStartLevel: Int = 15,
    minEndLevel: Int = 80
): HealthSummary {
    val qualifying = events.filter {
        it.isComplete &&
            it.type == ChargeEvent.TYPE_CHARGE &&
            it.startLevel <= minStartLevel &&
            (it.endLevel ?: -1) >= minEndLevel &&
            it.mah != null &&
            (it.deltaLevel ?: 0) > 0
    }.sortedBy { it.startTime }

    val design = events.mapNotNull { it.designCapacityMah }.lastOrNull()

    val points = qualifying.mapNotNull { e ->
        val mah = e.mah ?: return@mapNotNull null
        val delta = e.deltaLevel ?: 0
        if (delta <= 0) return@mapNotNull null
        val capacity = mah / (delta / 100.0)
        CapacityPoint(
            timeMs = e.startTime,
            capacityMah = capacity,
            capacityPercent = if (design != null && design > 0) capacity / design * 100.0 else capacity
        )
    }

    // Most recent 5 sessions, as AccuBattery does, for responsiveness.
    val recent = points.takeLast(5)
    val estimated = if (recent.isEmpty()) null else recent.map { it.capacityMah }.average()

    val regression = linearRegression(points.map { it.timeMs.toDouble() to it.capacityPercent })

    return HealthSummary(
        estimatedCapacityMah = estimated,
        designCapacityMah = design,
        dataPointCount = points.size,
        totalPercent = qualifying.sumOf { it.deltaLevel ?: 0 },
        totalMah = qualifying.sumOf { it.mah ?: 0.0 },
        capacityPoints = points,
        lossPerYearPercent = regression?.let { -it.slopePerYear },
        rSquared = regression?.rSquared,
        regression = regression
    )
}

/**
 * Battery wear, in equivalent full cycles, from charge throughput divided by the
 * design capacity. Rate windows are computed over the trailing N days.
 */
fun computeWear(events: List<ChargeEvent>, now: Long = System.currentTimeMillis()): WearSummary {
    val completed = events
        .filter { it.isComplete && it.wearCycles != null }
        .sortedBy { it.startTime }

    val points = mutableListOf<WearPoint>()
    var cumulative = 0.0
    completed.forEach { e ->
        cumulative += e.wearCycles ?: 0.0
        points.add(WearPoint(e.endTime ?: e.startTime, cumulative))
    }

    fun windowWear(days: Int): Double {
        val cutoff = now - days * MS_PER_DAY
        return completed
            .filter { (it.endTime ?: it.startTime) >= cutoff }
            .sumOf { it.wearCycles ?: 0.0 }
    }

    val w7 = windowWear(7)
    val w30 = windowWear(30)
    val w365 = windowWear(365)

    return WearSummary(
        totalWear7 = w7,
        cyclesPerDay7 = w7 / 7.0,
        totalWear30 = w30,
        cyclesPerDay30 = w30 / 30.0,
        totalWear365 = w365,
        cyclesPerDay365 = w365 / 365.0,
        totalWear = cumulative,
        wearPoints = points
    )
}

fun linearRegression(points: List<Pair<Double, Double>>): Regression? {
    if (points.size < 2) return null
    val n = points.size
    val xMean = points.sumOf { it.first } / n
    val yMean = points.sumOf { it.second } / n
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    for ((x, y) in points) {
        val dx = x - xMean
        val dy = y - yMean
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }
    if (sxx == 0.0) return null
    val slopePerMs = sxy / sxx
    val intercept = yMean - slopePerMs * xMean
    val rSquared = if (syy == 0.0) 0.0 else (sxy * sxy) / (sxx * syy)
    return Regression(
        slopePerMs = slopePerMs,
        slopePerYear = slopePerMs * MS_PER_YEAR,
        intercept = intercept,
        rSquared = rSquared
    )
}
