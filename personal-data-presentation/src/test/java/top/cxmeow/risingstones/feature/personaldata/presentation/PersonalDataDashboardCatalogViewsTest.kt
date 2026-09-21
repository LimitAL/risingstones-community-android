package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

class PersonalDataDashboardCatalogViewsTest {
    private val views = PersonalDataDashboardCatalogViews
    private val date = Instant.parse("2026-01-02T00:00:00Z")
    private val fish = PersonalDataOfficialCatalogs(fish = listOf(
        FishKingCatalogEntry(1, 11, "King A", "2"), FishKingCatalogEntry(2, 12, "King B", "7"),
    ).associateBy { it.itemId })
    private val extra = PersonalDataSupplementaryCatalogs(oceanFish = listOf(PersonalDataOceanFishCatalogEntry(3, 13, "Sea fish")))
    private val fishRecords = listOf(PersonalDataFishCatch("King A", date, 2), PersonalDataFishCatch("King A", null, 7),
        PersonalDataFishCatch("Sea fish", date, 3), PersonalDataFishCatch("Unmapped", date, 1))

    @Test fun fishNamesJoinWithoutMergingDuplicatesOrMisclassifyingOceanRecords() {
        val rows = views.fishRows(fishRecords, fish, extra, false, null, true, false, "")
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.name == "King A" })
        assertEquals(4, rows.map { it.key }.toSet().size)
        assertTrue(rows.none { it.name == "Sea fish" })
        assertNull(rows.single { it.name == "Unmapped" }.itemId)
        assertNull(rows.last().record)
        assertEquals("King B", rows.last().name)
    }

    @Test fun fishPatchAndCountSortAreLocalAndUnknownNamesDoNotAcquireAPatch() {
        val rows = views.fishRows(fishRecords, fish, extra, false, "2", false, true, "king")
        assertEquals(listOf(7L, 2L), rows.map { it.record?.count })
        assertTrue(rows.all { it.patch == "2" })
        assertEquals(listOf("Sea fish"), views.fishRows(fishRecords, fish, extra, true, "2", false, false, "").map { it.name })
    }

    @Test fun missingFishCatalogRetainsUnknownRecordsWithoutInventingUnobtainedRows() {
        val rows = views.fishRows(fishRecords, null, null, false, null, true, false, "")
        assertEquals(fishRecords.size, rows.size)
        assertTrue(rows.all { it.itemId == null && it.record != null })
        assertTrue(views.fishRows(emptyList(), null, null, false, null, true, false, "").isEmpty())
    }

    @Test fun duplicateOceanDefinitionsDoNotDuplicateRowsOrKeysButBusinessRecordsRemainSeparate() {
        val duplicated = extra.copy(oceanFish = extra.oceanFish + extra.oceanFish)
        val records = List(2) { PersonalDataFishCatch("Sea fish", date, 3) }
        val rows = views.fishRows(records, fish, duplicated, true, null, true, false, "")
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(1, views.fishRows(emptyList(), fish, duplicated, true, null, true, false, "").size)
    }

    @Test fun unobtainedAchievementsKeepCatalogOrderInsteadOfNumericIdOrder() {
        val catalog = listOf(9, 3, 7).map { PersonalDataAchievementCatalogEntry(it, "Achievement $it", null, null) }
        assertEquals(listOf(9, 3, 7), views.achievementRows(emptyList(), catalog, true, "").map { it.achievementId })
    }

    @Test fun achievementsKeepUnmappedAndDuplicateRecordsWhileLockedCatalogIsOptional() {
        val records = listOf(PersonalDataAchievementRecord(1, "First", null, date),
            PersonalDataAchievementRecord(1, "First", null, null), PersonalDataAchievementRecord(9, "Outside", null, date))
        val catalog = listOf(PersonalDataAchievementCatalogEntry(1, "First", null, 4),
            PersonalDataAchievementCatalogEntry(2, "Second", "Description", 5))
        assertEquals(3, views.achievementRows(records, catalog, false, "").size)
        val rows = views.achievementRows(records, catalog, true, "")
        assertEquals(4, rows.size)
        assertEquals(2, rows.last().achievementId)
        assertNull(rows.single { it.achievementId == 9 }.catalog)
        assertEquals(2, views.achievementRows(records, catalog, true, "description").single().achievementId)
        assertTrue(views.achievementRows(emptyList(), emptyList(), true, "").isEmpty())
    }

    @Test fun savageKeepsIncompleteUnlockedTierAndAchievementOnlyButHidesLockedNormalTier() {
        val normal = tier("Normal", false, 1, 2)
        val locked = tier("Locked", false, 3)
        val achievement = tier("Achievement", true, 4)
        val series = SavageRaidCatalogSeries("Series", "S", listOf(normal, locked, achievement))
        val rows = views.savageSeries(listOf(clear(1), clear(1)), PersonalDataOfficialCatalogs(savageSeries = listOf(series)), "")
        val tiers = rows.single().tiers
        assertEquals(listOf(normal, achievement), tiers.map { it.catalog })
        assertEquals(2, tiers.first().raids.first().records.size)
        assertEquals(2, tiers.first().raids.size)
        assertFalse(tiers.first().isComplete)
        assertFalse(tiers.last().hasRecords)
        assertFalse(tiers.last().isComplete)
    }

    @Test fun savageSeriesOrderAndUnknownRecordsStaySeparateFromCatalogCompleteness() {
        val a = SavageRaidCatalogSeries("A", "A", listOf(tier("A", false, 1)))
        val b = SavageRaidCatalogSeries("B", "B", listOf(tier("B", true, 2)))
        val c = SavageRaidCatalogSeries("C", "C", listOf(tier("C", false, 3)))
        val catalogs = PersonalDataOfficialCatalogs(savageSeries = listOf(a, b, c))
        val records = listOf(clear(1), clear(2), clear(99))
        val rows = views.savageSeries(records, catalogs, "")
        assertEquals(listOf("A", "B"), rows.map { it.catalog.name })
        assertTrue(rows.first().tiers.single().isComplete)
        assertFalse(rows.last().tiers.single().isComplete)
        assertEquals(listOf(clear(99)), views.unmappedSavageRecords(records, catalogs))
        assertEquals(records, views.unmappedSavageRecords(records, null))
        assertTrue(views.savageSeries(records, null, "").isEmpty())
    }

    @Test fun vanityAllIncludesNonSelectableCategoriesButSelectorOnlyUsesCurrentPeriodRecords() {
        val catalogs = listOf(category(1, 1), category(7, 1, false), category(2, 1), category(3, 3))
        val records = listOf(vanity(1, 4), vanity(7, 9), vanity(2, 5, PersonalDataVanityPeriod.LastYear), vanity(3, 2))
        assertEquals(listOf(1), views.vanityCategories(records, catalogs, PersonalDataVanityPeriod.AllTime, 1).map { it.id })
        assertEquals(listOf(7, 1), views.vanityRows(records, catalogs, PersonalDataVanityPeriod.AllTime, 1, null, "").map { it.categoryId })
        assertEquals(listOf(2), views.vanityCategories(records, catalogs, PersonalDataVanityPeriod.LastYear, 1).map { it.id })
        assertEquals(listOf(1, 2), views.vanityCategories(emptyList(), catalogs, PersonalDataVanityPeriod.AllTime, 1).map { it.id })
        assertTrue(views.vanityCategories(records, catalogs, PersonalDataVanityPeriod.Unknown, 1).isEmpty())
        assertTrue(views.vanityRows(records, catalogs, PersonalDataVanityPeriod.AllTime, 3, 1, "").isEmpty())
    }

    @Test fun vanityKeepsUnknownCountsAndUnmappedCategoryAsEvidenceButExcludesConfirmedZero() {
        val catalogs = listOf(category(1, 1))
        val records = listOf(vanity(1, 0), vanity(1, null), vanity(99, 2), vanity(1, 9, PersonalDataVanityPeriod.Unknown))
        val known = views.vanityRows(records, catalogs, PersonalDataVanityPeriod.AllTime, 1, null, "")
        assertEquals(1, known.size)
        assertNull(known.single().count)
        assertEquals(listOf(99), views.unmappedVanityRows(records, catalogs, PersonalDataVanityPeriod.AllTime, "").map { it.categoryId })
        assertEquals(9L, views.vanityRows(records, catalogs, PersonalDataVanityPeriod.Unknown, 1, null, "").single().count)
    }

    private fun tier(name: String, achievement: Boolean, vararg ids: Int) = SavageRaidCatalogTier(name, name, achievement,
        if (achievement) "Achievement record" else null, ids.map { SavageRaidCatalogEntry(it, "Raid $it", null) })
    private fun clear(id: Int) = PersonalDataSavageClear(id, date, false, "Synthetic job", 42.5)
    private fun category(id: Int, major: Int, selectable: Boolean = true) = PersonalDataVanityCategory(id, "Category $id", null, major, id, selectable)
    private fun vanity(category: Int, count: Long?, period: PersonalDataVanityPeriod = PersonalDataVanityPeriod.AllTime) =
        PersonalDataVanityUsage(period, category, 10 + category, "Item $category", null, count)
}
