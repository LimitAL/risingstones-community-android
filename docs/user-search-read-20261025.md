# 玩家搜索只读投影（2026-10-25）

## 合同与兼容

依据缓存 iOS70d0065 的 searchUsers 与用户摘要 DTO，新增 ProfileUserSearchService、ProfileUserSearchEntry、ProfileUserSearchPage；ProfileApiService 兼容实现新增接口。旧 ProfileService、CommunityProfile、ProfileListItem.Person 的构造和 copy 均不改。没有新增凭据存储、写入能力或用户权限。

唯一新增接口是 GET api/common/search，type=6，keywords 为已提交关键词，part_id 与 pageTime 留空，orderBy 固定 comment，page/limit 为正整数。复用 AccountRead、既有响应策略和一次认证刷新，取消传播，不返回原始私有服务错误文字。宿主继续绑定调用身份，不能把旧请求刷新为另一个账号。

data 必须是数组；错误结构不是成功空态。缺失、空白、掩码、布尔、控制字符等不可导航 UUID 行跳过，不凭展示名猜身份。保留名字、区服、头像和简介。fansNum 是非负可空 Long：缺失或非法保持未知，明确零仍是零，不借关注数、获赞数或当前列表长度补值。

响应不给来源总数，Page 不提供虚构 total。hasMore 仅为原始数组达到 limit 的续页提示，不能当成来源总量；调用方按 UUID 保留顺序去重，无新增行时停止续页，刷新失败保留已加载事实。

## 头像保真

离线测试复现把单头像交给多图拆分函数会截断合法逗号查询参数。单头像改用完整 URI 验证，贯通搜索、既有资料及人员投影；多图字段仍按原逗号列表拆分。https、端口、userinfo 与原有域名限制不变，没有新增下载或域名权限。

## 验证边界

最终完整1499项 SDK 单测、lint、Debug构建通过，零失败、错误、跳过。包含5项搜索协议用例与1项既有资料／人员头像链回归；旧消费者回归保持通过，但没有验证已发布 Maven 制品或外部二进制消费者。

所有新增验收通过本地终止传输和 fixture；没有真实服务、账号、头像联网或正式业务操作。宿主原生验证与准确边界记录在 Android 对齐检查点。本次静态文档检查先阻于 HEAD 已存在且未改的 dynamic-text-comment-local-20261025.md 缺少中文；另两份历史文档路径问题仍在，均独立保留，不因构建通过而称为全部检查通过。所有仓库 local-only，无推送、Actions或发布。
