package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

/** All records and images are synthetic. No account requests, chooser or external application. */
class PersonalDataShareScreenTest {
    @get:Rule val compose = createAndroidComposeRule<PersonalDataShareTestActivity>()
    @After fun clear() { compose.runOnIdle { ShareFixture.configuration = null } }
    @Test fun previewAt599() = preview(599)
    @Test fun previewAt600() = preview(600)
    @Test fun previewAt839() = preview(839)
    @Test fun previewAt840() = preview(840)
    private fun preview(width:Int) {
        val config=show(width)
        open()
        val old=models(); val artifact=(old.share.state.value as PersonalDataShareUiState.Preview).artifact
        val reads=config.service.reads
        compose.activityRule.scenario.recreate()
        waitTag("share-preview-image")
        assertSame(old,models()); assertSame(artifact,(models().share.state.value as PersonalDataShareUiState.Preview).artifact)
        assertEquals(reads,config.service.reads)
        compose.runOnIdle { config.width=if(width<600) 840 else 599 }
        compose.onNodeWithTag("share-preview-image").assertExists()
        assertSame(artifact,(models().share.state.value as PersonalDataShareUiState.Preview).artifact)
        compose.onNodeWithTag("share-preview-send").performClick()
        compose.waitUntil(3_000) { config.host.sent==1 }
        assertSame(artifact,config.host.artifact)
        compose.runOnIdle { config.width=width }
        capture("share-preview-$width.png")
        val cleared=config.host.cleared
        Espresso.pressBack()
        compose.onNodeWithTag("personal-data-share-preview").assertDoesNotExist()
        assertEquals(cleared+1,config.host.cleared)
        assertEquals(PersonalDataBoard.Ultimate,models().main.state.value.selectedBoard)
        assertEquals(reads,config.service.reads)
    }
    @Test fun logoutClearsPreviewAndRetainedBytes() {
        val config=show(600);open(); val old=models();val cleared=config.host.cleared
        compose.runOnIdle { config.service.enabled=false }
        compose.waitUntil(3_000) { old.share.state.value==PersonalDataShareUiState.Closed }
        compose.onNodeWithTag("share-preview-image").assertDoesNotExist()
        assertTrue(config.host.cleared>cleared)
    }
    @Test fun serviceReplacementClearsPreviewAndReturnsToFreshHub() {
        val config=show(840);open();val old=models();val cleared=config.host.cleared
        compose.runOnIdle { config.service=ShareService() }
        compose.waitUntil(3_000) { models().main.state.value.identity!=null && models()!==old }
        assertEquals(PersonalDataShareUiState.Closed,old.share.state.value)
        compose.onNodeWithTag("share-preview-image").assertDoesNotExist()
        assertTrue(config.host.cleared>cleared)
    }
    @Test fun sixCardsRenderCompleteTextAndScanToPublicPages() {
        val context=compose.activity
        val service=ShareService()
        val identity=PersonalDataIdentity("Synthetic adventurer", "Synthetic data center", "Synthetic world", null)
        val whenAt=Instant.parse("2026-01-02T03:04:05Z")
        val cat=PersonalDataAchievementCatalogEntry(1,"Synthetic achievement","Synthetic description",null)
        val record=PersonalDataUltimateRecord(733,2,3,"Synthetic job",UltimateRecordTime.CalendarDate(LocalDate.of(2026,1,2)),60,1)
        val raid=SavageRaidCatalogEntry(1,"Synthetic duty",null)
        val tier=SavageRaidCatalogTier("Synthetic tier","合成测试层级",false,null,listOf(raid))
        val series=SavageRaidCatalogSeries("Synthetic series","S",listOf(tier))
        val ranks=FrontlineRanks(80.0,50.0,null,99.0,0.0,75.0)
        val best=FrontlineBestRecord(FrontlineBestKind.Kills,"Synthetic map",null,"Synthetic job",FrontlinePlacement.First,10,2,20,null,null,null,emptyList())
        val contents=listOf<PersonalDataShareContent>(
            FishingShareContent(FishingOverview(123,0.75,4,12345),listOf(FishingShareCatch(PersonalDataFishCatch("Synthetic fish A",whenAt,1),null),FishingShareCatch(PersonalDataFishCatch("Synthetic fish B",null,2),null)),
                PersonalDataShareOptional.Known(FishingShareAchievement(PersonalDataAchievementRecord(1,null,null,whenAt),cat))),
            GlamourShareContent(GlamourOverview(3,5,9),50.0,emptyList(),GlamourShareFavorites(PersonalDataShareOptional.Empty,PersonalDataShareOptional.Failed,PersonalDataShareOptional.Empty,PersonalDataShareOptional.Empty)),
            SavageShareContent(SavageOverview(1,5,3,2.5),listOf(SavageShareSeries(series,listOf(SavageShareTier(tier,listOf(SavageShareRaid(raid,listOf(PersonalDataSavageClear(1,whenAt,null,"Synthetic job",60.0)))))))),PersonalDataSavageClear(1,whenAt,null,null,null)),
            FrontlineShareContent(FrontlineOverviewRecord(FrontlinePeriodKind.Total,123,30,10,0.25,2.5,null,null,null,null,null,FrontlineAverages(),ranks),ranks,emptyList(),best,PersonalDataShareOptional.Empty,PersonalDataShareOptional.Failed),
            UltimateShareContent(listOf(UltimateShareProgress(733,null,null,record)),listOf(UltimateShareTimelineEntry(record,null,null)),733,1,false),
            OccultShareContent(ExplorationOverview(ExplorationBoard.OccultCrescent,true,listOf(ExplorationField(ExplorationFieldKind.KnowledgeLevel,"60")),emptyList()),
                (0..23).map { OccultShareJob(PhantomShareJob(it,"Synthetic job $it",82271+it,6),if(it==2)null else 3) },PersonalDataShareOptional.Empty,PersonalDataShareOptional.Failed),
        )
        contents.forEach { content ->
            val document=PersonalDataShareDocument(identity,content)
            val text=ShareCardContentBuilder(context,ZoneId.of("Asia/Shanghai")).build(content)
            val artifact=renderShareCard(context,document,text,service,emptyMap())
            assertEquals(750,artifact.width);assertTrue(artifact.height in 700..16_000)
            assertTrue(artifact.accessibleText.contains(identity.characterName))
            assertTrue(artifact.accessibleText.contains(context.getString(R.string.pds_footer)))
            if(content is GlamourShareContent || content is OccultShareContent || content is FrontlineShareContent)
                assertTrue(artifact.accessibleText.contains(context.getString(R.string.pds_failed_section)))
            assertEquals(service.sharePageUrl(content.kind),decodeQr(artifact))
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"share-card-${content.kind}.png").writeBytes(artifact.pngBytes())
        }
    }
    @Test fun longSavageCatalogKeepsEverySeriesAndLastRaidInPng() {
        var id=0
        val series=(0..6).map { group ->
            val rows=(0 until if(group==6)10 else 8).map {
                id++
                SavageShareRaid(SavageRaidCatalogEntry(id,"Synthetic duty $id",null),emptyList())
            }
            val tier=SavageRaidCatalogTier("Synthetic tier $group","合成層級 $group",false,null,rows.map { it.catalog })
            val catalog=SavageRaidCatalogSeries("Synthetic series $group","S$group",listOf(tier))
            SavageShareSeries(catalog,listOf(SavageShareTier(tier,rows)))
        }
        assertEquals(58,id)
        val content=SavageShareContent(SavageOverview(1,10,2,12.0),series,null)
        val doc=PersonalDataShareDocument(PersonalDataIdentity("Synthetic full catalog","","",null),content)
        val card=renderShareCard(compose.activity,doc,ShareCardContentBuilder(compose.activity,ZoneId.of("UTC")).build(content),ShareService(),emptyMap())
        assertTrue(card.height>4_000)
        assertTrue(card.accessibleText.contains("Synthetic duty 58"))
        (0..6).forEach { assertTrue(card.accessibleText.contains("Synthetic series $it")) }
        assertEquals(ShareService().sharePageUrl(PersonalDataShareKind.Savage),decodeQr(card))
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"share-card-Savage-full.png").writeBytes(card.pngBytes())
    }
    @Test fun missingImagesKeepReadableRecordsAndMalformedHostsAreRejected() {
        assertFalse(allowedShareImageUrl("https://static.web.sdo.com.evil.invalid/a.png"))
        assertFalse(allowedShareImageUrl("file:///private/image.png"))
        assertFalse(allowedShareImageUrl("https://user@static.web.sdo.com/a.png"))
        assertTrue(allowedShareImageUrl("https://static.web.sdo.com/a.png"))
        val content=GlamourShareContent(GlamourOverview(null,0,1),null,emptyList(),GlamourShareFavorites(PersonalDataShareOptional.Empty,PersonalDataShareOptional.Empty,PersonalDataShareOptional.Empty,PersonalDataShareOptional.Empty))
        val doc=PersonalDataShareDocument(PersonalDataIdentity("Synthetic", "", "", null),content)
        val card=renderShareCard(compose.activity,doc,ShareCardContentBuilder(compose.activity,ZoneId.of("UTC")).build(content),ShareService(),mapOf("synthetic" to null))
        assertEquals(1,card.missingImageCount)
        assertTrue(card.accessibleText.contains(compose.activity.getString(R.string.pdr_unknown)))
    }
    private fun decodeQr(artifact:PersonalDataShareArtifact):String {
        val bytes=artifact.pngBytes();val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try {
            val h=minOf(400,bitmap.height);val pixels=IntArray(bitmap.width*h)
            bitmap.getPixels(pixels,0,bitmap.width,0,bitmap.height-h,bitmap.width,h)
            return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,h,pixels)))).text
        } finally { bitmap.recycle() }
    }
    private fun show(width:Int):ShareConfiguration {
        val config=ShareConfiguration(ShareService(),width)
        compose.runOnIdle { ShareFixture.configuration=config };waitTag("personal-data-hub-pane")
        return config
    }
    private fun open() {
        compose.onNodeWithTag("personal-data-board-Ultimate").performScrollTo().performClick()
        compose.waitUntil(3_000) { models().ultimate.state.value.overviewStatus==PersonalDataUltimateLoadStatus.Loaded }
        compose.onNodeWithTag("personal-data-share").performClick();waitTag("share-preview-image")
    }
    private fun models()=compose.runOnIdle { requireNotNull(ViewModelProvider(compose.activity)["personal-data-screen-owner",PersonalDataScreenModelOwner::class.java].currentModels) }
    private fun waitTag(tag:String)=compose.waitUntil(8_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name:String) {
        compose.waitForIdle()
        val image=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
}

class PersonalDataShareTestActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShareFixture.configuration?.let { config -> BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDensity provides Density(constraints.maxWidth.toFloat()/config.width,1f), LocalPersonalDataShareHost provides config.host) {
                MaterialTheme { RisingStonesPersonalDataScreen(config.service,{}) }
            }
        } } }
    }
}
private object ShareFixture { var configuration by mutableStateOf<ShareConfiguration?>(null) }
private class ShareConfiguration(service:ShareService,width:Int) {
    var service by mutableStateOf(service);var width by mutableStateOf(width);val host=ShareHost()
}
private class ShareHost:PersonalDataShareHost {
    var sent=0;var cleared=0;var artifact:PersonalDataShareArtifact?=null
    override suspend fun share(artifact:PersonalDataShareArtifact) { sent++;this.artifact=artifact }
    override fun clear() { cleared++;artifact=null }
}
private class ShareService:PersonalDataUltimateService,PersonalDataShareResourceService {
    var enabled by mutableStateOf(true);var reads=0
    override val hasCommunityIdentity get()=enabled
    override suspend fun fetchIdentity()=PersonalDataIdentity("Synthetic sharing reader","Synthetic area","Synthetic world",null)
    override suspend fun fetchAvailability()=PersonalDataAvailability(mapOf("jue1" to "1"))
    override suspend fun fetchOfficialCatalogs()=PersonalDataOfficialCatalogs()
    override suspend fun fetchUltimateRecords():List<PersonalDataUltimateRecord> { reads++;return listOf(PersonalDataUltimateRecord(733,2,3,"Synthetic job",UltimateRecordTime.CalendarDate(LocalDate.of(2026,1,2)),60,1)) }
    override suspend fun fetchUltimateSection(territoryType:Int,section:PersonalDataUltimateSection):PersonalDataUltimateData=error("No encounter read expected")
    override suspend fun fetchShareCatalogs()=PersonalDataShareCatalogs()
    override fun sharePageUrl(kind:PersonalDataShareKind)="https://ff14risingstones.web.sdo.com/pc/index.html#/statistics/${kind.name.lowercase()}"
    override fun shareImageUrl(image:PersonalDataShareImage):String?=null
    override suspend fun fetchBoardContent(board:PersonalDataBoard):PersonalDataBoardContent=error("No legacy read expected")
    override suspend fun fetchUltimateDashboard():UltimateDashboard=error("No legacy read expected")
    override suspend fun fetchUltimateEncounterDetail(summary:UltimateEncounterSummary):UltimateEncounterDetail=error("No legacy read expected")
}
