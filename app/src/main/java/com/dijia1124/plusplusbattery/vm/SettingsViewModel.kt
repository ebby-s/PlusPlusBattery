package com.dijia1124.plusplusbattery.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dijia1124.plusplusbattery.data.repository.PrefsRepository
import com.dijia1124.plusplusbattery.data.util.RootBackend
import com.dijia1124.plusplusbattery.data.util.detectRootBackend
import com.dijia1124.plusplusbattery.service.BatteryTrackerService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val prefs = PrefsRepository(application)

    private val _hasRoot = MutableStateFlow(false)
    val hasRoot: StateFlow<Boolean> = _hasRoot

    private val _rootBackend = MutableStateFlow(RootBackend.GENERIC)
    val rootBackend: StateFlow<RootBackend> = _rootBackend

    init {
        // check if the device has root access
        viewModelScope.launch {
            _hasRoot.value = try {
                hasRootAccess()
            } catch (e: Exception) {
                false
            }
        }
        viewModelScope.launch {
            _rootBackend.value = detectRootBackend()
        }
    }

    private suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("su -c whoami").exec().isSuccess
        } catch (e: Exception) {
            false
        }
    }
    val refreshInterval = prefs.refreshInterval.stateIn(
        viewModelScope, SharingStarted.Companion.WhileSubscribed(5000), initialValue = 1000
    )

    val darkModeEnabled = prefs.darkModeEnabled.stateIn(
        viewModelScope, SharingStarted.Companion.WhileSubscribed(5000), initialValue = false
    )
    val followSystemTheme = prefs.followSystemTheme.stateIn(
        viewModelScope, SharingStarted.Companion.WhileSubscribed(5000), initialValue = true
    )

    fun setRefreshInterval(rate: Int) = viewModelScope.launch {
        prefs.setRefreshInterval(rate)
    }

    fun setDarkMode(enabled: Boolean) = viewModelScope.launch {
        prefs.setDarkMode(enabled)
    }

    fun setFollowSystem(enabled: Boolean) = viewModelScope.launch {
        prefs.setFollowSystemTheme(enabled)
        if (enabled) prefs.setDarkMode(false)
    }

    val showOplusFields: StateFlow<Boolean> = prefs.showOplusFields
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowOplusFields(enabled: Boolean) = viewModelScope.launch {
        prefs.setShowOplusFields(enabled)
    }

    val isPowerChartExpanded: StateFlow<Boolean> = prefs.isPowerChartExpanded
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPowerChartExpanded(expanded: Boolean) = viewModelScope.launch {
        prefs.setPowerChartExpanded(expanded)
    }

    val dailyHistoryEnabled = prefs.dailyHistoryEnabled.stateIn(
        viewModelScope, SharingStarted.Eagerly, initialValue = false
    )

    fun setDailyHistoryEnabled(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            prefs.setDailyHistoryEnabled(context, enabled)
        }
    }

    val isCelsius: StateFlow<Boolean> = prefs.isCelsiusFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    fun setIsCelsius(isCelsius: Boolean) {
        viewModelScope.launch {
            prefs.setIsCelsius(isCelsius)
        }
    }

    val trackingEnabled: StateFlow<Boolean> = prefs.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    fun setTrackingEnabled(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            prefs.setTrackingEnabled(enabled)
            val intent = Intent(context, BatteryTrackerService::class.java)
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                intent.action = BatteryTrackerService.ACTION_STOP
                context.startService(intent)
            }
        }
    }

    val trackingInterval: StateFlow<Int> = prefs.trackingInterval
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = 60_000)

    fun setTrackingInterval(intervalMs: Int) {
        viewModelScope.launch {
            prefs.setTrackingInterval(intervalMs)
        }
    }

    val healthMinStartLevel: StateFlow<Int> = prefs.healthMinStartLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = 15)

    fun setHealthMinStartLevel(level: Int) = viewModelScope.launch {
        prefs.setHealthMinStartLevel(level)
    }

    val healthMinEndLevel: StateFlow<Int> = prefs.healthMinEndLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = 80)

    fun setHealthMinEndLevel(level: Int) = viewModelScope.launch {
        prefs.setHealthMinEndLevel(level)
    }
}