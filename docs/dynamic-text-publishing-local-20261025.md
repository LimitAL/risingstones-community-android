# 纯文字动态创建的独立合同（2026-10-25）

依据缓存 iOS `70d0065` 的 `RisingStonesUserAPIService.createDynamic`、动态 DTO 与编辑器；本批仅代码适配和本地拦截验证，没有向真实服务发布动态。

- 新增 `DynamicApiService.beginTextPublishingScope()`，只捕获 `DynamicWrite`，不申请图片上传能力。
- 新增 `publishText(scope, draft): DynamicEntry`，只允许非空正文及空图片列表。调用 `POST api/home/dynamic/create`，按已有表单编码传 `content`、`scope`（1/2/3）、空 `pic_url`、已知 UUID 的 `atInfo`；query 与 form 分别生成独立 `tempsuid`。
- 仅接受数值业务码 `10000`，返回必须为能投影出正整数 ID 的动态对象。缺失、非法返回不伪造成成功；成功对象复用现有动态投影，不从其他来源补事实。
- 创建后的作用域与凭据仍须当前有效；401/403 不重放 POST，写入也不切换备用主机重发。App 对已经交付传输但结果未知的请求另外做本机持久隔离。
- 原有 `DynamicPublishingService.publish`、媒体、转发及既有消费者合同保持不变。本新增入口不代表这些路径均完成 iOS 对齐。

验证：动态数据模块现有 42 项回归通过；App 的 9 项新增真实 SDK／有限本地传输测试覆盖三类状态、精确表单与提及 UUID、权限范围、身份代次、重复提交、取消及未知结果跨服务实例阻断。未匹配请求直接失败，不做外部业务请求。新增合同由上述 App 的 SDK 集成用例验证，既有通用接口回归的通过范围另行保留。

真实账号／真实服务、图片上传、候选提及读取、删除、Maven 制品及严格双端原图配对均不在本批通过范围。既有公共文档与私有路径检查历史失败仍独立保留，未改基线或抑制。
