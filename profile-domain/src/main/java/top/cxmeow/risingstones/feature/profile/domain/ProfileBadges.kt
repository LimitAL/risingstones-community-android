package top.cxmeow.risingstones.feature.profile.domain

/** Display facts from the same profile response; badge metadata grants no permissions. */
data class ProfileBadge(val field:String,val name:String,val level:Long,val weight:Long?=null)
data class ProfileBadgePresentation(val name:String,val description:String?,val iconFile:String?)

/** Exact local catalog from cached iOS 70d0065; official finished text stays untranslated. */
object ProfileBadgeCatalog {
    private data class Stage(val level:Long,val name:String,val description:String,val icon:String)
    private data class Entry(val field:String,val name:String,val description:String,val icon:String,val stages:List<Stage>)
    private val entries=listOf(
        Entry("test_limited_badge","测试贡献徽章","封闭测试期间对社区建设做出贡献。","test_limited_badge2.png",listOf(Stage(2L,"测试贡献徽章（银）","封闭测试期间登录社区且社区等级达到2级以上。","test_limited_badge2.png"), Stage(3L,"测试贡献徽章（金）","封闭测试期间提出了有效建议。","test_limited_badge3.png"))),
        Entry("posts2_creator_badge","攻略创作者徽章","发布的攻略帮助到了海量冒险者。","posts2_creator_badge1.png",listOf(Stage(1L,"攻略创作者徽章（铜）","测试期间内发布2篇以上攻略，或社区正式上线后发布的攻略中有1篇及以上(收藏+点赞)>=50","posts2_creator_badge1.png"), Stage(2L,"攻略创作者徽章（银）","发布的攻略中，有2篇及以上的 (收藏+点赞) >=100且<500","posts2_creator_badge2.png"), Stage(3L,"攻略创作者徽章（金）","发布的攻略中，有2篇及以上的 (收藏+点赞) >=500","posts2_creator_badge3.png"))),
        Entry("collapse_badge","崩之家","于石之家社区2023.12.18的崩溃中幸存。","collapse_badge.png",listOf()),
        Entry("warrior_badge","荣誉光之战士","达成了多项特殊成就。","achieve_bronze.png",listOf(Stage(1L,"荣誉光之战士（铜）","获得了石之家指定的20项特殊成就。","achieve_bronze.png"), Stage(2L,"荣誉光之战士（银）","获得了石之家指定的30项特殊成就。","achieve_silver.png"), Stage(3L,"荣誉光之战士（金）","获得了石之家指定的40项特殊成就。","achieve_gold.png"))),
        Entry("feifei_badge","好狒友","狒你莫属！狒狒将陪伴你的每一段旅途！","feifei_badge.png",listOf()),
        Entry("phaenna_badge","法恩娜行星纪念徽章","感谢冒险者们在宇宙探索中贡献的一份力量，恭喜中国区拉诺西亚服务器于2025年10月17日0:49达成全球首通！","phaenna_badge.png",listOf()),
        Entry("oizys_badge","俄匊斯行星纪念徽章","感谢冒险者们在宇宙探索中贡献的一份力量，恭喜中国区拉诺西亚服务器于2026年2月9日20:12达成全球首通！","oizys_badge.png",listOf()),
        Entry("auxesia_badge","奥克塞西亚行星纪念徽章","感谢冒险者们在宇宙探索中贡献的一份力量，恭喜中国区拉诺西亚服务器于2026年6月7日9:17达成全球首通！","auxesia_badge.png",listOf()),
        Entry("glamour_badge","AAA幻象棱晶批发","幻化投稿大受好评。","ffxivsc_bronze.png",listOf(Stage(1L,"AAA幻象棱晶批发（铜）","投稿的（收藏+赞）总量到达到达50。","ffxivsc_bronze.png"), Stage(2L,"AAA幻象棱晶批发（银）","投稿的（收藏+赞）总量到达到达200。","ffxivsc_silver.png"), Stage(3L,"AAA幻象棱晶批发（金）","投稿的（收藏+赞）总量到达到达500。","ffxivsc_gold.png"))),
        Entry("admin_tag","管理员徽章","社区管理员","adminvip_1.png",listOf(Stage(1L,"管理员徽章（银）","社区管理员","adminvip_1.png"), Stage(2L,"管理员徽章（金）","拂晓官方账号","adminvip_2.png")))
    )
    fun order(field:String):Int=entries.indexOfFirst{it.field==field}.let{if(it<0)entries.size else it}
    fun presentation(badge:ProfileBadge):ProfileBadgePresentation {
        val entry=entries.firstOrNull{it.field==badge.field} ?: return ProfileBadgePresentation(badge.name,null,null)
        val stage=entry.stages.firstOrNull{it.level==badge.level}
        return ProfileBadgePresentation(stage?.name ?: entry.name,stage?.description ?: entry.description,stage?.icon ?: entry.icon)
    }
}
