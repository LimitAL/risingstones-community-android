# 官方普通主题纯文字发布适配（本地验证）

`forum-data` 的 `OfficialForumPostApiPublisher` 实现 `OfficialForumPostPublisher`。调用者必须提供 `RisingStonesCapabilityScopeProvider`，捕获仅 `ForumWrite` 的单次凭据范围并限定 `api/home/posts/create`，不应给其他端点复用授权。该适配本身不创建凭据、不刷新身份、不重放 POST，也不授予图片、投票、删除或动态能力。

参照缓存 iOS 的普通主题阶段一合同：固定 `type=1`、空 `id/updated_at/cover_pic`、`is_share=0`、`scope=1`、`has_vote=0`；query 与 form 分别产生临时标识。只有数值业务码 10000 和正数帖子 ID 才确认成功。401/403 和明确业务拒绝不重放，断连、坏响应或缺 ID 必须作为结果未确认处理；宿主须在发出前保留本地待确认记录，重建后不能自动重发。

验证只使用本地有限传输替身与拦截器，未连接真实账号或发布任何主题。该资格不等于真实服务、Maven 制品或其他正式写能力通过。
