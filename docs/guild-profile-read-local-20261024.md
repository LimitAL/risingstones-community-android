# 明确部队ID与部队只读投影（2026-10-24）

参考固定缓存iOS70d0065。ProfileApiService.fetchProfileWithGuild在同一getUserInfo响应中保留ProfileGuildSnapshot：只有Self或明确公开时，characterDetail[0].fc_id为非零ASCII十进制串才提供ProfileGuildReference。不从部队名猜ID，不将缺失ID当无部队。新投影不改变原CommunityProfile构造／copy ABI。

GuildApiService.readInfo提供GuildReadSnapshot：独立可空成员数／活跃数、明确isGuildMember、guild_board及part_date；缺失不伪造0，星号房屋仍按私有处理。原info接口保持原合同。readPhotos提供GuildReadPhoto上传者UUID与原照片字段；GET短非空页继续、空页结束，原photos接口保留。缺失／占位UUID不猜作者。

本地mock单测覆盖公开／本人／隐私、缺失与非法ID、大数ID、同响应单次请求、未知／零成员数、星号房屋、实际来源字段、短页和空页。没有真实账号、写接口、上传、发布、分享、Maven发布或制品升级。原两项历史文档路径检查失败保持单列。
