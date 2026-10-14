package com.dijia1124.plusplusbattery.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dijia1124.plusplusbattery.data.model.HistoryInfo
import com.dijia1124.plusplusbattery.data.repository.ChargeEventRepository
import com.dijia1124.plusplusbattery.data.repository.HistoryInfoRepository
import com.dijia1124.plusplusbattery.data.repository.PrefsRepository
import com.dijia1124.plusplusbattery.data.util.HealthSummary
import com.dijia1124.plusplusbattery.data.util.WearSummary
import com.dijia1124.plusplusbattery.data.util.computeHealth
import com.dijia1124.plusplusbattery.data.util.computeWear
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val eventRepository = ChargeEventRepository(application)
    private val historyRepository = HistoryInfoRepository(application)
    private val prefsRepository = PrefsRepository(application)

    val health: StateFlow<HealthSummary> = combine(
        eventRepository.allEvents,
        prefsRepository.healthMinStartLevel,
        prefsRepository.healthMinEndLevel
    ) { events, minStart, minEnd ->
        computeHealth(events, minStart, minEnd)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), computeHealth(emptyList()))

    val wear: StateFlow<WearSummary> = eventRepository.allEvents
        .map { computeWear(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), computeWear(emptyList()))

    val cycleHistory: Flow<List<HistoryInfo>> = historyRepository.allHistoryInfos
}
