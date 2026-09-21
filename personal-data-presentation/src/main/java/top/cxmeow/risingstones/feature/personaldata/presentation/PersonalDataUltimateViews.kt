package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import top.cxmeow.risingstones.feature.personaldata.domain.*

data class UltimatePlotPoint(val sourceIndex: Int, val record: UltimateDeathRecord, val x: Double, val y: Double)
data class UltimatePlotAxis(val min: Double, val max: Double, val interval: Double, val splitNumber: Int)
data class UltimateDeathPlot(val points: List<UltimatePlotPoint>, val axis: UltimatePlotAxis, val excludedCount: Int)

/** Local projections retain the original records, including duplicate and unknown observations. */
object PersonalDataUltimateViews {
    fun records(rows: List<PersonalDataUltimateRecord>): List<PersonalDataUltimateRecord> = rows.distinctBy { it.territoryType }

    fun totalClears(rows: List<PersonalDataUltimateRecord>): Long? {
        var result = 0L
        for (row in records(rows)) {
            val count = row.clearCount ?: return null
            if (count < 0 || count > Long.MAX_VALUE - result) return null
            result += count
        }
        return result
    }

    fun party(rows: List<UltimatePartyMember>, order: (String) -> Int?): List<UltimatePartyMember> = rows.sortedWith(
        compareBy<UltimatePartyMember> { row -> row.jobName?.let(order)?.takeIf { it >= 0 } ?: Int.MAX_VALUE },
    )
    fun jobs(rows: List<UltimateJobUsage>): List<UltimateJobUsage> = rows.sortedWith(compareByDescending { it.times?.takeIf { count -> count >= 0 } })
    fun partners(rows: List<UltimateCompanion>): List<UltimateCompanion> = rows.sortedWith(compareByDescending { it.jointEntries?.takeIf { count -> count >= 0 } })

    fun phases(rows: List<UltimatePhaseRecord>, zone: ZoneId): List<UltimatePhaseRecord> = rows.sortedWith(
        compareBy<UltimatePhaseRecord> { timeInstant(it.reachedAt, zone) == null }.thenBy { timeInstant(it.reachedAt, zone) },
    )

    /** Date-only values use that day's start for comparison, without changing their display type. */
    fun timeInstant(time: UltimateRecordTime?, zone: ZoneId): Instant? = try { when (time) {
        is UltimateRecordTime.CalendarDate -> time.value.atStartOfDay(zone).toInstant()
        is UltimateRecordTime.LocalTime -> time.value.atZone(zone).toInstant()
        is UltimateRecordTime.OffsetTime -> time.value
        null -> null
    } } catch (_: DateTimeException) { null }

    fun deathPlot(rows: List<UltimateDeathRecord>, territoryType: Int): UltimateDeathPlot {
        val points = rows.mapIndexedNotNull { index, record ->
            val rawX = record.x?.takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
            val rawY = record.y?.takeIf(Double::isFinite) ?: return@mapIndexedNotNull null
            val x = if (territoryType == 733) rawX else rawX - 100.0
            val y = if (territoryType == 733) -rawY else 100.0 - rawY
            if (x.isFinite() && y.isFinite()) UltimatePlotPoint(index, record, x, y) else null
        }
        val extent = points.maxOfOrNull { maxOf(abs(it.x), abs(it.y)) }?.takeIf { it > 0.0 } ?: 1.0
        val interval = niceInterval((extent / 5.0).takeIf { it > 0.0 } ?: Double.MIN_VALUE)
        val niceBound = interval * 5.0
        // Extremely large finite doubles have no representable larger nice bound.
        val axis = if (niceBound.isFinite() && niceBound >= extent) UltimatePlotAxis(-niceBound, niceBound, interval, 10)
        else UltimatePlotAxis(-extent, extent, extent / 5.0, 10)
        return UltimateDeathPlot(points, axis, rows.size - points.size)
    }

    private fun niceInterval(value: Double): Double {
        val power = 10.0.pow(floor(log10(value)))
        if (power == 0.0 || !power.isFinite()) return value
        val fraction = value / power
        val multiple = when { fraction <= 1 -> 1; fraction <= 2 -> 2; fraction <= 5 -> 5; else -> 10 }
        return multiple * power
    }
}
