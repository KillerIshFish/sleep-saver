package com.sleepsaver.core.data.export

import com.sleepsaver.core.domain.model.Night
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The v2 CSV columns for one wake date; marks are stored mark names. */
internal data class CsvExtras(val morningFeel: Int?, val marks: List<String>, val anchorLatencyMin: Long?) {
    companion object {
        /** No Morning feel, no marks, no anchor latency. */
        val NONE = CsvExtras(morningFeel = null, marks = emptyList(), anchorLatencyMin = null)
    }
}

/**
 * The nights CSV: a header row, then one row per assembled night (a day with only naps has empty sleep times).
 * RFC 4180: CRLF line ends, fields containing a comma, quote, CR or LF are quoted with inner quotes doubled.
 * Local times use the offset they were recorded in; stage columns are empty for nights without stage data.
 * The last three columns come from [CsvExtras]: Morning feel (1..5), the stored marks (lowercased, `;`-joined, e.g.
 * "alcohol;late_meal") and the Drop anchor latency as shown ("about 15 min" → 15); each is empty when absent.
 */
internal object NightsCsv {
    val HEADER = listOf(
        "wake_date",
        "night_of",
        "fell_asleep_local",
        "woke_up_local",
        "total_min",
        "main_min",
        "awake_min",
        "nap_min",
        "deep_min",
        "light_min",
        "rem_min",
        "efficiency_pct",
        "interruptions",
        "source",
        "edited",
        "manual",
        "morning_feel",
        "marks",
        "anchor_latency_min",
    )

    private const val LINE_END = "\r\n"
    private val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun write(nights: List<Night>, out: Appendable, extras: (LocalDate) -> CsvExtras = { CsvExtras.NONE }) {
        out.appendRow(HEADER)
        nights.forEach { out.appendRow(row(it, extras(it.wakeDate))) }
    }

    fun row(night: Night, extras: CsvExtras = CsvExtras.NONE): List<String> {
        val stages = night.stageTotals?.takeIf { it.hasStageData }
        return listOf(
            night.wakeDate.toString(),
            night.nightOf.toString(),
            localTime(night.fellAsleep, night.fellAsleepOffset),
            localTime(night.wokeUp, night.wokeUpOffset),
            night.totalSleep.toMinutes().toString(),
            night.mainSleep.toMinutes().toString(),
            night.awakeInMain.toMinutes().toString(),
            night.napSleep.toMinutes().toString(),
            stages?.deep?.toMinutes()?.toString().orEmpty(),
            stages?.light?.toMinutes()?.toString().orEmpty(),
            stages?.rem?.toMinutes()?.toString().orEmpty(),
            night.efficiency?.let { (it * 100).roundToInt().toString() }.orEmpty(),
            night.interruptions.size.toString(),
            night.origin.orEmpty(),
            night.isEdited.toString(),
            night.isManual.toString(),
            extras.morningFeel?.toString().orEmpty(),
            extras.marks.joinToString(";") { it.lowercase() },
            extras.anchorLatencyMin?.toString().orEmpty(),
        )
    }

    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun Appendable.appendRow(fields: List<String>) {
        fields.forEachIndexed { i, field ->
            if (i > 0) append(',')
            append(escape(field))
        }
        append(LINE_END)
    }

    private fun localTime(instant: Instant?, offset: ZoneOffset?): String =
        instant?.let { LocalDateTime.ofInstant(it, offset ?: ZoneOffset.UTC).format(LOCAL_TIME_FORMAT) }.orEmpty()
}
