package com.dijia1124.plusplusbattery.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dijia1124.plusplusbattery.R
import com.dijia1124.plusplusbattery.ui.components.AppScaffold
import com.dijia1124.plusplusbattery.ui.components.BatteryChart
import com.dijia1124.plusplusbattery.ui.components.CardGroup
import com.dijia1124.plusplusbattery.ui.components.CardGroupTitle
import com.dijia1124.plusplusbattery.vm.HealthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Health(healthViewModel: HealthViewModel, currentTitle: String) {
    AppScaffold(title = currentTitle, isTopLevel = true) {
        HealthContent(healthViewModel)
    }
}

@Composable
fun HealthContent(healthViewModel: HealthViewModel) {
    val health by healthViewModel.health.collectAsState()
    val wear by healthViewModel.wear.collectAsState()
    val cycleHistory by healthViewModel.cycleHistory.collectAsState(initial = emptyList())
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .verticalScroll(scrollState)
    ) {
        // ---- Capacity estimate ------------------------------------------------
        CardGroup(
            title = { CardGroupTitle(text = stringResource(R.string.battery_capacity_estimate)) },
            content = listOf({
                Column(modifier = Modifier.padding(16.dp)) {
                    val est = health.estimatedCapacityMah
                    val design = health.designCapacityMah
                    InfoRow(
                        label = stringResource(R.string.estimated_capacity),
                        value = est?.let { "%,.0f mAh".format(it) } ?: "—"
                    )
                    InfoRow(
                        label = stringResource(R.string.design_capacity),
                        value = design?.let { "%,d mAh".format(it) } ?: "—"
                    )
                    val soh = if (est != null && design != null && design > 0) est / design * 100.0 else null
                    InfoRow(
                        label = stringResource(R.string.battery_health),
                        value = soh?.let { "%.1f%%".format(it) } ?: "—"
                    )
                    InfoRow(
                        label = stringResource(R.string.data_points),
                        value = "${health.dataPointCount}"
                    )
                    InfoRow(
                        label = stringResource(R.string.total_charged),
                        value = "${health.totalPercent}% · %,.0f mAh".format(health.totalMah)
                    )
                }
            })
        )

        Spacer(Modifier.height(16.dp))

        // ---- Capacity over time ----------------------------------------------
        CardGroup(
            title = { CardGroupTitle(text = stringResource(R.string.capacity_over_time)) },
            content = listOf({
                Column(modifier = Modifier.padding(16.dp)) {
                    BatteryChart(
                        points = health.capacityPoints.map { it.timeMs to it.capacityPercent },
                        regression = health.regression,
                        yUnit = "%",
                        connect = false
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(
                        label = stringResource(R.string.capacity_loss_per_year),
                        value = health.lossPerYearPercent?.let { "%.1f %%/year".format(it) } ?: "—"
                    )
                    InfoRow(
                        label = stringResource(R.string.r_squared),
                        value = health.rSquared?.let { "%.3f".format(it) } ?: "—"
                    )
                }
            })
        )

        Spacer(Modifier.height(16.dp))

        // ---- Wear ------------------------------------------------------------
        CardGroup(
            title = { CardGroupTitle(text = stringResource(R.string.battery_wear)) },
            content = listOf({
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow(
                        label = stringResource(R.string.cycles_per_day_7),
                        value = "%.3f  (%.3f)".format(wear.cyclesPerDay7, wear.totalWear7)
                    )
                    InfoRow(
                        label = stringResource(R.string.cycles_per_day_30),
                        value = "%.3f  (%.3f)".format(wear.cyclesPerDay30, wear.totalWear30)
                    )
                    InfoRow(
                        label = stringResource(R.string.cycles_per_day_365),
                        value = "%.3f  (%.3f)".format(wear.cyclesPerDay365, wear.totalWear365)
                    )
                    InfoRow(
                        label = stringResource(R.string.total_wear),
                        value = "%.3f".format(wear.totalWear)
                    )
                    Spacer(Modifier.height(8.dp))
                    BatteryChart(
                        points = wear.wearPoints.map { it.timeMs to it.cumulativeCycles },
                        yUnit = "",
                        connect = true
                    )
                }
            })
        )

        Spacer(Modifier.height(16.dp))

        // ---- Legacy cycle count ---------------------------------------------
        CardGroup(
            title = { CardGroupTitle(text = stringResource(R.string.battery_cycle_count)) },
            content = listOf({
                Column(modifier = Modifier.padding(16.dp)) {
                    val points = cycleHistory.mapNotNull { h ->
                        h.cycleCount.toDoubleOrNull()?.let { h.date to it }
                    }
                    BatteryChart(points = points, yUnit = "", connect = true)
                }
            })
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
