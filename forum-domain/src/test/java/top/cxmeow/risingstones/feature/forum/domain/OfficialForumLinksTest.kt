package top.cxmeow.risingstones.feature.forum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialForumLinksTest {
    @Test
    fun resolvesOfficialPostRoutesAndQueries() {
        assertEquals(
            42,
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/detail/42",
            ),
        )
        assertEquals(
            43,
            OfficialForumLinkParser.postId(
                "https://apiff14risingstones.web.sdo.com/post?id=43",
            ),
        )
        assertEquals(
            44,
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/#/detail/44",
            ),
        )
        assertEquals(
            45,
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/#/post?id=45",
            ),
        )
        assertEquals(
            131913,
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/mob/index.html#/tiedes/131913",
            ),
        )
    }

    @Test
    fun rejectsLookalikesInvalidIdsAndMalformedEscapes() {
        assertNull(
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com.evil.test/detail/42",
            ),
        )
        assertNull(
            OfficialForumLinkParser.postId(
                "http://ff14risingstones.web.sdo.com/detail/42",
            ),
        )
        assertNull(
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/detail/0",
            ),
        )
        assertNull(
            OfficialForumLinkParser.postId(
                "https://ff14risingstones.web.sdo.com/post?id=%",
            ),
        )
    }

    @Test
    fun buildsOfficialEmojiResourceUrl() {
        assertEquals(
            "https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo46.png",
            OfficialForumResourceUrls.emojiImageUrl(46),
        )
    }
}
