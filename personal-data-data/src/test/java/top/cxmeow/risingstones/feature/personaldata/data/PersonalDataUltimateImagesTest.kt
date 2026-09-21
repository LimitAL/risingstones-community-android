package top.cxmeow.risingstones.feature.personaldata.data

import java.net.URI
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDataUltimateImagesTest {
    @Test
    fun eachReviewedEncounterUsesItsOwnPublishedCoverIncludingTheExplicitDefault() {
        val covers = mapOf(
            733 to "03d05c7264a9e0e5", 777 to "5b2d6831aac2af1d", 887 to "37021af70e09f86c",
            968 to "e1176e944b308333", 1122 to "d38ad293f3237cb6", 1238 to "39cd1fd5bf0ae3ce",
            1363 to "191b305bd9531bfa",
        )
        for ((territory, image) in covers) {
            assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/ff14_$image.png", personalDataUltimateCoverUrl(territory))
        }
    }

    @Test
    fun unknownEncounterNumbersDoNotReceiveAnotherEncountersCoverOrMedal() {
        for (id in listOf(Int.MIN_VALUE, -1, 0, 1, 7, 732, 734, 778, 900, 1163, 1364, Int.MAX_VALUE)) {
            assertNull("cover $id", personalDataUltimateCoverUrl(id))
            assertNull("medal $id", personalDataUltimateMedalImageUrl(id))
        }
    }

    @Test
    fun medalsFollowTheReviewedEncounterMappingWithoutPaddingOrIconSuffixes() {
        val medals = mapOf(733 to 1, 777 to 2, 887 to 3, 968 to 4, 1122 to 25, 1238 to 43, 1363 to 46)
        for ((territory, medal) in medals) {
            assertEquals("$base/medal/medal$medal.png", personalDataUltimateMedalImageUrl(territory))
        }
    }

    @Test
    fun allReviewedJobsHaveOrdinaryImagesAndAThreeGroupOrder() {
        val tanks = mapOf("骑士" to "DoW/PLD", "战士" to "DoW/WAR", "暗黑骑士" to "DoW/DRK", "绝枪战士" to "DoW/GNB")
        val healers = mapOf("白魔法师" to "DoM/WHM", "学者" to "DoM/SCH", "占星术士" to "DoM/AST", "贤者" to "DoM/SGE")
        val damage = mapOf(
            "武僧" to "DoW/MNK", "龙骑士" to "DoW/DRG", "忍者" to "DoW/NIN", "武士" to "DoW/SAM",
            "钐镰客" to "DoW/RPR", "蝰蛇剑士" to "DoW/VPR", "驯兽师" to "DoW/BST", "吟游诗人" to "DoW/BRD",
            "机工士" to "DoW/MCH", "舞者" to "DoW/DNC", "黑魔法师" to "DoM/BLM", "召唤师" to "DoM/SMN",
            "赤魔法师" to "DoM/RDM", "绘灵法师" to "DoM/PCT", "青魔法师" to "DoM/BLU",
        )
        listOf(tanks, healers, damage).forEachIndexed { order, group ->
            for ((name, path) in group) {
                assertEquals("$base/job/$path.png", personalDataUltimateJobIconUrl(name))
                assertEquals(order, personalDataUltimateJobOrder(name))
            }
        }
    }

    @Test
    fun groupOrderAllowsStableSourceOrderInsteadOfReorderingJobsByCatalogIndex() {
        val source = listOf("青魔法师", "战士", "Unknown A", "骑士", "学者", "暗黑骑士", "白魔法师", "Unknown B")
        val sorted = source.sortedBy { personalDataUltimateJobOrder(it) ?: Int.MAX_VALUE }
        assertEquals(listOf("战士", "骑士", "暗黑骑士", "学者", "白魔法师", "青魔法师", "Unknown A", "Unknown B"), sorted)
    }

    @Test
    fun unknownNamesAndPathLikeInputDoNotGainAnIconOrKnownRole() {
        for (name in listOf("", "全部", "其他", "Unknown", "PLD", "19", "骑士 ", " 騎士", "采矿工", "../PLD", "DoW/PLD", "骑士\n", "https://example.invalid/icon.png")) {
            assertNull(name, personalDataUltimateJobIconUrl(name))
            assertNull(name, personalDataUltimateJobOrder(name))
        }
    }

    @Test
    fun imageUrlsRemainOnTheReviewedHttpsHostsWithoutCredentialsOrQueryParameters() {
        val urls = mapOf(
            requireNotNull(personalDataUltimateCoverUrl(733)) to "ff14risingstones.web.sdo.com",
            requireNotNull(personalDataUltimateJobIconUrl("白魔法师")) to "static.web.sdo.com",
            requireNotNull(personalDataUltimateMedalImageUrl(1363)) to "static.web.sdo.com",
        )
        for ((value, host) in urls) {
            val uri = URI(value)
            assertEquals("https", uri.scheme)
            assertEquals(host, uri.host)
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
    }

    @Test
    fun localeDoesNotChangeResourceNamesOrJobMatching() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("$base/medal/medal25.png", personalDataUltimateMedalImageUrl(1122))
            assertEquals("$base/job/DoW/PLD.png", personalDataUltimateJobIconUrl("骑士"))
            assertEquals(0, personalDataUltimateJobOrder("骑士"))
            assertNull(personalDataUltimateJobIconUrl("pld"))
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        const val base = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones"
    }
}
