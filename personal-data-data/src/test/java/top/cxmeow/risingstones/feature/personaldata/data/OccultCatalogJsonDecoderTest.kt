package top.cxmeow.risingstones.feature.personaldata.data

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

class OccultCatalogJsonDecoderTest {
    // Source fixture: iOS 611ccb7 PersonalDataTests.OccultCatalogHTTPClient, not a live account response.
    private val fixture = javaClass.getResourceAsStream("/occult-native-catalog.json")!!.use { it.readBytes() }
    @Test fun decodesCompleteNativeCatalogIncludingDynamicSoulIds() {
        val catalog = OccultCatalogJsonDecoder().decode(fixture)
        assertEquals(2, catalog.version)
        assertEquals(1, catalog.schemaVersion)
        assertEquals(3, catalog.supportJobs.size)
        assertEquals("辅助骑士", catalog.supportJobs[1].name)
        assertEquals("Phantom Paladin", catalog.supportJobs[1].nameEnglish)
        assertEquals(6, catalog.supportJobs[1].levelMax)
        assertEquals(5, catalog.weaponStages.size)
        assertEquals(listOf(50802), catalog.phaseResources.halfSoulCrystalItemIds)
        assertEquals(47979, catalog.itemDirectory.single().itemId)
        assertEquals(listOf(50974, 50975, 50976), catalog.phaseResources.eclipticumCrystals.map { it.itemId })
        assertNull(catalog.phaseResources.eclipticumCrystals.first().itemUICategoryId)
    }
    @Test fun unsupportedAndMalformedSchemasRemainErrors() {
        try { OccultCatalogJsonDecoder().decode(fixture.decodeToString().replace("\"schemaVersion\":1", "\"schemaVersion\":2").encodeToByteArray()); fail() }
        catch (error: OccultCatalogException.UnsupportedSchema) { assertEquals(2, error.version) }
        for (document in listOf("{}", "[]", "not-json")) {
            try { OccultCatalogJsonDecoder().decode(document.encodeToByteArray()); fail() }
            catch (_: OccultCatalogException.InvalidResponse) { }
        }
    }
    @Test fun onlyReviewedItemHostAndTokenAreAccepted() {
        val base = "https://ff14-eo.web.sdo.com/ffstones/item/icon/new_token-1"
        assertEquals(base, validatedOccultItemIconBaseUrl(base))
        for (url in listOf("http://ff14-eo.web.sdo.com/ffstones/item/icon/a", "$base/x", "$base?q=x", "$base#x",
            "https://user@ff14-eo.web.sdo.com/ffstones/item/icon/a", "https://evil.test/ffstones/item/icon/a", "https://ff14-eo.web.sdo.com/ffstones/item/icon/")) {
            assertNull(validatedOccultItemIconBaseUrl(url))
        }
        val catalog = OccultCatalogJsonDecoder().decode(fixture.decodeToString().replace(DefaultOccultItemIconBaseUrl, "https://evil.test/icon").encodeToByteArray())
        assertEquals(DefaultOccultItemIconBaseUrl, catalog.itemIconBaseUrl)
    }
    @Test fun reviewedStaticAndDynamicIconPathsAndNegativeIds() {
        assertEquals("$DefaultOccultItemIconBaseUrl/030000/030694_hr1.png", occultGameItemIconUrl(30694))
        assertEquals("https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/job/082272_hr1.png", occultStaticIconUrl(82272, OccultStaticIconKind.SupportJob))
        assertEquals("https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/item/026039_hr1.png", occultStaticIconUrl(26039, OccultStaticIconKind.Item))
        assertEquals("https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/026025_hr1.png", occultStaticIconUrl(26025, OccultStaticIconKind.PhaseResource))
        assertNull(occultGameItemIconUrl(-1))
        assertNull(occultStaticIconUrl(-1, OccultStaticIconKind.Item))
    }
}
