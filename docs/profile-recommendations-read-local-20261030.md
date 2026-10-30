# 推荐用户只读合同与本地验证

新增可选 `ProfileRecommendationService.fetchRecommendedUsers()`，由 `ProfileApiService` 实现；旧 `ProfileService`、列表枚举及构造签名保持不变。

## 来源合同与边界

依据缓存 Apple 客户端的用户关系服务与推荐列表 DTO：认证 GET `api/home/userRelation/getUnFollowFriend`，无目标 UUID、页码或 limit；使用既有 AccountRead 与当前授权器，不申请新能力或持久凭据。只有业务码 10000 才解码列表；认证失效沿用既有只读刷新一次，取消继续传播。

`ProfileRecommendationSnapshot` 保留来源行数 `sourceRows`、坏行数 `invalidRows`；有效行保留 `sourceIndex`、明确 UUID、名字、区服、头像、简介与 `recommend_type`。没有来源总数或分页保证，不能把有效行数叫作全部关注人数或全部可推荐人数。缺失／非法 UUID 不创建身份；来源重复 UUID 保留，分组按推荐理由首次出现的顺序、组内保持来源顺序。头像沿用安全图片过滤；未知理由不合成标题。

接入方应把推荐列表绑定当前账号、角色与凭据代次，并仅在已加载资料确认本人时显示入口；不能凭缓存 UUID 认定本人。身份变更、取消或迟返后不得发布旧列表。此读取合同不包含关注、取消关注、发布、上传或管理操作。

## 本地证据

`ProfileRecommendationsReadTest` 使用有限合成传输，只接受上述精确 GET 与空查询；测试顺序、重复、坏行、空与畸形响应、只读资格、业务错误、认证刷新和取消。所有输入均虚构，无真实账号或服务请求。

本文为源码合同与离线测试范围，不表示真实服务、Maven 独立消费或严格双端视觉配对通过。非空服务端结构仅依据缓存源码与合成样本。
