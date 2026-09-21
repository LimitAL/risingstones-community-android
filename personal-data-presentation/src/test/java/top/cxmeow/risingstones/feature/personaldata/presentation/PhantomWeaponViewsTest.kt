package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponStage.*

class PhantomWeaponViewsTest {
    private val catalog = phantomCatalog()
    private val utc = ZoneId.of("UTC")

    @Test fun successfulEmptyProgressIsKnownButMissingSourcesAreUnknown() {
        val state = PhantomWeaponUiState(items = emptyList(), aether = emptyList(), catalog = catalog)
        assertFalse(state.hasUnknownProgress)
        assertNull(state.maximumStage)
        assertTrue(state.copy(items = null).hasUnknownProgress)
        assertTrue(state.copy(aether = null).hasUnknownProgress)
        assertTrue(state.copy(catalog = null).hasUnknownProgress)
    }

    @Test fun everyProgressMaterialWithMissingOrNegativeQuantityRemainsUnknown() {
        for (category in listOf("半魂晶", "水晶混合黏土", "消幻晶")) {
            for (count in listOf(null, -1L)) {
                val state = PhantomWeaponUiState(items = listOf(phantomItem(1, category, count)), aether = emptyList(), catalog = catalog)
                assertTrue(state.hasUnknownProgress)
                assertNull(state.maximumStage)
            }
            assertFalse(PhantomWeaponUiState(items = listOf(phantomItem(1, category, 0)), aether = emptyList(), catalog = catalog).hasUnknownProgress)
        }
    }

    @Test fun missingOrNegativeAetherPointsRemainUnknownIncludingUnrecognizedColors() {
        for (points in listOf(null, -1L)) {
            val state = PhantomWeaponUiState(items = emptyList(), aether = listOf(PhantomWeaponAetherRecord("future", points)), catalog = catalog)
            assertTrue(state.hasUnknownProgress)
            assertNull(state.maximumStage)
        }
        assertFalse(PhantomWeaponUiState(items = emptyList(), aether = listOf(PhantomWeaponAetherRecord("yellow", 0)), catalog = catalog).hasUnknownProgress)
    }

    @Test fun unrelatedItemCategoriesDoNotMakeKnownProgressUnknown() {
        val records = listOf(phantomItem(quantity = null), phantomItem(999, "other", -1))
        val state = PhantomWeaponUiState(items = records, aether = emptyList(), catalog = catalog)
        assertFalse(state.hasUnknownProgress)
        assertNull(state.maximumStage)
        assertEquals(records, state.items)
    }

    @Test fun allStageBoundariesRequireBothSuccessfulSources() {
        val cases = listOf(
            emptyList<PhantomWeaponItemRecord>() to null,
            listOf(phantomItem(47744, "半魂晶", 1)) to Penumbrae,
            listOf(phantomItem(500, "水晶混合黏土", 1)) to Obscurum,
            listOf(phantomItem(50974, "消幻晶", 1)) to Eclipticum,
            List(3) { phantomItem(50974, "消幻晶", 100) } + phantomItem(50978, "unrelated", 0) to Occultum,
        )
        cases.forEach { (items, expected) ->
            assertEquals(expected, PhantomWeaponViews.maximumStage(items, emptyList(), catalog))
            assertNull(PhantomWeaponViews.maximumStage(items, null, catalog))
            assertNull(PhantomWeaponViews.maximumStage(null, emptyList(), catalog))
        }
        assertEquals(Umbrae, PhantomWeaponViews.maximumStage(emptyList(), listOf(PhantomWeaponAetherRecord("future", 1)), catalog))
    }

