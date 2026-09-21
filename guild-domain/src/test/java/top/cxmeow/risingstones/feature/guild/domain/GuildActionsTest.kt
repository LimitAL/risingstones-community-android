package top.cxmeow.risingstones.feature.guild.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GuildActionsTest {
    @Test
    fun uploadInputOwnsItsBytesAndReturnsCopies() {
        val source = byteArrayOf(1, 2, 3)
        val input = GuildImageUploadInput(source, "image/png")
        source[0] = 9
        val first = input.copyBytes()
        first[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), input.copyBytes())
        assertEquals(3, input.byteCount)
    }

    @Test
    fun activeTimeUsesAsciiOfficialFormatWithoutInventingOrderingRule() {
        assertEquals("24:00-00:00", GuildActiveTimeRange(24, 0).officialValue)
    }

    @Test
    fun commentMentionsCannotBreakOfficialHashSeparatedIdentity() {
        for (invalid in listOf("bad#uuid", "bad\nname")) {
            try {
                GuildPhotoCommentMention(invalid, "Name")
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun commentDraftRequiresConsistentReplyIdsAndSomeContent() {
        try {
            GuildPhotoCommentDraft(photoId = 1, contentHtml = "", parentId = 2, rootParentId = 0)
            fail()
        } catch (_: IllegalArgumentException) {
        }

        assertEquals(
            2,
            GuildPhotoCommentDraft(
                photoId = 1,
                contentHtml = "reply",
                parentId = 2,
                rootParentId = 2,
            ).parentId,
        )
    }
}
