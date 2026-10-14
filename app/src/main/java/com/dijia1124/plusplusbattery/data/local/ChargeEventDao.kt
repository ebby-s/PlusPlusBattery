package com.dijia1124.plusplusbattery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dijia1124.plusplusbattery.data.model.ChargeEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeEventDao {
    @Query("SELECT * FROM charge_events ORDER BY startTime DESC")
    fun getAllEvents(): Flow<List<ChargeEvent>>

    @Query("SELECT * FROM charge_events WHERE isComplete = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getOngoingEvent(): ChargeEvent?

    @Query("SELECT * FROM charge_events WHERE isComplete = 1 ORDER BY startTime ASC")
    suspend fun getAllCompleteEvents(): List<ChargeEvent>

    @Insert
    suspend fun insertEvent(event: ChargeEvent): Long

    @Update
    suspend fun updateEvent(event: ChargeEvent)

    @Query("DELETE FROM charge_events WHERE uid = :uid")
    suspend fun deleteEvent(uid: Long)

    @Query("DELETE FROM charge_events")
    suspend fun clearAll()
}