    @Test fun stageFiveCountsRowsWithoutInventingDistinctMaterialOrWeaponCategoryRequirement() {
        val base = List(3) { phantomItem(999, "消幻晶", 100) }
        assertEquals(Eclipticum, PhantomWeaponViews.maximumStage(base, emptyList(), catalog))
        assertEquals(Occultum, PhantomWeaponViews.maximumStage(base + phantomItem(50978, "future", null), emptyList(), catalog))
        assertEquals(Eclipticum, PhantomWeaponViews.maximumStage(base.take(2) + phantomItem(50978), emptyList(), catalog))
        assertNull(PhantomWeaponViews.maximumStage(base + phantomItem(50978), emptyList(), null))
        assertEquals(Eclipticum, PhantomWeaponViews.maximumStage(List(3) { phantomItem(999, "消幻晶", 99) } + phantomItem(50978), emptyList(), catalog))
    }

    @Test fun zeroNegativeAndUnknownValuesCannotOpenAnyStage() {
        for (quantity in listOf(null, 0L, -1L)) {
            val rows = listOf("半魂晶", "水晶混合黏土", "消幻晶").map { phantomItem(1, it, quantity) }
            assertNull(PhantomWeaponViews.maximumStage(rows, listOf(PhantomWeaponAetherRecord("green", quantity)), catalog))
        }
    }

    @Test fun firstWeaponRecordCountsEvenWithZeroOrUnknownQuantityAndWrongCategoriesDoNotCount() {
        val first = phantomItem(47869, quantity = null)
        val records = listOf(phantomItem(47870, "other"), first, phantomItem(47869, quantity = 9), phantomItem(47871, quantity = 0))
        val rows = PhantomWeaponViews.weapons(records, catalog, Penumbrae, utc)
        assertSame(first, rows.first { it.definition.itemId == 47869 }.record)
        assertEquals(2, rows.count { it.isObtained })
        assertFalse(rows.first { it.definition.itemId == 47870 }.isObtained)
        assertEquals(3, PhantomWeaponViews.rawAcquiredCount(records, catalog, Penumbrae))
        assertEquals(4, records.size)
    }

    @Test fun weaponsSortObtainedFirstNewestThenStableUnknownAndUnobtainedIds() {
        val definitions = catalog.copy(weapons = catalog.weapons.reversed())
        val rows = listOf(phantomItem(47869), phantomItem(47870),
            phantomItem(47871, time = PhantomWeaponRecordTime.OffsetTime(Instant.parse("2026-01-02T00:00:00Z"))),
            phantomItem(47872, time = PhantomWeaponRecordTime.OffsetTime(Instant.parse("2026-01-01T00:00:00Z"))))
        val result = PhantomWeaponViews.weapons(rows, definitions, Penumbrae, utc)
        assertEquals(listOf(47871, 47872, 47870, 47869), result.take(4).map { it.definition.itemId })
        assertEquals((47873..47890).toList(), result.drop(4).map { it.definition.itemId })
    }

    @Test fun mixedTimeKindsUseExplicitZoneWhileOriginalPrecisionIsRetained() {
        val local = PhantomWeaponRecordTime.LocalTime(LocalDateTime.of(2026, 1, 1, 12, 0))
        val offset = PhantomWeaponRecordTime.OffsetTime(Instant.parse("2026-01-01T08:00:00Z"))
        val rows = listOf(phantomItem(47869, time = local), phantomItem(47870, time = offset))
        assertEquals(47869, PhantomWeaponViews.weapons(rows, catalog, Penumbrae, utc).first().definition.itemId)
        assertEquals(47870, PhantomWeaponViews.weapons(rows, catalog, Penumbrae, ZoneId.of("Asia/Shanghai")).first().definition.itemId)
        assertSame(local, rows[0].firstAcquiredAt)
        assertEquals(Instant.parse("2025-12-31T16:00:00Z"), PhantomWeaponViews.timeInstant(PhantomWeaponRecordTime.CalendarDate(LocalDate.of(2026, 1, 1)), ZoneId.of("Asia/Shanghai")))
    }

    @Test fun sourceNotLoadedDoesNotCreateUnobtainedRowsButSuccessfulEmptyDoes() {
        assertTrue(PhantomWeaponViews.weapons(null, catalog, Penumbrae).isEmpty())
        assertTrue(PhantomWeaponViews.weapons(emptyList(), null, Penumbrae).isEmpty())
        val rows = PhantomWeaponViews.weapons(emptyList(), catalog, Penumbrae)
        assertEquals(22, rows.size)
        assertTrue(rows.none { it.isObtained })
    }

