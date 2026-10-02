package top.cxmeow.risingstones.feature.personaldata.presentation

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationFieldKind.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind.*

class NativeOccultProjectionTest {
    private val soul = OccultCatalogItem(50802, "青色半魂晶", 26501)
    private val crystals = (50974..50976).map { OccultCatalogItem(it, "消幻晶", 26229) }
    private val catalog = OccultCatalog(2, 1, "2026-08-12", "https://ff14-eo.web.sdo.com/ffstones/item/icon/dcsvv4fowz2m",
        listOf(job(0, 0), job(1, 6), job(2, 3)), listOf(OccultCatalogItem(47979, "瓣齿鲨笛", 26039), OccultCatalogItem(52264, "安静蜂鸟笛", 26039)),
        listOf("penumbrae", "umbrae", "obscurum", "eclipticum", "occultum").zip(listOf(47869, 47006, 50032, 50978, 51000)).mapIndexed { index, (stage, id) ->
            OccultWeaponStage(stage, stage, index + 1, listOf(OccultCatalogItem(id, stage, 30694)))
        }, OccultPhaseResources(listOf(soul), listOf(soul.itemId), crystals))
    @Test fun jobsUseMaximumDuplicateLevelsAndCatalogCapsIncludingFreelancer() {
        val section = section(PhantomJobs, row("a", null, PhantomJob to "1", Level to "2"), row("b", null, PhantomJob to "1", Level to "8"), row("c", null, PhantomJob to "0", Level to "999"))
        val result = NativeOccultProjection.jobs(catalog, section)
        assertEquals(2, result.jobs.size)
        assertEquals(1, result.learnedCount)
        assertEquals(1, result.masteredCount)
        assertEquals(1L, result.freelancer?.currentLevel)
        assertTrue(result.jobs.first().isMastered)
        assertFalse(result.jobs.last().isLearned)
        val updated = catalog.copy(supportJobs = listOf(job(0, 0), job(1, 10)))
        assertEquals(0, NativeOccultProjection.jobs(updated, section).masteredCount)
    }
    @Test fun successfulEmptyJobsAndMissingSnapshotHaveDifferentOutcomes() {
        val empty = NativeOccultProjection.jobs(catalog, section(PhantomJobs))
        assertEquals(0L, empty.freelancer?.currentLevel)
        assertTrue(empty.hasSnapshot)
        val missing = NativeOccultProjection.jobs(catalog, null)
        assertNull(missing.freelancer?.currentLevel)
        assertFalse(missing.hasSnapshot)
    }
    @Test fun boxesAggregateAllGradesPreserveUnknownAndClampInvalidCounts() {
        val boxes = NativeOccultProjection.boxes(section(TreasureChests,
            row("a", null, BoxType to "撒娇罐", BoxGrade to "銅", Quantity to "3"),
            row("b", null, BoxType to "幸福兔", BoxGrade to "silver", Quantity to "2"),
            row("c", null, BoxType to "other", BoxGrade to " platinum ", Quantity to "4"),
            row("d", null, BoxType to "撒娇罐", BoxGrade to "gold", Quantity to "-2"),
            row("e", null, BoxGrade to "", Quantity to "bad")))
        assertEquals(3L, boxes.treasureCount)
        assertEquals(2L, boxes.bunnyCount)
        assertEquals(9L, boxes.totalCount)
        assertEquals(listOf("copper", "silver", "gold", "platinum", "unknown"), boxes.grades.map { it.id })
        assertEquals(listOf(3L, 2L, 0L, 4L, 0L), boxes.grades.map { it.count })
        assertFalse(NativeOccultProjection.boxes(null).hasSnapshot)
    }
    @Test fun dropsUseMaxDuplicateCountAndDeterministicOrdering() {
        val result = NativeOccultProjection.drops(catalog, section(AcquiredItems,
            row("a", 47979, Quantity to "2"), row("b", 47979, Quantity to "1"), row("c", 52264, Quantity to "-1")))
        assertEquals(2L, result.first().count)
        assertTrue(result.first().isObtained)
        assertFalse(result.last().isObtained)
        assertFalse(NativeOccultProjection.drops(catalog, null).first().hasSnapshot)
    }
    @Test fun historyUsesCatalogFallbackNewestFirstAnd200Limit() {
        val records = (0..202).map { row("history-$it", 47979, RecordedAt to it.toString().padStart(3, '0')) }
        val result = NativeOccultProjection.history(catalog, ExplorationSection(TreasureHistory, records))
        assertEquals(200, result.size)
        assertEquals("202", result.first().logTime)
        assertEquals("瓣齿鲨笛", result.first().name)
        assertEquals(26039, result.first().iconId)
        assertTrue(NativeOccultProjection.history(catalog, section(TreasureHistory, row("unknown", 999))).isEmpty())
    }
    @Test fun weaponsKeepEveryStageLastDuplicateTimestampAndAcquiredFirst() {
        val items = section(AcquiredItems,
            row("a", 47869, ItemCategory to "幻境武器", FirstAcquiredAt to "2026-07-01"),
            row("b", 47869, ItemCategory to "幻境武器", FirstAcquiredAt to "2026-08-01"))
        val result = NativeOccultProjection.weapons(catalog.copy(weaponStages = catalog.weaponStages.reversed()), items, null)
        assertEquals(5, result.stages.size)
        assertEquals("2026-08-01", result.stages.first().weapons.single().obtainedAt)
        assertEquals("penumbrae", result.suggestedStageId)
        assertTrue(result.stages.first().weapons.single().isObtained)
        assertFalse(result.stages.last().weapons.single().isObtained)
    }
    @Test fun resourcesUseCatalogIdsMaxCountsClayMaximumAndFirstSphere() {
        val items = section(AcquiredItems, row("soul1", 50802, Quantity to "4"), row("soul2", 50802, Quantity to "2"),
            row("clay1", 0, ItemCategory to "水晶混合黏土", Quantity to "50"), row("clay2", 0, ItemCategory to "水晶混合黏土", Quantity to "350"))
        val light = section(Aether, row("light1", null, AetherColor to "green", AetherPoints to "1200"), row("light2", null, AetherColor to "green", AetherPoints to "2000"))
        val stages = NativeOccultProjection.weapons(catalog, items, light).stages
        assertEquals(4L, (stages[0].progress as NativeOccultProgress.SoulCrystals).rows.single().value)
        assertEquals(1200L, (stages[1].progress as NativeOccultProgress.MagicBoard).spheres.first().value)
        val clay = (stages[2].progress as NativeOccultProgress.Clay).value
        assertEquals(3, clay.step)
        assertEquals(50L, clay.current)
        assertEquals(300L, clay.maximum)
    }
    @Test fun suggestedStageRequiresCatalogCrystalCompletionAndObtainedEclipticum() {
        val records = crystals.map { row("crystal-${it.itemId}", it.itemId, Quantity to "100") }
        val materialsOnly = NativeOccultProjection.weapons(catalog, ExplorationSection(AcquiredItems, records), null)
        assertEquals("eclipticum", materialsOnly.suggestedStageId)
        val completed = NativeOccultProjection.weapons(catalog, ExplorationSection(AcquiredItems, records + row("weapon", 50978, ItemCategory to "幻境武器")), null)
        assertEquals("occultum", completed.suggestedStageId)
    }
    @Test fun clayThresholdsClampCountsAndPreserveSnapshotState() {
        for ((total, step, current, max) in listOf(listOf(-1L,1L,0L,100L), listOf(100L,2L,0L,200L), listOf(300L,3L,0L,300L), listOf(600L,4L,0L,600L), listOf(9999L,4L,600L,600L))) {
            val clay = NativeOccultProjection.clay(total, true)
            assertEquals(step.toInt(), clay.step)
            assertEquals(current, clay.current)
            assertEquals(max, clay.maximum)
        }
        assertFalse(NativeOccultProjection.clay(0, false).hasSnapshot)
    }
    @Test fun unavailableSectionsDoNotPretendToBeConfirmedUnobtained() {
        val result = NativeOccultProjection.weapons(catalog, ExplorationSection(AcquiredItems, failure = ExplorationFailure.Network), null)
        assertFalse(result.stages.first().weapons.single().hasSnapshot)
        assertEquals("penumbrae", result.suggestedStageId)
        assertFalse((result.stages[1].progress as NativeOccultProgress.MagicBoard).hasSnapshot)
    }
    private fun job(id: Int, cap: Int) = OccultSupportJob(id, "job-$id", "Phantom $id", "", cap, id, 82271 + id)
    private fun row(key: String, id: Int?, vararg fields: Pair<ExplorationFieldKind, String>) = ExplorationRecord(key, "", fields.map { ExplorationField(it.first, it.second) }, id)
    private fun section(kind: ExplorationSectionKind, vararg records: ExplorationRecord) = ExplorationSection(kind, records.toList())
}
