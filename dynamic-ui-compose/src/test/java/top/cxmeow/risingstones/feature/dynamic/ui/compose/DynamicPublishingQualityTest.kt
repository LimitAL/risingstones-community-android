package top.cxmeow.risingstones.feature.dynamic.ui.compose
import org.junit.Test
import org.junit.Assert.*
class DynamicPublishingQualityTest {
 @Test fun exactIosOriginalByteThresholds(){for((bytes,expected)in listOf(0 to 80,(5*1024*1024-1) to 80,(5*1024*1024) to 60,(10*1024*1024-1) to 60,(10*1024*1024) to 40,(20*1024*1024) to 40))assertEquals(expected,dynamicPublishingJpegQuality(bytes))}
}