    @Test fun collapsedLimitUsesRawCountRoundsUpInPairsAndCapsAt22() {
        listOf(0 to 2, 1 to 2, 2 to 2, 3 to 4, 4 to 4, 21 to 22, 23 to 22, Int.MAX_VALUE to 22).forEach { (count, expected) ->
            assertEquals(expected, PhantomWeaponViews.collapsedLimit(count))
        }
        val state = PhantomWeaponUiState(items = List(5) { phantomItem(47869) }, catalog = catalog, selectedStage = Penumbrae)
        assertEquals(1, state.acquiredCount); assertEquals(5, state.rawAcquiredCount); assertEquals(6, state.visibleWeapons.size)
        assertEquals(22, state.weapons.size)
        assertEquals(47869, state.recentWeapon?.definition?.itemId)
        assertEquals(1, state.copy(obtainedOnly = true).visibleWeapons.size)
        assertEquals(1, state.copy(query = "  Weapon 47869  ").filteredWeapons.size)
        assertEquals(22, state.copy(isExpanded = true).visibleWeapons.size)
    }

    @Test fun soulCrystalsKeepObservedDuplicatesSortCountsThenIdsAndAppendMissingDefinitions() {
        val items = listOf(phantomItem(47745, "半魂晶", 5), phantomItem(47744, "半魂晶", 2),
            phantomItem(47745, "半魂晶", 2), phantomItem(47746, "半魂晶", null))
        val material = PhantomWeaponViews.materials(Penumbrae, items, emptyList(), catalog) as PhantomWeaponMaterials.SoulCrystals
        assertEquals(listOf(47744, 47745, 47745, 47746, 47747, 47748, 47749), material.rows.map { it.definition.itemId })
        assertEquals(listOf(2L, 2L, 5L, null, 0L, 0L, 0L), material.rows.map { it.count })
        assertTrue(material.rows.all { it.target == null && it.fraction == null })
        val unknown = PhantomWeaponViews.materials(Penumbrae, null, null, catalog) as PhantomWeaponMaterials.SoulCrystals
        assertTrue(unknown.rows.all { it.count == null })
    }

    @Test fun aetherUsesFixedReverseColorOrderFirstExactColorAndNeverSumsDuplicates() {
        val records = listOf(PhantomWeaponAetherRecord("green", 600), PhantomWeaponAetherRecord("green", 900), PhantomWeaponAetherRecord("red", null), PhantomWeaponAetherRecord("yellow", 10_001))
        val rows = (PhantomWeaponViews.materials(Umbrae, emptyList(), records, catalog) as PhantomWeaponMaterials.Aether).rows
        assertEquals(PhantomWeaponElement.entries, rows.map { it.element })
        assertEquals(listOf(10_001L, null, 0L, 600L), rows.map { it.points })
        assertEquals(1.0, rows[0].fraction)
        assertNull(rows[1].fraction)
        assertTrue((PhantomWeaponViews.materials(Umbrae, null, null, catalog) as PhantomWeaponMaterials.Aether).rows.all { it.points == null })
    }

    @Test fun unknownAndInvalidSoulCrystalIdsKeepTheirObservedRowsAndOriginalRecords() {
        val records = listOf(phantomItem(99999, "半魂晶", 8), phantomItem(null, "半魂晶", 1), phantomItem(-3, "半魂晶", null))
        val rows = (PhantomWeaponViews.materials(Penumbrae, records, emptyList(), catalog) as PhantomWeaponMaterials.SoulCrystals).rows
        assertEquals(9, rows.size)
        assertEquals(listOf(0, 99999, -3), rows.take(3).map { it.definition.itemId })
        assertEquals(listOf(records[1], records[0], records[2]), rows.take(3).map { it.record })
        assertTrue(rows.take(3).all { it.definition.iconId == 0 })
        assertTrue(rows.drop(3).all { it.count == 0L && it.record == null })
    }

