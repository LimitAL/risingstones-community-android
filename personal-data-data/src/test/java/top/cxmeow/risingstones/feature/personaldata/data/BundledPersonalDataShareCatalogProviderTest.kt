package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareImage
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareKind
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomShareWeaponCategory

class BundledPersonalDataShareCatalogProviderTest {
    @Test
    fun bundledSnapshotRecordsBothReviewedSourcesAndFixedCounts() = runBlocking {
        val document = Json.parseToJsonElement(resource()).jsonObject
        assertEquals("1", document.getValue("formatVersion").jsonPrimitive.content)
        val provenance = document.getValue("provenance").jsonObject
        assertEquals("2026-09-20", provenance.getValue("capturedOn").jsonPrimitive.content)
        val sources = provenance.getValue("sources").jsonArray
        assertEquals(2, sources.size)
        assertEquals(
            listOf("chunk-f27e63c4.14a654ff.js", "chunk-7690576a.97d2f158.js"),
            sources.map { it.jsonObject.getValue("url").jsonPrimitive.content.substringAfterLast('/') },
        )
        assertTrue(sources.all {
            it.jsonObject.getValue("sha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}"))
        })
        val catalogs = BundledPersonalDataShareCatalogProvider().fetchShareCatalogs()
        assertEquals(7, catalogs.ultimateAchievements.size)
        assertEquals(24, catalogs.phantomJobs.size)
        assertEquals(44, catalogs.weaponItemCategories.size)
    }

    @Test
    fun catalogJoinsWeaponCategoriesFromTheReviewedSupplementaryDirectory() = runBlocking {
        val catalogs = BundledPersonalDataShareCatalogProvider().fetchShareCatalogs()

        assertEquals(
            PhantomShareWeaponCategory(2, "单手剑"),
            catalogs.weaponItemCategories.getValue(47869),
        )
        assertEquals(
            PhantomShareWeaponCategory(11, "盾"),
            catalogs.weaponItemCategories.getValue(47890),
        )
        assertEquals((0..23).toList(), catalogs.phantomJobs.map { it.id })
        assertEquals("辅助自由人", catalogs.phantomJobs.first().name)
        assertEquals(82271, catalogs.phantomJobs.first().iconId)
        assertEquals(setOf(733, 777, 887, 968, 1122, 1238, 1363),
            catalogs.ultimateAchievements.map { it.territoryType }.toSet())
    }

    @Test
    fun imageAndPageUrlsFollowTheReviewedOfficialMappings() {
        val provider = BundledPersonalDataShareCatalogProvider()

        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_3d76515f2ed4ff71.png",
            provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Fishing)))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_83038ecea0a484cc.png",
            provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Glamour)))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_4a3bbd691f4c3875.png",
            provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Frontline)))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_85a1d50894a27f02.png",
            provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Occult)))
        assertEquals("https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/savage/default.png", provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Savage)))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_191b305bd9531bfa.png", provider.shareImageUrl(PersonalDataShareImage.Cover(PersonalDataShareKind.Ultimate)))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_b09dfbeb911eabbb.png",
            provider.shareImageUrl(PersonalDataShareImage.Logo))
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_52f521799906ed0b.png",
            provider.shareImageUrl(PersonalDataShareImage.GameLogo))
        assertEquals("https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult/job/082271_hr1.png",
            provider.shareImageUrl(PersonalDataShareImage.PhantomJobIcon(82271)))
        assertEquals("https://ff14risingstones.web.sdo.com/pc/index.html#/statistics/frontline",
            provider.sharePageUrl(PersonalDataShareKind.Frontline))
        assertEquals("https://ff14risingstones.web.sdo.com/pc/index.html#/statistics/ultimate",
            provider.sharePageUrl(PersonalDataShareKind.Ultimate))
    }

    @Test
    fun malformedOrUnjoinableCatalogsFailInsteadOfInventingCategoryNames() = runBlocking {
        val categories = BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs().vanityCategories
        val valid = resource()
        val invalid = listOf(
            "{}",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"itemUiCategoryId\":2", "\"itemUiCategoryId\":999"),
            valid.replace("\"id\":23", "\"id\":22"),
        )
        invalid.forEach { text ->
            assertSame(
                PersonalDataException.MissingPayload,
                org.junit.Assert.assertThrows(PersonalDataException::class.java) {
                    decodePersonalDataShareCatalog(text, categories)
                },
            )
        }
    }

    private fun resource(): String = checkNotNull(
        javaClass.getResourceAsStream(
            "/top/cxmeow/risingstones/feature/personaldata/data/official-share-catalog.json",
        ),
    ).use { it.readBytes().decodeToString() }
}
