package top.cxmeow.risingstones.feature.forum.domain

import org.junit.Assert.*
import org.junit.Test

class OfficialForumLinkTargetsTest {
    private val base = "https://ff14risingstones.web.sdo.com/pc/index.html"

    @Test fun entityTypesFollowOfficialSiteRoutes() {
        val cases = listOf(
            "#/post/detail/131913/2147091" to OfficialForumLinkTarget(OfficialForumLinkKind.Post, 131913),
            "#/posts/detail/77" to OfficialForumLinkTarget(OfficialForumLinkKind.Post, 77),
            "#/strat/detail/5120" to OfficialForumLinkTarget(OfficialForumLinkKind.Post, 5120),
            "#/tiedes/131913" to OfficialForumLinkTarget(OfficialForumLinkKind.Post, 131913),
            "#/wikides/5120" to OfficialForumLinkTarget(OfficialForumLinkKind.Post, 5120),
            "#/glamour/detail/293183" to OfficialForumLinkTarget(OfficialForumLinkKind.Glamour, 293183),
            "#/dynamic/detail/880021" to OfficialForumLinkTarget(OfficialForumLinkKind.Dynamic, 880021),
            "#/recruit/guild/detail/312" to OfficialForumLinkTarget(OfficialForumLinkKind.GuildRecruitment, 312),
            "#/recruit/roleplay/detail/77" to OfficialForumLinkTarget(OfficialForumLinkKind.RolePlayRecruitment, 77),
        )
        for ((route, expected) in cases) {
            assertEquals(route, expected, OfficialForumLinkParser.target(base + route))
            assertEquals(route, if (expected.kind == OfficialForumLinkKind.Post) expected.id else null, OfficialForumLinkParser.postId(base + route))
        }
    }

    @Test fun typedRoutesWinOverUnrelatedQueryIdsAndPreserveCaseAndEncodedSegments() {
        val expected = OfficialForumLinkTarget(OfficialForumLinkKind.Glamour, 42)
        for (url in listOf(
            "$base?id=999#/glamour/detail/42?id=888",
            "https://ff14risingstones.web.sdo.com/glamour/detail/42?id=888",
            "$base#/GLAMOUR/DETAIL/42",
            "$base#/%67lamour/detail/%34%32",
            "https://apiff14risingstones.web.sdo.com/glamour/detail/42",
        )) assertEquals(url, expected, OfficialForumLinkParser.target(url))
        assertEquals(43, OfficialForumLinkParser.postId("https://apiff14risingstones.web.sdo.com/post?id=%34%33"))
    }

    @Test fun knownPostPathsAndQueriesRetainOrdinaryAnchors() {
        assertEquals(43, OfficialForumLinkParser.postId("https://ff14risingstones.web.sdo.com/post?id=43#comment-7"))
        assertEquals(42, OfficialForumLinkParser.postId("https://ff14risingstones.web.sdo.com/post/detail/42#comment%2F7"))
    }

    @Test fun unknownMalformedAndExternalPagesNeverBecomeNativeEntities() {
        val routes = listOf(
            "#/me/glamour/collections/12", "#/event/detail/42", "#/event/detail/42?id=9",
            "#/glamour/detail/bad?id=9", "#/recruit/unknown/detail/42",
            "#/glamour/detail/0", "#/glamour/detail/-1", "#/dynamic/detail/2147483648",
            "#/glamour/detail/", "#/glamour%2Fdetail/42", "#/glamour/detail/%2534%2532",
        )
        for (route in routes) assertNull(route, OfficialForumLinkParser.target(base + route))
        for (url in listOf(
            "http://ff14risingstones.web.sdo.com/glamour/detail/42",
            "https://ff14risingstones.web.sdo.com.evil.test/glamour/detail/42",
            "https://ff14risingstones.web.sdo.com@evil.test/glamour/detail/42",
            "https://example.com/glamour/detail/42",
            "astria://glamour/detail/42", "//ff14risingstones.web.sdo.com/glamour/detail/42",
            "https://ff14risingstones.web.sdo.com/post?id=%", "not a URL",
        )) assertNull(url, OfficialForumLinkParser.target(url))
    }
}
