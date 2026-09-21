package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOceanFishCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogProvider

class BundledPersonalDataSupplementaryCatalogProviderTest {
    @Test
    fun independentClasspathResourceContainsReviewedPublicSourcesAndCounts() = runBlocking {
        val stream = javaClass.getResourceAsStream(
            "/top/cxmeow/risingstones/feature/personaldata/data/official-supplementary-catalogs.json",
        ) ?: error("Missing supplementary resource")
        val document = stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        assertEquals("1", document.getValue("formatVersion").jsonPrimitive.content)
        assertFalse(document.containsKey("schemaVersion"))
        val provenance = document.getValue("provenance").jsonObject
        assertEquals("2026-09-20", provenance.getValue("capturedOn").jsonPrimitive.content)
        val sources = provenance.getValue("sources").jsonArray
        assertEquals(3, sources.size)
        sources.forEach {
            val source = it.jsonObject
            assertTrue(source.getValue("url").jsonPrimitive.content.startsWith(
                "https://ff14risingstones.web.sdo.com/mob/static/js/chunk-"))
            assertTrue(source.getValue("sha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        }
        val counts = provenance.getValue("rawCounts").jsonObject
        assertEquals("116", counts.getValue("vanityCategories").jsonPrimitive.content)
        assertEquals(13, BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs().oceanFish.size)
    }

    @Test
    fun originalProviderKeepsItsCatalogAndExposesSupplementaryTrait() = runBlocking {
        val provider = BundledPersonalDataCatalogProvider()
        val supplementary: PersonalDataSupplementaryCatalogProvider = provider
        val original = provider.fetchCatalogs()
        assertEquals(335, original.fish.size)
        assertEquals(618, original.glamour!!.sets.size)
        assertEquals(BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs(),
            supplementary.fetchSupplementaryCatalogs())
    }

    @Test
    fun oceanFishPreserveAllThirteenOfficialEntriesWithoutInventingPatch() = runBlocking {
        val rows = BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs().oceanFish
        assertEquals(13, rows.size)
        assertEquals(13, rows.map { it.itemId }.toSet().size)
        assertEquals(PersonalDataOceanFishCatalogEntry(29788, 28009, "索蒂斯"), rows.first())
        assertEquals(PersonalDataOceanFishCatalogEntry(51247, 28156, "摩那苏婆帝"), rows.last())
        assertTrue(rows.all { it.itemId > 0 && it.iconId > 0 && it.name.isNotBlank() })
    }

    @Test
    fun achievementCatalogsKeepNamesDetailsAndDoNotInventFrontlineIconIdentifiers() = runBlocking {
        val rows = BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs()
        assertEquals(35, rows.fishingAchievements.size)
        assertEquals(35, rows.frontlineAchievements.size)
        assertEquals(35, rows.fishingAchievements.map { it.achievementId }.toSet().size)
        assertEquals(35, rows.frontlineAchievements.map { it.achievementId }.toSet().size)
        val fishing = rows.fishingAchievements.last()
        assertEquals(3985, fishing.achievementId)
        assertEquals("太公奇绝", fishing.name)
        assertEquals("达成“太公封神”“金曦太公3”成就。", fishing.detail)
        assertEquals(1145, fishing.iconId)
        assertTrue(rows.fishingAchievements.all { it.iconId != null && it.iconId!! > 0 })
        assertTrue(rows.frontlineAchievements.all { it.iconId == null && !it.detail.isNullOrBlank() })
        assertEquals(934, rows.frontlineAchievements.first().achievementId)
        assertEquals("沃刻其特战役6", rows.frontlineAchievements.last().name)
    }

    @Test
    fun hiddenVanityChoicesRemainAvailableForAllCategoryJoins() = runBlocking {
        val rows = BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs().vanityCategories
        assertEquals(36, rows.size)
        assertEquals(mapOf(1 to 25, 3 to 6, 4 to 5), rows.groupingBy { it.majorOrder }.eachCount())
        assertEquals(setOf(7, 9, 62), rows.filterNot { it.selectable }.map { it.id }.toSet())
        assertEquals(33, rows.count { it.selectable })
        assertTrue(rows.none { it.id == 0 || it.id == 999 })
        assertTrue(rows.all { it.name.isNotBlank() && it.iconId != null })
        assertEquals(rows, rows.sortedWith(compareBy({ it.majorOrder }, { it.minorOrder }, { it.id })))
        assertEquals("双手咒杖", rows.single { it.id == 7 }.name)
        assertEquals(listOf(11, 34, 35, 37, 36, 38), rows.filter { it.majorOrder == 3 }.map { it.id })
    }

    @Test
    fun mutatingReturnedListsDoesNotAlterTheOfflineSnapshot() = runBlocking {
        val provider = BundledPersonalDataSupplementaryCatalogProvider()
        val first = provider.fetchSupplementaryCatalogs()
        (first.oceanFish as MutableList).clear()
        (first.fishingAchievements as MutableList).clear()
        (first.frontlineAchievements as MutableList).clear()
        (first.vanityCategories as MutableList).clear()
        val second = provider.fetchSupplementaryCatalogs()
        assertEquals(listOf(13, 35, 35, 36), listOf(second.oceanFish.size, second.fishingAchievements.size,
            second.frontlineAchievements.size, second.vanityCategories.size))
    }

    @Test
    fun cancellationPropagatesBeforeOfflineDelivery() {
        val cancellation = CancellationException("fixture cancellation")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                currentCoroutineContext().cancel(cancellation)
                BundledPersonalDataSupplementaryCatalogProvider().fetchSupplementaryCatalogs()
            }
        }
        assertSame(cancellation, error)
    }
}
