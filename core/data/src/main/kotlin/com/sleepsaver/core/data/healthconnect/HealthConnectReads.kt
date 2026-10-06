package com.sleepsaver.core.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.reflect.KClass

/** Reads every page of [type] in [filter], from all origins. Errors propagate. */
internal suspend fun <T : Record> HealthConnectClient.readAllRecords(
    type: KClass<T>,
    filter: TimeRangeFilter,
    pageSize: Int = 1000,
): List<T> {
    val records = mutableListOf<T>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageSize = pageSize, pageToken = pageToken),
        )
        records += response.records
        pageToken = response.pageToken?.takeIf { it.isNotEmpty() }
    } while (pageToken != null)
    return records
}
