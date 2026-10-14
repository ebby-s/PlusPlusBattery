package com.dijia1124.plusplusbattery.data.repository

import android.app.Application
import com.dijia1124.plusplusbattery.data.local.ChargeEventDao
import com.dijia1124.plusplusbattery.data.local.HistoryInfoDatabase
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import kotlinx.coroutines.flow.Flow

class ChargeEventRepository(application: Application) {
    private val dao: ChargeEventDao =
        HistoryInfoDatabase.getDatabase(application).chargeEventDao()

    val allEvents: Flow<List<ChargeEvent>> = dao.getAllEvents()

    suspend fun getOngoingEvent(): ChargeEvent? = dao.getOngoingEvent()

    suspend fun insertEvent(event: ChargeEvent): Long = dao.insertEvent(event)

    suspend fun updateEvent(event: ChargeEvent) = dao.updateEvent(event)

    suspend fun deleteEvent(uid: Long) = dao.deleteEvent(uid)

    suspend fun getAllCompleteEvents(): List<ChargeEvent> = dao.getAllCompleteEvents()

    suspend fun clearAll() = dao.clearAll()
}
