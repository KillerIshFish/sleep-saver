package com.sleepsaver.hcseeder

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Headless helper driven by `adb shell am start` extras. Grant WRITE_SLEEP first:
 * `adb shell pm grant com.sleepsaver.hcseeder android.permission.health.WRITE_SLEEP`. Results go to logcat (tag HcSeeder).
 *
 *     adb shell am start -n com.sleepsaver.hcseeder/.SeedActivity --es action seed --ei nights 30 --ez lastNight false
 *     adb shell am start -n com.sleepsaver.hcseeder/.SeedActivity --es action lastnight   # "Garmin synced this morning"
 *     adb shell am start -n com.sleepsaver.hcseeder/.SeedActivity --es action clear
 */
class SeedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "seed"
        val nights = intent.getIntExtra("nights", 30)
        val lastNight = intent.getBooleanExtra("lastNight", true)
        lifecycleScope.launch {
            runCatching {
                val client = HealthConnectClient.getOrCreate(this@SeedActivity)
                when (action) {
                    "clear" -> clear(client)
                    "seed" -> seed(client, nights, lastNight)
                    "lastnight" -> seed(client, 1, lastNight = true, onlyNewest = true)
                    else -> Log.w(TAG, "unknown action $action")
                }
            }.onFailure { Log.e(TAG, "failed: $action", it) }
            finish()
        }
    }

    private suspend fun seed(client: HealthConnectClient, nights: Int, lastNight: Boolean, onlyNewest: Boolean = false) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var sessions = SampleData.sessions(today = today, zone = zone, count = nights, includeLastNight = lastNight)
        if (onlyNewest) {
            val newestWake = sessions.maxOf { it.end }
            sessions = sessions.filter { !it.end.isBefore(newestWake.minus(14, ChronoUnit.HOURS)) }
        }
        val device = Device(type = Device.TYPE_WATCH, manufacturer = "Seeder", model = "Emulator")
        val records = sessions.map { s ->
            SleepSessionRecord(
                startTime = s.start,
                startZoneOffset = s.startOffset,
                endTime = s.end,
                endZoneOffset = s.endOffset,
                metadata = Metadata.autoRecorded(device = device, clientRecordId = s.id),
                stages = s.stages.map { SleepSessionRecord.Stage(it.start, it.end, it.type.healthConnectValue) },
            )
        }
        val ids = client.insertRecords(records).recordIdsList
        Log.i(TAG, "inserted ${ids.size} sleep sessions (nights=$nights, lastNight=$lastNight)")
    }

    private suspend fun clear(client: HealthConnectClient) {
        val now = Instant.now()
        client.deleteRecords(
            SleepSessionRecord::class,
            TimeRangeFilter.between(now.minus(400, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS)),
        )
        Log.i(TAG, "deleted this app's sleep sessions")
    }

    private companion object {
        const val TAG = "HcSeeder"
    }
}
