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
import top.cxmeow.risingstones.feature.personaldata.domain.FishKingCatalogEntry
import top.cxmeow.risingstones.feature.personaldata.domain.GlamourCatalogSetItem
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataCatalogProvider

class BundledPersonalDataCatalogProviderTest {
    @Test
    fun packagedResourceLoadsOnJvmWithoutAndroidContextAndCarriesPublicProvenance() = runBlocking {
        val resource = BundledPersonalDataCatalogProvider::class.java.getResourceAsStream(
            "/top/cxmeow/risingstones/feature/personaldata/data/official-catalogs.json",
        ) ?: error("Missing packaged catalog")
        val document = resource.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        assertEquals("1", document.getValue("formatVersion").jsonPrimitive.content)
        assertFalse(document.containsKey("schemaVersion"))
        val provenance = document.getValue("provenance").jsonObject
        assertEquals("2026-09-20", provenance.getValue("capturedOn").jsonPrimitive.content)
        val sources = provenance.getValue("sources").jsonArray
        assertEquals(3, sources.size)
        sources.forEach { element ->
            val source = element.jsonObject
            assertTrue(source.getValue("url").jsonPrimitive.content.startsWith(
                "https://ff14risingstones.web.sdo.com/mob/static/js/chunk-"))
            assertTrue(source.getValue("sha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        }
        val provider: PersonalDataCatalogProvider = BundledPersonalDataCatalogProvider()
        assertEquals(335, provider.fetchCatalogs().fish.size)
    }

    @Test
    fun fishCatalogPreservesOfficialIdsNamesIconsAndPatchGroups() = runBlocking {
        val fish = BundledPersonalDataCatalogProvider().fetchCatalogs().fish
        assertEquals(335, fish.size)
        assertEquals(FishKingCatalogEntry(7678, 29069, "扎尔艾拉", "2"), fish[7678])
        assertEquals(FishKingCatalogEntry(52297, 28790, "闪电球", "7"), fish[52297])
        assertEquals(mapOf("2" to 112, "3" to 44, "4" to 48, "5" to 45, "6" to 40, "7" to 46),
            fish.values.groupingBy { it.patch }.eachCount())
        assertTrue(fish.values.all { it.itemId > 0 && it.iconId > 0 && it.name.isNotBlank() })
    }

    @Test
    fun savageCatalogRetainsMainPageOrderAchievementOnlyTiersAndNestedIndexAgreement() = runBlocking {
        val catalog = BundledPersonalDataCatalogProvider().fetchCatalogs()
        val series = catalog.savageSeries
        assertEquals(7, series.size)
        assertEquals(listOf("阿卡狄亚零式登天斗技场", "零式万魔殿", "伊甸零式希望乐园", "欧米茄零式时空狭缝",
            "亚历山大零式机神城", "巴哈姆特零式大迷宫", "巴哈姆特大迷宫"), series.map { it.name })
        val tiers = series.flatMap { it.tiers }
        assertEquals(19, tiers.size)
        assertEquals(58, catalog.savageRaids.size)
        assertEquals(catalog.savageRaids, tiers.flatMap { it.raids }.associateBy { it.instanceId })
        val achievementOnly = tiers.filter { it.achievementOnly }
        assertEquals(6, achievementOnly.size)
        assertTrue(achievementOnly.all { !it.achievementText.isNullOrBlank() && it.raids.size == 1 })
        assertEquals("阿卡狄亚零式登天斗技场 重量级4", catalog.savageRaids[1327]?.name)
        assertEquals(112644, catalog.savageRaids[1327]?.imageId)
    }

    @Test
    fun setsExcludeOfficialBlacklistAndEmptyItemsWithoutRenumberingSlots() = runBlocking {
        val glamour = requireNotNull(BundledPersonalDataCatalogProvider().fetchCatalogs().glamour)
        assertEquals(618, glamour.setCount)
        assertEquals(glamour.setCount, glamour.sets.size)
        assertFalse(glamour.sets.any { it.mirageSetId == 52594 })
        val set = glamour.sets.single { it.mirageSetId == 45094 }
        assertEquals("梦幻套装", set.name)
        assertEquals(42192, set.iconId)
        assertEquals(listOf(
            GlamourCatalogSetItem(0, 2642, "梦幻帽", 41086),
            GlamourCatalogSetItem(1, 2965, "梦幻装", 42192),
            GlamourCatalogSetItem(4, 3747, "梦幻靴", 46193),
        ), set.items)
        assertTrue(glamour.sets.all { row -> row.items.all { it.itemId > 0 && it.slotIndex in 0..8 } &&
            row.items.map { it.slotIndex }.distinct().size == row.items.size })
    }

    @Test
    fun unnamedPlaceholdersAreExcludedButMeaningfulNoDyeEntryRemains() = runBlocking {
        val glamour = requireNotNull(BundledPersonalDataCatalogProvider().fetchCatalogs().glamour)
        assertEquals(56, glamour.fashionAccessoryCount)
        assertEquals(56, glamour.fashionAccessories.size)
        assertTrue(glamour.fashionAccessories.none { it.id in setOf(0, 21, 29) })
        assertTrue(glamour.fashionAccessories.all { !it.name.isNullOrBlank() && it.iconId > 0 })
        assertEquals("阳伞", glamour.fashionAccessories.single { it.id == 1 }.name)
        assertEquals(126, glamour.stainCount)
        assertEquals(126, glamour.stains.size)
        assertTrue(glamour.stains.none { it.stainId in 126..128 })
        assertEquals("无染色", glamour.stains.single { it.stainId == 0 }.name)
        assertEquals(0L, glamour.stains.single { it.stainId == 0 }.color)
        assertEquals(16, glamour.stains.count { it.isMetallic })
        assertTrue(glamour.stains.all { it.color in 0L..0xFFFFFFL })
    }

    @Test
    fun callerMutationCannotAlterTheCachedSourceSnapshot() = runBlocking {
        val provider = BundledPersonalDataCatalogProvider()
        val first = provider.fetchCatalogs()
        (first.fish as MutableMap).clear()
        (first.glamour!!.sets.first().items as MutableList).clear()
        val second = provider.fetchCatalogs()
        assertEquals(335, second.fish.size)
        assertEquals(listOf(0, 1, 4), second.glamour!!.sets.single { it.mirageSetId == 45094 }.items.map { it.slotIndex })
    }

    @Test
    fun cancelledCallerDoesNotReceiveAnOfflineResult() {
        val cancellation = CancellationException("fixture cancellation")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                currentCoroutineContext().cancel(cancellation)
                BundledPersonalDataCatalogProvider().fetchCatalogs()
            }
        }
        assertSame(cancellation, error)
    }
}
