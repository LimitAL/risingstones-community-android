package top.cxmeow.risingstones.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.data.BundledPersonalDataCatalogProvider

/** Checks Java-resource packaging in the installed APK without starting an account or Activity. */
class BundledPersonalDataCatalogTest {
    @Test fun installedApkLoadsSupplementaryCatalogsWithoutNetwork() = runBlocking {
        val catalogs = BundledPersonalDataCatalogProvider().fetchSupplementaryCatalogs()
        assertEquals(13, catalogs.oceanFish.size)
        assertEquals(35, catalogs.fishingAchievements.size)
        assertEquals(35, catalogs.frontlineAchievements.size)
        assertEquals(36, catalogs.vanityCategories.size)
        assertTrue(catalogs.frontlineAchievements.all { it.iconId == null })
        assertTrue(catalogs.vanityCategories.filter { it.id in setOf(7, 9, 62) }.none { it.selectable })
    }
    @Test fun installedApkLoadsOfficialOfflineCatalogs() = runBlocking {
        val catalogs = BundledPersonalDataCatalogProvider().fetchCatalogs()
        assertEquals(335, catalogs.fish.size)
        assertEquals(58, catalogs.savageRaids.size)
        assertEquals(7, catalogs.savageSeries.size)
        val glamour = requireNotNull(catalogs.glamour)
        assertEquals(618, glamour.sets.size)
        assertEquals(56, glamour.fashionAccessories.size)
        assertEquals(126, glamour.stains.size)
        assertTrue(glamour.stains.any { it.stainId == 0 })
        assertFalse(glamour.sets.any { it.mirageSetId == 52594 })
        assertTrue(glamour.sets.flatMap { it.items }.all { it.itemId > 0 && it.slotIndex in 0..8 })
    }
}
