package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponDefinition
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponStage

class BundledPhantomWeaponCatalogProviderTest {
    @Test fun standaloneResourceRecordsTheReviewedPublicSource() {
        val document = Json.parseToJsonElement(resource()).jsonObject
        assertEquals("1", document.getValue("formatVersion").jsonPrimitive.content)
        assertFalse(document.containsKey("schemaVersion"))
        val provenance = document.getValue("provenance").jsonObject
        assertEquals("2026-09-20", provenance.getValue("capturedOn").jsonPrimitive.content)
        val source = provenance.getValue("source").jsonObject
        assertEquals("https://ff14risingstones.web.sdo.com/mob/static/js/chunk-7690576a.97d2f158.js", source.getValue("url").jsonPrimitive.content)
        assertEquals("60f51032cf4dac74e7c430a4bcbbe20e277f14cc329d27d48f770f35af3a9f7d", source.getValue("sha256").jsonPrimitive.content)
        assertEquals("975500", source.getValue("byteCount").jsonPrimitive.content)
    }

    @Test fun allFiveStagesRetainTwentyTwoItemsIncludingScientificNotationIdentifier() = runBlocking {
        val rows = BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog().weapons
        assertEquals(110, rows.size)
        assertEquals(110, rows.map { it.itemId }.toSet().size)
        val ranges = listOf(47869..47890, 47006..47027, 50032..50053, 50978..50999, 51000..51021)
        PhantomWeaponStage.entries.forEachIndexed { index, stage ->
            assertEquals(ranges[index].toList(), rows.filter { it.stage == stage }.map { it.itemId })
        }
        assertEquals(PhantomWeaponDefinition(PhantomWeaponStage.Occultum, 51000, "幻境利剑·秘影", 30704), rows.single { it.itemId == 51000 })
        assertEquals("幻境鸢盾·半影", rows.single { it.itemId == 47890 }.name)
        assertTrue(rows.all { it.itemId > 0 && it.iconId > 0 && it.name.isNotBlank() })
    }

    @Test fun materialsKeepTheExactPublicNamesIdsAndIconOrder() = runBlocking {
        val catalog = BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog()
        assertEquals((47744..47749).toList(), catalog.soulCrystals.map { it.itemId })
        assertEquals(listOf("青色半魂晶", "碧色半魂晶", "绿色半魂晶", "橙色半魂晶", "紫色半魂晶", "黄色半魂晶"), catalog.soulCrystals.map { it.name })
        assertEquals(listOf(26025, 26035, 26034, 26026, 26027, 26029), catalog.soulCrystals.map { it.iconId })
        assertEquals(listOf(50974, 50975, 50976), catalog.demiatma.map { it.itemId })
        assertEquals(listOf("消幻晶α", "消幻晶β", "消幻晶γ"), catalog.demiatma.map { it.name })
        assertEquals(listOf(26229, 26231, 26230), catalog.demiatma.map { it.iconId })
        assertFalse(catalog.soulCrystals.any { it.itemId in listOf(47740, 47741, 47743) })
    }

    @Test fun returnedMutableCollectionsCannotPoisonTheNextOfflineRead() = runBlocking {
        val provider = BundledPhantomWeaponCatalogProvider()
        val first = provider.fetchPhantomWeaponCatalog()
        (first.weapons as MutableList).clear()
        (first.soulCrystals as MutableList).clear()
        (first.demiatma as MutableList).clear()
        val second = provider.fetchPhantomWeaponCatalog()
        assertEquals(listOf(110, 6, 3), listOf(second.weapons.size, second.soulCrystals.size, second.demiatma.size))
    }

    @Test fun malformedIncompleteOrDuplicatedSnapshotsFailInsteadOfBecomingAnEmptyCatalog() {
        val valid = resource()
        val invalid = listOf("{}", "<html>challenge</html>", valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"stage\":\"occultum\"", "\"stage\":\"unknown\""),
            valid.replace("\"itemId\":51000", "\"itemId\":51001"),
            valid.replace("\"itemId\":47744", "\"itemId\":47740"),
            valid.replace("\"iconId\":30704", "\"iconId\":0"),
            valid.replace("\"name\":\"幻境利剑·秘影\"", "\"name\":\" \""))
        invalid.forEach { text ->
            assertSame(PersonalDataException.MissingPayload, assertThrows(PersonalDataException::class.java) { decodePhantomWeaponCatalog(text) })
        }
    }

    @Test fun callerCancellationPropagatesBeforeDeliveringOfflineData() {
        val cancellation = CancellationException("synthetic cancellation")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                currentCoroutineContext().cancel(cancellation)
                BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog()
            }
        }
        assertSame(cancellation, error)
    }

    private fun resource() = checkNotNull(javaClass.getResourceAsStream(PhantomCatalogResource)).use { it.readBytes().decodeToString() }
}
