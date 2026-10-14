package com.dijia1124.plusplusbattery.vm

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import com.dijia1124.plusplusbattery.data.repository.ChargeEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChargeEventViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val repository = ChargeEventRepository(application)

    val allEvents: Flow<List<ChargeEvent>> = repository.allEvents

    fun deleteEvent(uid: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteEvent(uid)
    }

    fun clearAll() = viewModelScope.launch(Dispatchers.IO) {
        repository.clearAll()
    }

    suspend fun exportEventsToCsv(context: Context) = withContext(Dispatchers.IO) {
        val events = allEvents.first()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val builder = StringBuilder()
        builder.append(
            "type,startTime,endTime,durationMs,startLevel,endLevel,deltaLevel," +
                "mah,mahSource,screenOnMs,screenOffMs,deepSleepMs,wearCycles\n"
        )
        events.forEach { e ->
            builder.append(
                listOf(
                    e.type,
                    sdf.format(Date(e.startTime)),
                    e.endTime?.let { sdf.format(Date(it)) } ?: "",
                    e.durationMs ?: "",
                    e.startLevel,
                    e.endLevel ?: "",
                    e.deltaLevel ?: "",
                    e.mah?.let { "%.1f".format(it) } ?: "",
                    e.mahSource ?: "",
                    e.screenOnMs,
                    e.screenOffMs,
                    e.deepSleepMs,
                    e.wearCycles?.let { "%.4f".format(it) } ?: ""
                ).joinToString(",")
            )
            builder.append('\n')
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "battery_events_$timeStamp.csv"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it).use { out -> out?.write(builder.toString().toByteArray()) }
        }
    }
}
