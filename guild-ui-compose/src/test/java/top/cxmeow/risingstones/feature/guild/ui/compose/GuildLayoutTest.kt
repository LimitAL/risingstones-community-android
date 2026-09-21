package top.cxmeow.risingstones.feature.guild.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class GuildLayoutTest {
    @Test fun exactBreakpointsSelectExpectedLayout() {
        assertEquals(GuildLayoutMode.Compact, guildLayoutMode(599))
        assertEquals(GuildLayoutMode.Medium, guildLayoutMode(600))
        assertEquals(GuildLayoutMode.Medium, guildLayoutMode(839))
        assertEquals(GuildLayoutMode.Expanded, guildLayoutMode(840))
    }
}
