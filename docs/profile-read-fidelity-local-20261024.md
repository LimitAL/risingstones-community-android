# 用户资料读取保真（本地合同）

以缓存 iOS 70d0065 的 RisingStonesUserInfoDTO、UserView 为依据。新增 ProfileReadSnapshot / ProfileReadCounts 与 ProfileApiService.fetchReadSnapshot；一次既有 getUserInfo GET 同时返回资料、可见部队引用与原始可空关注/粉丝/获赞数。CommunityProfile、ProfileGuildSnapshot、ProfileService 的旧公开声明未改，旧零默认行为保留供旧消费者；不得从旧字段恢复未知信息。

characterDetail 对象和首个数组对象使用同一投影；空、缺失、坏形态为不可用。原有他人公开开关及同响应 UUID/本人 relation 判定保持，隐藏事实不随兼容形态泄露，无新增请求或私有访问。

来源计数仅接可解析、非负、有符号 Long 范围内整数；明确零保留，空/布尔/小数/负值/溢出/坏形态为未知，不借列表 count、已加载长度或其他计数补值。计数独立于资料隐私事实。

修复前对象详情丢失和缺失计数降零均有离线失败记录。新增四项及完整 1485 单测、lint、Debug 构建通过；边界、分层、公共源码安全检查通过。两份历史文档本机路径布局检查失败未改。当前仅 source-composite 本地验证，未发布 Maven、验证已发布制品升级或真实服务；无真实账号和外部请求。
