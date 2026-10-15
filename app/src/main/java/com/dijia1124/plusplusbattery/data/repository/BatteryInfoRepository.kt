package com.dijia1124.plusplusbattery.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import com.dijia1124.plusplusbattery.data.util.DUAL_BATTERY_KEY
import com.dijia1124.plusplusbattery.data.util.ESTIMATED_FCC_KEY
import com.dijia1124.plusplusbattery.data.util.MULTIPLIER_MAGNITUDE_KEY
import com.dijia1124.plusplusbattery.data.util.MULTIPLY_KEY
import com.dijia1124.plusplusbattery.R
import com.dijia1124.plusplusbattery.data.util.calcRawFcc
import com.dijia1124.plusplusbattery.data.util.calcRawSoh
import com.dijia1124.plusplusbattery.data.model.BatteryInfo
import com.dijia1124.plusplusbattery.data.model.BatteryInfoType
import com.dijia1124.plusplusbattery.data.model.CustomEntry
import com.dijia1124.plusplusbattery.data.util.CUSTOM_ENTRIES
import com.dijia1124.plusplusbattery.data.util.IS_CELSIUS
import com.dijia1124.plusplusbattery.data.util.formatTemperature
import com.dijia1124.plusplusbattery.data.util.dataStore
import com.dijia1124.plusplusbattery.data.util.formatWithUnit
import com.dijia1124.plusplusbattery.data.util.getHealthString
import com.dijia1124.plusplusbattery.data.util.getStatusString
import com.dijia1124.plusplusbattery.data.util.isDualBattery
import com.dijia1124.plusplusbattery.data.util.normalizeQmax
import com.dijia1124.plusplusbattery.data.util.readBatteryInfo
import com.dijia1124.plusplusbattery.data.util.readBatteryLogMap
import com.dijia1124.plusplusbattery.data.util.readChargeMah
import com.dijia1124.plusplusbattery.data.util.readRootFiles
import com.dijia1124.plusplusbattery.data.util.readRootCycleCount
import com.dijia1124.plusplusbattery.data.util.readTermCoeff
import com.dijia1124.plusplusbattery.data.util.safeRootReadInt
import com.dijia1124.plusplusbattery.data.util.detectRootBackend
import com.dijia1124.plusplusbattery.data.util.RootBackend
import com.dijia1124.plusplusbattery.data.util.BMS_PATH
import com.dijia1124.plusplusbattery.data.util.BATTERY_PATH
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import kotlin.collections.map
import kotlin.math.pow

private const val BCC_VOLTAGE_0_INDEX = 6
private const val BCC_VOLTAGE_1_INDEX = 11
private const val BCC_CURRENT_INDEX = 8
private const val CURRENT_FULL_IN_MA = 25
private val OPLUS_TYPES = setOf(
    BatteryInfoType.OPLUS_RM, BatteryInfoType.OPLUS_FCC,
    BatteryInfoType.OPLUS_RAW_FCC, BatteryInfoType.OPLUS_SOH,
    BatteryInfoType.OPLUS_RAW_SOH, BatteryInfoType.OPLUS_QMAX,
    BatteryInfoType.OPLUS_VBAT_UV, BatteryInfoType.OPLUS_SN,
    BatteryInfoType.OPLUS_MANU_DATE, BatteryInfoType.OPLUS_BATTERY_TYPE,
    BatteryInfoType.OPLUS_DESIGN_CAPACITY
)

class BatteryInfoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val batteryManager get() =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val settings by lazy { context.dataStore }

    private val calibFlow = settings.data
        .map { prefs ->
            val isMult = prefs[MULTIPLY_KEY] != false
            val mag    = prefs[MULTIPLIER_MAGNITUDE_KEY] ?: 0
            if (isMult) 10.0.pow(mag.toDouble()) else 1/10.0.pow(mag.toDouble())
        }

    private val dualBattFlow: Flow<Int> = settings.data
        .map { prefs -> if (prefs[DUAL_BATTERY_KEY] == true) 2 else 1 }

    val isDualBattFlow: Flow<Boolean> = dualBattFlow
        .map { it == 2 }

    suspend fun setDualBatt(enabled: Boolean) {
        settings.edit { it[DUAL_BATTERY_KEY] = enabled }
    }

    suspend fun setMultiplierPrefs(isMult: Boolean, mag: Int) {
        settings.edit { prefs ->
            prefs[MULTIPLY_KEY] = isMult
            prefs[MULTIPLIER_MAGNITUDE_KEY] = mag
        }
    }

    val isMultiplyFlow: Flow<Boolean> = settings.data
        .map { prefs -> prefs[MULTIPLY_KEY] != false }

    val selectedMagnitudeFlow: Flow<Int> = settings.data
        .map { prefs -> prefs[MULTIPLIER_MAGNITUDE_KEY] ?: 0 }

    val isCelsiusFlow: Flow<Boolean> = settings.data
        .map { prefs -> prefs[IS_CELSIUS] ?: true }

    val estimatedFccFlow: Flow<String> = settings.data
        .map { prefs ->
            prefs[ESTIMATED_FCC_KEY]?.toString()
                ?: context.getString(R.string.estimating_full_charge_capacity)
        }

    suspend fun getBasicBatteryInfo(allowRootFallback: Boolean = false): List<BatteryInfo> = withContext(Dispatchers.IO) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -999) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        var cycleCount = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
        // Some devices don't report the cycle count through the framework, so fall
        // back to the fuel-gauge sysfs node when running in root mode.
        if (allowRootFallback && cycleCount < 0) {
            cycleCount = readRootCycleCount() ?: cycleCount
        }
        val isCelsius = isCelsiusFlow.first()

        listOf(
            BatteryInfo(
                BatteryInfoType.LEVEL,
                "$level%",
                false
            ),
            BatteryInfo(
                BatteryInfoType.STATUS,
                getStatusString(status, context),
                false
            ),
            BatteryInfo(
                BatteryInfoType.HEALTH,
                getHealthString(health, context),
                false
            ),
            BatteryInfo(
                BatteryInfoType.CYCLE_COUNT,
                cycleCount.toString(),
                false
            ),
            BatteryInfo(
                BatteryInfoType.TEMP,
                formatTemperature(temperature, isCelsius),
                false
            ),
        )
    }

    suspend fun getRootBatteryInfo(): List<BatteryInfo> = withContext(Dispatchers.IO) {
        when (detectRootBackend()) {
            RootBackend.OPLUS -> getOplusRootBatteryInfo()
            RootBackend.GENERIC -> getGenericRootBatteryInfo()
        }
    }

    private suspend fun getOplusRootBatteryInfo(): List<BatteryInfo> = withContext(Dispatchers.IO) {
        val calibMultiplier = calibFlow.first()
        val dualBattMultiplier = dualBattFlow.first()
        var rootModeVoltage0 = 0
        var rootModeVoltage1 = 0
        var rootModeCurrent = 0
        var rootModePower = 0.0
        var rootReadFailed = false
        // fall back to batteryManager if root read fails for rooted vot1,2 and current
        rootModeVoltage0 =
            safeRootReadInt("bcc_parms", BCC_VOLTAGE_0_INDEX, {
                val intent = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            }) { rootReadFailed = true }

        // A workaround for devices that return 0 for rootModeVoltage0
        if (rootModeVoltage0 == 0) {
            rootReadFailed = true
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            rootModeVoltage0 = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            // BatteryManager reports microamps; root entries are in mA
            rootModeCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
        } else {
            rootModeVoltage1 =
                safeRootReadInt("bcc_parms", BCC_VOLTAGE_1_INDEX, {
                    // if root read fails, shows value 0, on the right side of Battery Voltage entry
                    // e.g. 4000 / 0 mV
                    0
                }) { rootReadFailed = true }

            rootModeCurrent =
                safeRootReadInt("bcc_parms", BCC_CURRENT_INDEX, {
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
                }) { rootReadFailed = true }
        }

        rootModePower = if (!rootReadFailed) {
            if (isDualBattery()) {
                (rootModeVoltage0 + rootModeVoltage1) * rootModeCurrent * calibMultiplier / 1000000.0
            }
            else {
                rootModeVoltage0 * rootModeCurrent * calibMultiplier * dualBattMultiplier / 1000000.0
            }
        } else {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000.0
                    * dualBattMultiplier
                    * calibMultiplier
                    * (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                ?: 0) / 1000000.0)
        }
        val rm = readBatteryInfo("battery_rm")
            ?: context.getString(R.string.unknown)
        val fcc = readBatteryInfo("battery_fcc")
            ?: context.getString(R.string.unknown)
        val soh = readBatteryInfo("battery_soh")
            ?: context.getString(R.string.unknown)
        val vbatUv = readBatteryInfo("vbat_uv")
            ?: context.getString(R.string.unknown)
        val sn = readBatteryInfo("battery_sn")
            ?: context.getString(R.string.unknown)
        val batManDate = readBatteryInfo("battery_manu_date")
            ?: context.getString(R.string.unknown)
        val battType = readBatteryInfo("battery_type")
            ?: context.getString(R.string.unknown)
        val designCapacity = readBatteryInfo("design_capacity")
            ?: context.getString(R.string.unknown)
        val rawSoh = calcRawSoh(
            soh.toIntOrNull() ?: 0,
            vbatUv.toIntOrNull() ?: 0,
            readTermCoeff(context)
        ).let { resultValue ->
            if (resultValue < 0.0001f) context.getString(R.string.unknown) else resultValue.toString()
        }
        val rawFcc = calcRawFcc(
            fcc.toIntOrNull() ?: 0,
            rawSoh.toFloatOrNull() ?: 0f,
            vbatUv.toIntOrNull() ?: 0,
            readTermCoeff(context),
            designCapacity.toIntOrNull() ?: 0
        ).let { resultValue ->
            if (resultValue == 0) context.getString(R.string.unknown) else resultValue.toString()
        }
        val logMap = readBatteryLogMap()
        val fccInt = fcc.toIntOrNull()
        val qmaxInt = logMap["batt_qmax"]?.toIntOrNull()
        val qMax = qmaxInt?.let { q ->
            "${normalizeQmax(q, fccInt)} mAh"
        } ?: context.getString(R.string.unknown)
        listOf(
            BatteryInfo(
                BatteryInfoType.VOLTAGE,
                "$rootModeVoltage0 / $rootModeVoltage1 mV",
                false
            ),
            BatteryInfo(
                BatteryInfoType.CURRENT,
                (rootModeCurrent * calibMultiplier).formatWithUnit("mA"),
                false
            ),
            BatteryInfo(
                BatteryInfoType.POWER,
                rootModePower.formatWithUnit("W"),
                false
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_RM,
                "$rm mAh"
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_FCC,
                "$fcc mAh"
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_RAW_FCC,
                "$rawFcc mAh"
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_SOH,
                "$soh %"
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_RAW_SOH,
                rawSoh
                    .toDoubleOrNull()
                    ?.formatWithUnit("%")
                    ?: rawSoh
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_QMAX,
                qMax
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_VBAT_UV,
                "$vbatUv mV"
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_SN,
                sn
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_MANU_DATE,
                batManDate
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_BATTERY_TYPE,
                battType
            ),
            BatteryInfo(
                BatteryInfoType.OPLUS_DESIGN_CAPACITY,
                "$designCapacity mAh",
            ),
        )
    }

    /**
     * Root entries for devices without /sys/class/oplus_chg, read from the standard
     * Linux power_supply nodes (e.g. Qualcomm fuel gauge on the OnePlus 6).
     * charge_* nodes are in microampere-hours, voltage_now in microvolts and
     * current_now in microamps.
     */
    private suspend fun getGenericRootBatteryInfo(): List<BatteryInfo> = withContext(Dispatchers.IO) {
        val calibMultiplier = calibFlow.first()
        val dualBattMultiplier = dualBattFlow.first()
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Read every root-only node in a single batched call.
        val paths = listOf(
            BATTERY_PATH + "voltage_now",
            BATTERY_PATH + "current_now",
            BMS_PATH + "charge_counter",
            BATTERY_PATH + "charge_counter",
            BMS_PATH + "charge_full",
            BATTERY_PATH + "charge_full",
            BMS_PATH + "charge_full_design",
            BATTERY_PATH + "charge_full_design",
            BMS_PATH + "battery_type"
        )
        val v = readRootFiles(paths)
        fun mah(index: Int): Int? = v[index]?.toLongOrNull()?.let { (it / 1000L).toInt() }

        val voltageMv: Int = v[0]?.toLongOrNull()?.let { (it / 1000L).toInt() }
            ?: (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
        val currentMa: Int = v[1]?.toLongOrNull()?.let { (it / 1000L).toInt() }
            ?: (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000)
        val power = voltageMv * currentMa * calibMultiplier * dualBattMultiplier / 1_000_000.0

        val rm = mah(2) ?: mah(3)
        val fcc = mah(4) ?: mah(5)
        val design = mah(6) ?: mah(7)
        val soh = if (fcc != null && design != null && design > 0) {
            fcc * 100.0 / design
        } else null
        val battType = v[8]
        val unknown = context.getString(R.string.unknown)

        listOf(
            BatteryInfo(
                BatteryInfoType.VOLTAGE,
                "$voltageMv mV",
                false
            ),
            BatteryInfo(
                BatteryInfoType.CURRENT,
                (currentMa * calibMultiplier).formatWithUnit("mA"),
                false
            ),
            BatteryInfo(
                BatteryInfoType.POWER,
                power.formatWithUnit("W"),
                false
            ),
            BatteryInfo(
                BatteryInfoType.ROOT_REMAINING,
                rm?.let { "$it mAh" } ?: unknown
            ),
            BatteryInfo(
                BatteryInfoType.ROOT_FCC,
                fcc?.let { "$it mAh" } ?: unknown
            ),
            BatteryInfo(
                BatteryInfoType.ROOT_DESIGN_CAPACITY,
                design?.let { "$it mAh" } ?: unknown
            ),
            BatteryInfo(
                BatteryInfoType.ROOT_SOH,
                soh?.let { "%.1f%%".format(it) } ?: unknown
            ),
            BatteryInfo(
                BatteryInfoType.ROOT_BATTERY_TYPE,
                battType ?: unknown
            ),
        )
    }

    suspend fun getNonRootVoltCurrPwr(): List<BatteryInfo> = withContext(Dispatchers.IO) {
        val calibMultiplier = calibFlow.first()
        val dualBattMultiplier = dualBattFlow.first()
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        // BatteryManager reports current in microamperes; convert to milliamps
        val current =
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000.0 * calibMultiplier
        val power = current * voltage * dualBattMultiplier / 1_000_000.0

        listOf(
            BatteryInfo(BatteryInfoType.VOLTAGE, "$voltage mV", false),
            BatteryInfo(BatteryInfoType.CURRENT, current.formatWithUnit("mA"), false),
            BatteryInfo(BatteryInfoType.POWER, power.formatWithUnit("W"), false)
        )
    }

    suspend fun getEstimatedFcc(savedEstimatedFcc: String): BatteryInfo =
        withContext(Dispatchers.IO) {
            val calibMultiplier = calibFlow.first()
            // BatteryManager reports current in microamperes; convert to milliamps
            val currentNow =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000.0 * calibMultiplier
            val batteryLevel =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            if (kotlin.math.abs(currentNow) <= CURRENT_FULL_IN_MA && batteryLevel == 100) {
                val chargeCounter =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val fullChargeCapacity = (chargeCounter / (batteryLevel / 100.0)).toInt() / 1000
                if (fullChargeCapacity > 0) {
                    context.dataStore.edit { prefs ->
                        prefs[ESTIMATED_FCC_KEY] = fullChargeCapacity
                    }
                }
            }
            var estimatedFcc = context.getString(R.string.estimating_full_charge_capacity)

            if (savedEstimatedFcc != context.getString(R.string.estimating_full_charge_capacity)) {
                estimatedFcc = savedEstimatedFcc
                BatteryInfo(
                    BatteryInfoType.EST_FCC,
                    "$estimatedFcc mAh",
                    false
                )
            } else {
                BatteryInfo(
                    BatteryInfoType.EST_FCC,
                    estimatedFcc,
                    false
                )
            }
        }

    val customEntries: Flow<List<CustomEntry>> = settings.data
        .map { prefs ->
            prefs[CUSTOM_ENTRIES]
                ?.let { Json.decodeFromString<List<CustomEntry>>(it) }
                ?: emptyList()
        }

    suspend fun addCustomEntry(entry: CustomEntry) = settings.edit { prefs ->
        val list = customEntries.first().associateBy { it.path }.toMutableMap()
        list[entry.path] = entry
        prefs[CUSTOM_ENTRIES] = Json.encodeToString(list.values.toList())
    }

    suspend fun removeCustomEntry(path: String) = settings.edit { prefs ->
        val current = customEntries.first().filterNot { it.path == path }
        prefs[CUSTOM_ENTRIES] = Json.encodeToString(current)
    }

    suspend fun readCustomEntries(): List<BatteryInfo> = coroutineScope {
        customEntries.first().map { entry ->
            async(Dispatchers.IO) {
                val raw = readBatteryInfo("", entry.path) ?: context.getString(R.string.unknown)
                val scaled = raw.toString().toDoubleOrNull()?.let { value ->
                    value * 10.0.pow(entry.scale)
                }?.let { "%.0f".format(it) } ?: raw
                BatteryInfo(
                    type = BatteryInfoType.CUSTOM,
                    value = buildString {
                        append(scaled)
                        if (entry.unit.isNotBlank()) append(' ').append(entry.unit)
                    },
                    customTitle  = resolveTitle(context, entry.title),
                )
            }
        }.awaitAll()
    }

    suspend fun exportEntriesToDownloads(
        ctx: Context,
        fileName: String = "battery_entries_${System.currentTimeMillis()}.json"
    ): Uri = withContext(Dispatchers.IO) {

        val json = Json.encodeToString(customEntries.first())

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create download entry")

        resolver.openOutputStream(uri).use { it?.write(json.toByteArray()) }

        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        uri
    }

    suspend fun importFromUri(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val jsonText = ctx.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.readText()
            ?: throw IOException("Cannot read JSON")

        val incoming = Json.decodeFromString<List<CustomEntry>>(jsonText)

        mergeAndSave(incoming)
    }

    private suspend fun mergeAndSave(incoming: List<CustomEntry>) {
        settings.edit { prefs ->
            val current = prefs[CUSTOM_ENTRIES]
                ?.let { Json.decodeFromString<List<CustomEntry>>(it) }
                .orEmpty()

            val merged = (current + incoming).associateBy { it.path }.values.toList()

            prefs[CUSTOM_ENTRIES] = Json.encodeToString(merged)
        }
    }

    private val titleResMap = mapOf(
        "cycle_count"        to R.string.battery_cycle_count,
        "charge_full"        to R.string.full_charge_capacity,
        "charge_full_design" to R.string.design_capacity,
        // modify this after presets are changed
    )

    private fun resolveTitle(ctx: Context, key: String): String =
        titleResMap[key]?.let(ctx::getString) ?: key

    suspend fun importPreset(ctx: Context, name: String) = withContext(Dispatchers.IO) {
        val json = ctx.assets.open("profiles/$name.json").bufferedReader().readText()
        val list = Json.decodeFromString<List<CustomEntry>>(json)
        mergeAndSave(list)
    }

    suspend fun getAvailableBatteryInfo(isRoot: Boolean, showOplus: Boolean): List<BatteryInfo> {
        return if (isRoot) {
            val infoList = (getBasicBatteryInfo(allowRootFallback = true) + getRootBatteryInfo() + readCustomEntries()).toMutableList()
            // OPlus-only entries are meaningless without /sys/class/oplus_chg
            if (!showOplus || detectRootBackend() != RootBackend.OPLUS) {
                infoList.removeAll { it.type in OPLUS_TYPES }
            }
            infoList
        } else {
            val infoList = (getBasicBatteryInfo() + getNonRootVoltCurrPwr()).toMutableList()
            val savedFcc = estimatedFccFlow.first()
            infoList.add(getEstimatedFcc(savedFcc))
            infoList
        }
    }
}