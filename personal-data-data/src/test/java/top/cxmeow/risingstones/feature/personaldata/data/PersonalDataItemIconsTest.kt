package top.cxmeow.risingstones.feature.personaldata.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDataItemIconsTest {
    @Test
    fun nonPositiveIdentifiersHaveNoIcon() {
        for (iconId in listOf(Int.MIN_VALUE, -1, 0)) {
            assertNull(personalDataItemIconUrl(iconId))
        }
    }

    @Test
    fun sixDigitNamesUseTheContainingThousandDirectory() {
        val expectedPaths = mapOf(
            1 to "000000/000001_hr1.png",
            999 to "000000/000999_hr1.png",
            1_000 to "001000/001000_hr1.png",
            1_001 to "001000/001001_hr1.png",
            42_192 to "042000/042192_hr1.png",
            999_999 to "999000/999999_hr1.png",
        )
        for ((iconId, path) in expectedPaths) {
            assertEquals("iconId=$iconId", "$baseUrl/$path", personalDataItemIconUrl(iconId))
        }
    }

    @Test
    fun largerIdentifiersAreNeitherTruncatedNorOverflowed() {
        assertEquals("$baseUrl/1000000/1000000_hr1.png", personalDataItemIconUrl(1_000_000))
        assertEquals("$baseUrl/2147483000/2147483647_hr1.png", personalDataItemIconUrl(Int.MAX_VALUE))
    }

    @Test
    fun localeWithNonLatinDigitsDoesNotChangeResourceAddress() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("$baseUrl/042000/042192_hr1.png", personalDataItemIconUrl(42_192))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private companion object {
        const val baseUrl = "https://ff14-eo.web.sdo.com/ffstones/item/icon/dcsvv4fowz2m"
    }
}