    @Test fun lensChecksEveryCumulativeBoundaryIncludingCompletionAndDoesNotSumRows() {
        val values = listOf(0L to Triple(1, 0L, 100L), 99L to Triple(1, 99L, 100L), 100L to Triple(2, 0L, 200L),
            299L to Triple(2, 199L, 200L), 300L to Triple(3, 0L, 300L), 599L to Triple(3, 299L, 300L),
            600L to Triple(4, 0L, 600L), 1199L to Triple(4, 599L, 600L), 1200L to Triple(5, 1200L, 1200L), Long.MAX_VALUE to Triple(5, 1200L, 1200L))
        values.forEach { (count, expected) ->
            val view = PhantomWeaponViews.lens(listOf(phantomItem(500, "水晶混合黏土", count)))
            assertEquals(expected, Triple(view.step, view.current, view.target))
            assertEquals(count >= 1200, view.isComplete)
        }
        val view = PhantomWeaponViews.lens(listOf(phantomItem(500, "水晶混合黏土", 0), phantomItem(500, "水晶混合黏土", 100), phantomItem(500, "水晶混合黏土", 1200)))
        assertEquals(100L, view.cumulative)
        assertFalse(view.isComplete)
    }

    @Test fun lensMissingSuccessCanBeZeroButInvalidAndFailedSourcesStayUnknown() {
        assertEquals(0L, PhantomWeaponViews.lens(emptyList()).cumulative)
        assertNull(PhantomWeaponViews.lens(null).cumulative)
        listOf(null, -1L).forEach { count ->
            val view = PhantomWeaponViews.lens(listOf(phantomItem(500, "水晶混合黏土", count)))
            assertNull(view.step); assertNull(view.current); assertNull(view.fraction); assertFalse(view.isComplete)
        }
        assertNull(PhantomWeaponViews.lens(listOf(phantomItem(500, "水晶混合黏土", null), phantomItem(500, "水晶混合黏土", 1200))).step)
        assertNull(PhantomWeaponViews.lens(listOf(phantomItem(500, "水晶混合黏土", -1), phantomItem(500, "水晶混合黏土", 1200))).step)
    }

    @Test fun demiatmaUsesFixedIdsFirstExactRecordAndPreservesUnknownWithoutSumming() {
        val items = listOf(phantomItem(50974, "other", null), phantomItem(50974, "消幻晶", 100), phantomItem(50975, "消幻晶", 150))
        val rows = (PhantomWeaponViews.materials(Eclipticum, items, emptyList(), catalog) as PhantomWeaponMaterials.DemiAtma).rows
        assertEquals(listOf(50974, 50975, 50976), rows.map { it.definition.itemId })
        assertEquals(listOf(null, 150L, 0L), rows.map { it.count })
        assertEquals(listOf(null, 1.0, 0.0), rows.map { it.fraction })
        assertTrue((PhantomWeaponViews.materials(Eclipticum, null, null, catalog) as PhantomWeaponMaterials.DemiAtma).rows.all { it.count == null })
        assertEquals(PhantomWeaponMaterials.None, PhantomWeaponViews.materials(Occultum, items, emptyList(), catalog))
    }
}

internal fun phantomItem(id: Int? = 47869, category: String = "幻境武器", quantity: Long? = 1, time: PhantomWeaponRecordTime? = null) =
    PhantomWeaponItemRecord(id, category, quantity, time, "Observed")
internal fun phantomCatalog() = PhantomWeaponCatalog(
    PhantomWeaponStage.entries.flatMap { stage ->
        val first = when (stage) { Penumbrae -> 47869; Umbrae -> 47006; Obscurum -> 50032; Eclipticum -> 50978; Occultum -> 51000 }
        (first..first + 21).map { PhantomWeaponDefinition(stage, it, "Weapon $it", it) }
    }, (47744..47749).map { PhantomWeaponMaterialDefinition(it, "Material $it", it) },
    (50974..50976).map { PhantomWeaponMaterialDefinition(it, "Material $it", it) },
)
