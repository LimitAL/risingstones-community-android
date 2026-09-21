package top.cxmeow.risingstones.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareArtifact

/** Uses only synthetic pixels. The chooser test opens the system panel without selecting a recipient. */
class PersonalDataPngShareHostTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private var host:PersonalDataPngShareHost?=null
    @After fun clear() { host?.clear() }
    @Test fun sendUsesChooserAndRetainsUntilExplicitClose()=runBlocking {
        var chosen:Intent?=null
        val subject=PersonalDataPngShareHost(context,{true}) { chosen=it }.also { host=it }
        subject.share(artifact())
        assertEquals(Intent.ACTION_CHOOSER,chosen?.action)
        assertEquals(1,files().size)
        subject.clear()
        assertTrue(files().isEmpty())
    }
    @Test fun accessRevokedAfterPreparationNeverLaunchesAndDeletesPng()=runBlocking {
        var checks=0;var launched=false
        val subject=PersonalDataPngShareHost(context,{ ++checks==1 }) { launched=true }.also { host=it }
        try { subject.share(artifact());fail("Revoked access must fail") } catch (_:IllegalStateException) { }
        assertFalse(launched);assertTrue(files().isEmpty())
    }
    @Test fun launcherFailureDeletesPreparedFile()=runBlocking {
        val subject=PersonalDataPngShareHost(context,{true}) { throw IllegalStateException("Synthetic failure") }.also { host=it }
        try { subject.share(artifact());fail("Expected synthetic failure") } catch (_:IllegalStateException) { }
        assertTrue(files().isEmpty())
    }
    @Test fun cancellationRemainsCancellationAndDeletesPreparedFile()=runBlocking {
        val subject=PersonalDataPngShareHost(context,{true}) { throw CancellationException("Synthetic cancellation") }.also { host=it }
        try { subject.share(artifact());fail("Expected cancellation") } catch (_:CancellationException) { }
        assertTrue(files().isEmpty())
    }
    @Test fun realSystemChooserOpensAndBackDoesNotPrematurelyDeletePng()=runBlocking {
        val subject=PersonalDataPngShareHost(context,{true}).also { host=it }
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String):String=automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        var panelMayBeOpen=false
        try {
            subject.share(artifact())
            panelMayBeOpen=true
            val deadline=android.os.SystemClock.uptimeMillis()+5_000
            var opened=false
            while(!opened && android.os.SystemClock.uptimeMillis()<deadline) {
                opened=shell("dumpsys activity activities").lineSequence().any {
                    (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("ChooserActivity")
                }
                if(!opened) android.os.SystemClock.sleep(100)
            }
            assertTrue("Synthetic image must open the system chooser",opened)
            assertEquals(1,files().size)
            shell("input keyevent 4")
            panelMayBeOpen=false
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("Chooser return does not mean the receiver has finished",1,files().size)
        } finally { if(panelMayBeOpen) shell("input keyevent 4");subject.clear() }
    }
    private fun files()=File(context.cacheDir,"share").listFiles().orEmpty().toList()
    private fun artifact():PersonalDataShareArtifact {
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val png=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() };bitmap.recycle()
        return PersonalDataShareArtifact(png,2,2,"Synthetic image")
    }
}
