package top.cxmeow.risingstones.feature.personaldata.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import top.cxmeow.risingstones.feature.personaldata.domain.*

/** Converts the already-read exploration batch; it never performs a second item or aether read. */
internal fun phantomWeaponSnapshot(overview: ExplorationOverview): PhantomWeaponExplorationSnapshot {
    if (overview.board != ExplorationBoard.OccultCrescent) throw ExplorationException.InvalidResponse
    if (!overview.available) return PhantomWeaponExplorationSnapshot(overview, null, null)
    val items = overview.sections.firstOrNull { it.kind == ExplorationSectionKind.AcquiredItems }
    val aether = overview.sections.firstOrNull { it.kind == ExplorationSectionKind.Aether }
    return PhantomWeaponExplorationSnapshot(
        overview = overview,
        items = PhantomWeaponItemSection(
            records = items?.records.orEmpty().map { record ->
                PhantomWeaponItemRecord(
                    itemId = record.itemId?.takeIf { it > 0 },
                    category = record.field(ExplorationFieldKind.ItemCategory),
                    quantity = record.field(ExplorationFieldKind.Quantity).exactCount(),
                    firstAcquiredAt = record.field(ExplorationFieldKind.FirstAcquiredAt)?.recordTime(),
                    name = record.field(ExplorationFieldKind.ItemName) ?: record.title.takeIf(String::isNotBlank),
                )
            },
            failure = if (items == null) ExplorationFailure.InvalidResponse else items.failure,
        ),
        aether = PhantomWeaponAetherSection(
            records = aether?.records.orEmpty().map { record ->
                PhantomWeaponAetherRecord(record.field(ExplorationFieldKind.AetherColor),
                    record.field(ExplorationFieldKind.AetherPoints).exactCount())
            },
            failure = if (aether == null) ExplorationFailure.InvalidResponse else aether.failure,
        ),
    )
}

private fun ExplorationRecord.field(kind: ExplorationFieldKind): String? =
    fields.firstOrNull { it.kind == kind }?.value?.takeIf(String::isNotBlank)

private fun String?.exactCount(): Long? = try {
    this?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.longValueExact()
} catch (_: ArithmeticException) { null }

private val phantomSpacedTime = DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME).toFormatter().withResolverStyle(ResolverStyle.STRICT)

private fun String.recordTime(): PhantomWeaponRecordTime? {
    val value = trim()
    val parsers = listOf<() -> PhantomWeaponRecordTime>(
        { PhantomWeaponRecordTime.CalendarDate(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)) },
        { PhantomWeaponRecordTime.LocalTime(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)) },
        { PhantomWeaponRecordTime.LocalTime(LocalDateTime.parse(value, phantomSpacedTime)) },
        { PhantomWeaponRecordTime.OffsetTime(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()) },
        { PhantomWeaponRecordTime.OffsetTime(Instant.parse(value)) },
    )
    for (parse in parsers) {
        try { return parse() } catch (_: DateTimeParseException) { }
    }
    return null
}
