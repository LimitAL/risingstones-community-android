package top.cxmeow.risingstones.feature.personaldata.ui.compose

import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateRecordTime

/** Preserve source precision; only timestamps with an offset are converted into the display zone. */
internal fun ultimateDateText(value: UltimateRecordTime?, zone: ZoneId, locale: Locale): String? = try {
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    when (value) {
        is UltimateRecordTime.CalendarDate -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value.value)
        is UltimateRecordTime.LocalTime -> dateTime.format(value.value)
        is UltimateRecordTime.OffsetTime -> dateTime.format(value.value.atZone(zone))
        null -> null
    }
} catch (_: DateTimeException) { null }
