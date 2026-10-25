# 动态提及候选只读合同（2026-10-25）

依据缓存 iOS `70d0065` 的候选面板与用户读取服务。本批没有发出真实关注列表、玩家搜索或发布请求。

`ProfileApiService.fetchMentionCandidateSnapshot()` 专用请求 `GET api/home/userRelation/followList?page=1&limit=5000`，不传 UUID。不改通用 DynamicListQuery 的 100 上限，也不承诺服务端返回 5000 条或原子全量快照。

新增投影保留候选 UUID、姓名、大区、服务器、既有安全策略内的头像、来源总数（可未知）、原始行数、不可用／重复行统计、完整度和本机加载时刻。只有来源条数相符且无过滤损失时才标记本次响应一致；不把可选候选数量当全部关注人数。缺失、负数、非法 count 不补零；缺失 rows 不冒充空列表。

UUID 使用明确字符串或数字标量的文本值，拒绝布尔、空值、对象、掩码、控制字符与提及协议分隔字符；姓名同样必须可用。按 UUID 去重并保留统计，不按姓名猜用户。头像复用既有 ProfileApiService 白名单。

`searchMentionCandidates()` 与既有 `searchUsers()` 共用 type=6 请求构造。搜索来源是数组、无总数；原始页长决定可继续读取，不能用过滤或去重后的数量提前宣称结束。新的提及投影不改变旧 ProfileUserSearchService 的构造与业务规则。

独立 `ProfileMentionCandidateContractTest` 新文件 4 项用例通过，profile-data 共 47 项通过。旧受保护 DynamicApiServiceTest 未修改，旧发布专用合同的 SDK 自身测试缺口也不因此算关闭。App 另外提供身份、取消、缓存及选择状态测试与有限拦截原生流程；这些不等于真实服务或 Maven 制品验收。
