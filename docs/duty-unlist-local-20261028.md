# 副本招募下架的独立本地适配

`DutyRecruitmentUnlister` 和 `DutyRecruitmentUnlistApiService` 只提供下架，不实现混合管理器、上架、删除、分享或媒体。
依据缓存 iOS `70d0065` 的 `DutyRecruitmentAPIService+Publishing.swift` 与发布合同文档；该下架请求是官方网页 JS 确认，尚未取得真实服务验收证据。

- 认证 `PUT api/home/recruit/shelveRecruitFb`，JSON 只含正整数 `id` 与字符串 `status:"0"`。
- `tempsuid` 仅在 query；不在 JSON 补表单字段。临时请求标识不代表服务端幂等保证。
- 捕获 `RecruitmentWrite` 的单次作用域，发送前、返回后与完成时验证归属；不读取删除资格或额外私有资料。
- 严格数值 `code=10000` 才正常返回，允许空 `data`。不根据响应或本地列表猜测新的期限、顺序或计数。
- 明确普通业务拒绝使用 `DutyUnlistRejected`；HTTP 错误、认证异常、损坏／缺失成功码或传输失败使用 `DutyUnlistUnconfirmed`。取消保持协程取消。
- 不刷新凭据、不自动重试 PUT、不切备用主机重放。宿主注入的传输仍须自行保证不重试；项目现有 OkHttp 写客户端已禁用重试、认证器和重定向。

宿主必须先核实当前身份与“我的招募”来源行状态为 `1`，发送前持久记录尝试。超时或取消不证明失败；刷新后状态 `0` 只证明当前已下架，缺行／部分页／状态仍 `1` 不能证明旧请求失败。若提供结束跟踪，必须由用户看过风险后明确确认，保留旧尝试和迟返记录；结束本身不发送请求，新操作须另一次手动触发。

专用 `DutyRecruitmentUnlistContractTest` 使用有限合成传输和非秘密输入，覆盖准确参数、空成功数据、严格业务码、认证／损坏／传输错误、发送前后代次变化和取消；测试不调用真实账号、下架、凭据获取或外部网络。没有修改历史受保护发布测试。本批 SDK 专用合同 4 项通过，完整 1534 项单测零失败／零跳过、lint 87 Warning／4 Hint 及 assembleDebug 通过；公开中文文档、源码安全及 40 模块依赖检查通过。宿主另以底层 DNS／Socket 禁网与有限合成响应验证正式入口。此为本地资格，不冒称真实服务／Maven／线上资格通过。
