# 我的副本招募只读合同（2026-10-25）

依据缓存 iOS `70d0065` 的 Publishing 扩展和 MyDutyRecruitmentsView。新增独立 MyDutyRecruitmentReader，不启用混合管理接口；既有 mock Manager、发布和草稿契约保持不变。

读取为认证 GET `api/home/recruit/myFbRecruitList`，page 为正整数、limit 固定 10，query 带 tempsuid，无请求体和 UUID 参数。要求既有 AccountRead，业务码严格为整数 10000；缺失或畸形 data/rows 报错，不冒充空列表。

独立页面投影保留来源可未知 Long count、原始行数、坏行和页内重复数量、本机加载时间、原始可未知 status。ID 必须为正整数字段；本批严格整数解析不修改旧通用解析器。只有 status=1 可进入现有详情，其他值按 iOS 分至未上架，保留原始值；不按时钟推断上下架。不以过滤/去重后条数或缺失 count 伪造总数和结束；原始页长小于 10 才无后续页。

App 对跨页重复另计，保留每页来源统计。整页没有有效新增时停止自动循环，仍保留可能有后续页的事实，可由用户明确继续下一页；读取失败重试原页。

本批独立 MyDutyRecruitmentReadContractTest 三项及 recruitment-data 全部 68 项通过。传输为内存 fail-closed fixture，验证精确 GET、严格码、来源零/未知/非法总数、缺失载荷、坏行/重复和状态/分页事实。没有请求真实账号或服务，没有创建、上下架、删除、联系或授权写能力。

非空行结构仍只是缓存 iOS 源码沿用公开卡片的推定，不是服务实测确认；不宣称 Maven 消费、真实媒体或严格双端验收。既有两项 SDK 历史文档检查问题未改，旧受保护发布测试未写入，本批读取测试不关闭旧发布合同测试缺口。
