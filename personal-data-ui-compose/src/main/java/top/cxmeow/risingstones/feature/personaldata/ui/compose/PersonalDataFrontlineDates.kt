package top.cxmeow.risingstones.feature.personaldata.ui.compose

import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlineDayStamp

/** Local timestamps have no implicit source zone. Only an offset timestamp is converted for display. */
internal fun frontlineDateText(value: FrontlineDayStamp?, zone: ZoneId, locale: Locale): String? = try {
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    when (value) {
        is FrontlineDayStamp.CalendarDate -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value.value)
        is FrontlineDayStamp.LocalTime -> dateTime.format(value.value)
        is FrontlineDayStamp.OffsetTime -> dateTime.format(value.value.atZone(zone))
        null -> null
    }
} catch (_: DateTimeException) { null }
