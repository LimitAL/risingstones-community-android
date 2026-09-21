package top.cxmeow.risingstones.feature.personaldata.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDataDashboardImagesTest {
    @Test
    fun unknownIdentifiersDoNotCreateResourceUrls() {
        for (id in listOf(Int.MIN_VALUE, -1, 0)) {
            assertNull(personalDataAchievementIconUrl(id))
            assertNull(personalDataRaidImageUrl(id))
        }
    }

    @Test
    fun achievementIconsHaveSixDigitNamesWithoutAnItemDirectory() {
        assertEquals("$base/achievements/icon/001103_hr1.png", personalDataAchievementIconUrl(1103))
        assertEquals("$base/achievements/icon/038211_hr1.png", personalDataAchievementIconUrl(38211))
        assertEquals("$base/achievements/icon/1000000_hr1.png", personalDataAchievementIconUrl(1_000_000))
    }

    @Test
    fun raidLoadingImagesUseTheirUnpaddedIdentifier() {
        assertEquals("$base/savage/loading/1_hr1.png", personalDataRaidImageUrl(1))
        assertEquals("$base/savage/loading/112644_hr1.png", personalDataRaidImageUrl(112644))
        assertEquals("$base/savage/loading/2147483647_hr1.png", personalDataRaidImageUrl(Int.MAX_VALUE))
    }

    @Test
    fun resourceNamesUseAsciiDigitsRegardlessOfLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("$base/achievements/icon/001103_hr1.png", personalDataAchievementIconUrl(1103))
            assertEquals("$base/savage/loading/112644_hr1.png", personalDataRaidImageUrl(112644))
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        const val base = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones"
    }
}
