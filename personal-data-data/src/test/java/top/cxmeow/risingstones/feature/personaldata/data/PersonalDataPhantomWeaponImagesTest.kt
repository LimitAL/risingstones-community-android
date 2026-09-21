package top.cxmeow.risingstones.feature.personaldata.data

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponElement

class PersonalDataPhantomWeaponImagesTest {
    @Test fun theSixSoulCrystalAndThreeDemiatmaIconsKeepTheirDistinctOfficialPaths() = runBlocking {
        val catalog = BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog()
        catalog.soulCrystals.forEach { assertEquals("$base/0${it.iconId}_hr1.png", phantomWeaponItemIconUrl(it.iconId)) }
        catalog.demiatma.forEach { assertEquals("$base/item/0${it.iconId}_hr1.png", phantomWeaponItemIconUrl(it.iconId)) }
    }

    @Test fun weaponAndOtherPositiveItemIconsUseTheReviewedThousandDirectoryFormula() = runBlocking {
        val catalog = BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog()
        catalog.weapons.forEach { assertEquals(personalDataItemIconUrl(it.iconId), phantomWeaponItemIconUrl(it.iconId)) }
        assertEquals("$itemBase/000000/000999_hr1.png", phantomWeaponItemIconUrl(999))
        assertEquals("$itemBase/001000/001000_hr1.png", phantomWeaponItemIconUrl(1000))
        assertEquals("$itemBase/030000/030704_hr1.png", phantomWeaponItemIconUrl(30704))
    }

    @Test fun invalidNumbersDoNotAcquireMaterialOrProcessImages() {
        for (icon in listOf(Int.MIN_VALUE, -1, 0)) assertNull(phantomWeaponItemIconUrl(icon))
        for (step in listOf(Int.MIN_VALUE, -1, 0, 6, 100, Int.MAX_VALUE)) assertNull(phantomWeaponLensImageUrl(step))
    }

    @Test fun colorNamesMapToTheFourOfficialElementFiles() {
        val files = mapOf(PhantomWeaponElement.Yellow to "earth", PhantomWeaponElement.Red to "fire",
            PhantomWeaponElement.Blue to "water", PhantomWeaponElement.Green to "wind")
        files.forEach { (element, file) -> assertEquals("$base/$file.png", phantomWeaponElementIconUrl(element)) }
    }

    @Test fun allFourLensProcessesAndTheCompletionImageAreSeparate() {
        val hashes = listOf("c2497d0941516b81", "bf9ca5eb4a3b8e76", "611b7f59f8536f19", "ec05a7bf5a8e6ea2", "98769429037e6712")
        hashes.forEachIndexed { index, hash ->
            assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_$hash.png", phantomWeaponLensImageUrl(index + 1))
        }
    }

    @Test fun resourceLocationsStayOnReviewedHttpsHostsWithoutUserInfoOrQuery() {
        val urls = listOfNotNull(phantomWeaponItemIconUrl(30704), phantomWeaponItemIconUrl(26025),
            phantomWeaponItemIconUrl(26229), phantomWeaponElementIconUrl(PhantomWeaponElement.Green), phantomWeaponLensImageUrl(5))
        urls.forEach { value ->
            val uri = URI(value)
            assertEquals("https", uri.scheme)
            assertTrue(uri.host in setOf("ff14-eo.web.sdo.com", "static.web.sdo.com", "ff14risingstones.web.sdo.com"))
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
    }

    @Test fun localeCannotChangePaddingOrMaterialDispatch() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("$base/026025_hr1.png", phantomWeaponItemIconUrl(26025))
            assertEquals("$base/item/026229_hr1.png", phantomWeaponItemIconUrl(26229))
            assertEquals("$itemBase/030000/030704_hr1.png", phantomWeaponItemIconUrl(30704))
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        const val base = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult"
        const val itemBase = "https://ff14-eo.web.sdo.com/ffstones/item/icon/dcsvv4fowz2m"
    }
}
