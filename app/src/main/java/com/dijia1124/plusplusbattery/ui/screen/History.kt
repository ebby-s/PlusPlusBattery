package com.dijia1124.plusplusbattery.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dijia1124.plusplusbattery.R
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import com.dijia1124.plusplusbattery.ui.components.AppScaffold
import com.dijia1124.plusplusbattery.ui.components.getListItemShape
import com.dijia1124.plusplusbattery.vm.ChargeEventViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun History(chargeEventViewModel: ChargeEventViewModel, currentTitle: String) {
    var showHelpDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by chargeEventViewModel.allEvents.collectAsState(initial = emptyList())

    AppScaffold(
        title = currentTitle,
        isTopLevel = true,
        actions = {
            OutlinedIconButton(onClick = { showExportDialog = true }) {
                Icon(
                    painter = painterResource(id = R.drawable.export_notes_24dp_1f1f1f_fill0_wght400_grad0_opsz24),
                    contentDescription = stringResource(R.string.export)
                )
            }
            OutlinedIconButton(onClick = { showHelpDialog = true }) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.info_24dp_1f1f1f_fill0_wght400_grad0_opsz24),
                    contentDescription = stringResource(R.string.help)
                )
            }
        }
    ) {
        ChargeEventList(events) { uid -> chargeEventViewModel.deleteEvent(uid) }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = stringResource(R.string.history)) },
            text = { Text(text = stringResource(R.string.charge_event_help)) },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(text = stringResource(R.string.export_history)) },
            text = { Text(text = stringResource(R.string.export_events_confirmation)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        chargeEventViewModel.exportEventsToCsv(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.saved_to_downloads),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    showExportDialog = false
                }) {
                    Text(stringResource(R.string.export))
                }
            },
            dismissButton = {
                Button(onClick = { showExportDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ChargeEventList(events: List<ChargeEvent>, onDelete: (Long) -> Unit) {
    if (events.isEmpty()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.no_events_yet),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        LazyColumn {
            items(events.size) { index ->
                val event = events[index]
                val cardShape = getListItemShape(index = index, size = events.size)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = cardShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            ChargeEventCardContent(event)
                        }
                        IconButton(onClick = { onDelete(event.uid) }) {
                            Icon(
                                imageVector = ImageVector.vectorResource(
                                    id = R.drawable.delete_24dp_1f1f1f_fill0_wght400_grad0_opsz24
                                ),
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargeEventCardContent(event: ChargeEvent) {
    val isCharge = event.type == ChargeEvent.TYPE_CHARGE
    val typeLabel = if (isCharge) stringResource(R.string.charging) else stringResource(R.string.discharging)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Text(
        text = "$typeLabel · ${formatter.format(Date(event.startTime))}",
        style = MaterialTheme.typography.bodyMedium
    )

    val endLevel = event.endLevel
    val levelText = if (endLevel != null) {
        val delta = event.deltaLevel ?: (endLevel - event.startLevel)
        val sign = if (delta >= 0) "+" else ""
        "${event.startLevel}% → ${endLevel}% ($sign$delta%)"
    } else {
        "${event.startLevel}% → …"
    }
    Text(
        text = levelText,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )

    val mahText = event.mah?.let {
        val verb = if (isCharge) stringResource(R.string.added) else stringResource(R.string.used)
        "%,.0f mAh %s".format(it, verb)
    } ?: "—"
    Text(text = mahText, style = MaterialTheme.typography.bodyMedium)

    val durationText = event.durationMs?.let { formatDuration(it) }
        ?: stringResource(R.string.in_progress)
    Text(
        text = "${stringResource(R.string.duration)}: $durationText",
        style = MaterialTheme.typography.bodyMedium
    )

    if (event.isComplete) {
        Text(
            text = "${stringResource(R.string.screen_on)}: ${formatDuration(event.screenOnMs)} · " +
                "${stringResource(R.string.screen_off)}: ${formatDuration(event.screenOffMs)} · " +
                "${stringResource(R.string.deep_sleep)}: ${formatDuration(event.deepSleepMs)}",
            style = MaterialTheme.typography.bodySmall
        )
        val wearText = event.wearCycles?.let { "%.3f".format(it) } ?: "—"
        Text(
            text = "${stringResource(R.string.wear)}: $wearText",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(Modifier.height(2.dp))
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
