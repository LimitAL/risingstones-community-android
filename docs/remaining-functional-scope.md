# 原生移植剩余功能清单

## 目标、证据与维护方式

本清单记录 2026-09-18 对官方移动站公开脚本与当前仓库源码的核对结果，服务于以下顺序：

1. 完成官方移动站全部有效功能的原生移植，覆盖入口、读取、操作、返回和失败处理。
2. 全部功能完成后，整体评估并实施导航、信息结构、页面层级和交互路径的 UI/UE 重构。
3. 最后专项优化原生 Android 动效，达到连贯、流畅的体验，并兼顾系统返回与无障碍偏好。

接口或 ViewModel 存在，不代表独立客户端已完成该功能。功能完成至少需要原生可达入口、
完整操作路径、正确权限与会话边界，以及与改动范围匹配的验证。不能将按钮接入、目录接入、
作者导航或数据缺失归入最终视觉改造后再处理。

公开路由证据来自 `app.22e9274d.js`，功能证据来自下文列出的公开分块脚本；脚本文件名仅用于
定位证据，不作为客户端运行时依赖。路由或接口包装器存在，只证明有对应源码；历史活动、
迁移与测试资格入口还须确认当前可达性，不根据名称猜测请求参数或账号权限。

本文件不保存公开脚本副本、浏览器快照、请求日志、账号响应或任何凭证。接口设计与验证记录
分别见[官方移动站移植记录](web-parity-plan.md)及[兼容性矩阵](compatibility-matrix.md)。

状态使用以下含义：

| 状态 | 含义 |
| --- | --- |
| 公共能力已有，原生流程待接入 | 领域、服务或状态机已有部分实现，但默认 Compose 或独立 App 没有完整操作入口 |
| 领域或服务待补 | 缺少完整公共模型、请求实现、状态机或原生界面，不只是缺一个按钮 |
| 证据待补 | 官方页面调用、请求参数、成功结构、当前可达性或真实账号结果尚不足 |
| 正在补齐 | 当前批次已开始实现，完成并验证后更新状态，不与其他批次重复建设 |
| 已补齐 | 所列原生流程已接通并通过对应检查；具体线上及写入验证边界仍单独说明 |
| 最终专项 | 功能完成后的整体 UI/UE 或动效工作 |

下文的 P0、P1、P2 表示实施顺序，不表示 P2 可以从最终目标中省略。每项完成后应更新状态、
实际入口、验证日期及证据类别；接口仍待验证时保留该边界，不因单元测试通过改为线上已验证。

## 当前已有证据，不应重置为未验证

2026-09-18 的独立 Android 官方 WebView 会话已验证 `AccountRead`、`GlamourAuthenticated`、
`PersonalData`、`DynamicRead`、`MessageRead`。已完成的实际读取包括：

- 原生账号摘要、本人个人主页、本人帖子收藏、关注动态和动态详情。
- 动态来源进入原生幻化详情。
- 原生消息未读摘要与系统消息列表；其他消息类别另有浏览器鉴权证据，不能等同于全部 Android
  类别均已重新验证。消息中已经实现已回应记录列表，不应再次列为整项未实现。
- 强制结束并冷启动后恢复账号摘要，验证保存的 Cookie 与原登录 User-Agent 可用于当前会话。
- 蜃景总览、辅助职业进度、稀有道具历史、半魂晶历史的原生读取与显示。
- 蜃景与深层迷宫十五个白名单请求的设备内只读结构检查；深层迷宫结果为空数组。
- 原生投影台关注流、标签筛选、本人作品空态与统计、作者作品及公开收藏入口、返回所选作品。
  七个固定投影台读取请求的设备结构检查成功；没有自动验证关注或收藏等写入。
- 原生投影台装备候选搜索与选中后的作品列表均为非空；装备、眼镜、配饰和本人收藏夹四个
  固定只读请求结构检查成功。收藏夹管理、指定收藏和优惠券界面已接通，真实写入仍待验证。

朝圣交错路当前 `page_a` 未开放，原生页正确显示未开放状态；空数组不证明账号拥有开放权限，
也不证明非空记录的线上结构。非空深层迷宫记录目前依靠公开源码及合成夹具覆盖。

2026-09-20 用户再次确认 Android 官方 WebView 登录后，原生账号摘要复核成功。保留数据升级
当前 Debug 版本并冷启动后，账号、投影台、个人数据、动态、消息五项读取能力恢复，新增部队
招募只读验证也成功；四项写能力仍未验证。此次仅保留能力布尔结果，不保存个人响应或凭证，
也不代表此前各分区都已重新逐项读取。

源码检查、单元测试、模拟器交互和真实 Android 会话结果分别记录。上述结果的完整范围以
[兼容性矩阵](compatibility-matrix.md)为准；后续证据只补充，不覆盖已有有效记录。

## P0：先打通已有公共能力到独立客户端的操作路径

### 论坛阅读、互动与投票

状态：原生互动与首次明确操作授予流程已接入；真实账号写入结果仍待验证。

- [x] 接入帖子点赞、收藏及状态反馈。
- [x] 接入发表评论、回复主评论与楼中楼、单张评论图片选择和上传、提交成功后的列表更新。
- [x] 接入参与投票的选项选择、选择数量限制、提交、结果更新与不可重复操作状态。
- [x] 保留已有评论点赞、删除自己的评论、评论排序、只看作者和楼中楼读取。
- [x] 状态机覆盖取消、普通错误、权限不可用、会话失效，以及刷新时旧内容保留。
- [x] 补齐 46 个官方评论表情、关注对象提及选择、原生图文编辑、光标与草稿恢复。
- [ ] 在用户明确授权的真实互动中验证写能力；不能用点赞、评论、投票或图片上传自动探测。

仓库证据：[论坛状态机](../forum-presentation/src/main/java/top/cxmeow/risingstones/feature/forum/presentation/OfficialForumPresentation.kt)
通过独立 `interactionState` 管理草稿、待提交、错误与投票状态；[论坛服务](../forum-data/src/main/java/top/cxmeow/risingstones/feature/forum/data/OfficialForumApiService.kt)
校正评论删除表单和图片上传契约。[默认论坛界面](../forum-ui-compose/src/main/java/top/cxmeow/risingstones/feature/forum/ui/compose/RisingStonesForumScreen.kt)
已连接互动入口；通过可选首次操作资格扩展允许已验证账号明确执行互动，实际成功后分别授予
`ForumWrite` 或 `ForumImageUpload`。资格与已验证能力分开，图片选择本身不联网或授予能力。

评论编辑器通过可选 `OfficialForumCommentAuthoringService` 获取关注候选和发送 `atInfo`；
`commentEditorState` 保留 UTF-16 选区与有效身份范围。原生 `EditText` 实际绘制表情和姓名，
修改提及文字或缺少精确替换范围时清除身份，防止同名对象被误通知。候选读取不授予写能力。
本批五十四项论坛合成设备用例通过，四条主流程另在实际 599、600、839、840dp 窗口验证；
真实关注候选读取和实际发送仍单独保留待验证，详见[兼容性记录](compatibility-matrix.md)。

官网证据：`chunk-0819b39c.a493ceca.js` 中的 `posts/like`、`posts/star`、`posts/comment`、
`posts/vote`；完整详情位于 `chunk-7e966c18.469d1215.js`，评论编辑器位于公共分块的
`PostCommentFun`。这里的投票是参与现有投票；发起投票属于后文的内容创作。

### 招募筛选、回应与跑团评论

状态：以下筛选、互动与首次明确操作授予流程已接入；真实回应、点赞结果仍待验证。

- [x] 接入副本类型、名称、多位置、队伍构成、团队分队、大区与标签筛选。
- [x] 接入新人身份和风格、部队成员范围和标签、其他类别，以及跑团类型、状态和排序。
- [x] 跑团与其他招募支持同区服务器多选、换区重置和各自的全服参数语义。
- [x] 接入副本、新人回应表单、草稿保留/恢复/丢弃、联系人结果、提交失败反馈及一次提交。
- [x] 接入跑团评论排序、继续加载、楼中楼分页、点赞与局部重试。
- [x] 保留五类列表、详情、成员摘要、评论与评分读取；部队入口仍受独立能力约束。
- [x] 跑团完整成员分页、成员详情及活动列表与详情已接入独立阅读层，保留父跑团和各分区选择。
- [ ] 真实招募写能力与回应、点赞结果尚未验证，不能用写接口自动探测。

仓库证据：[招募状态机](../recruitment-presentation/src/main/java/top/cxmeow/risingstones/feature/recruitment/presentation/DutyRecruitmentViewModel.kt)
通过可选浏览与作者资格扩展提供完整筛选、草稿和分页状态；
[默认招募界面](../recruitment-ui-compose/src/main/java/top/cxmeow/risingstones/feature/recruitment/ui/compose/RisingStonesRecruitmentScreen.kt)
已连接以上入口。三十七项合成设备测试覆盖四个断点、配置重建、重复提交、联系人补读、资格未知时
草稿恢复，以及完整目录分页、详情、跨宽度返回时的滚动保留和活动链接。
2026-09-20 匿名原生成员列表及成员详情实测成功，活动列表返回正常空态；成员后续页与非空活动
详情目前由官方源码和合成测试覆盖。此前副本筛选、详情及跑团评论排序证据保留；真实写入及
后文管理生命周期仍未完成。

### 投影台已有能力的原生入口

状态：下表中的浏览、物品搜索及收藏操作入口已补齐；写入结果仍缺真实账号验证。

| 范围 | 当前状态与后续工作 |
| --- | --- |
| 关注流、标签、部族及时间等筛选、浏览游标 | 已补齐；四个断点和窗口变化设备测试通过，真实 Android 关注流及标签筛选成功 |
| 本人作品、作者作品与作者入口 | 已补齐并验证只读路径；作者作品入口不等于社区个人主页和所有作者资料均已完成 |
| 按装备、眼镜、配饰搜索投稿 | 已补齐候选、分组、装备分页及选择结果；三类目录有真实 Android 结构证据，装备选择到作品列表实测成功 |
| 作者关注与取消关注 | 已接入明确操作与失败反馈；已关注和互关均正确映射，真实写入仍未验证 |
| 收藏夹创建、删除 | 已补齐管理入口、默认夹删除限制、删除确认及独立刷新错误；合成设备测试通过，未执行真实写入 |
| 收藏夹改名、公开状态修改、收藏到指定文件夹 | 可选扩展契约及完整界面已补齐；选择器只读本人目录，提交需明确确认；旧默认收藏接口保留兼容 |
| 优惠券领取 | 已接入资格、关注并领取提示、提交及已领取反馈；合成测试确认只调用一次领取且不额外关注；真实写入待验证 |

仓库证据：[投影台模型](../glamour-domain/src/main/java/top/cxmeow/risingstones/feature/glamour/domain/GlamourModels.kt)、
[浏览扩展](../glamour-domain/src/main/java/top/cxmeow/risingstones/feature/glamour/domain/GlamourBrowsing.kt)、
[服务](../glamour-data/src/main/java/top/cxmeow/risingstones/feature/glamour/data/GlamourApiService.kt)、
[状态机](../glamour-presentation/src/main/java/top/cxmeow/risingstones/feature/glamour/presentation/GlamourPresentation.kt)、
[默认界面](../glamour-ui-compose/src/main/java/top/cxmeow/risingstones/feature/glamour/ui/compose/RisingStonesGlamourScreen.kt)。

官网证据：`chunk-78f68c56.3e50ce4c.js` 使用 `GlamourFavouritePopup`；入口脚本包含
`updateFavorites`、`claimCoupon`。本批范围与精确参数见[移植记录](web-parity-plan.md)。

2026-09-18 的二十六项投影台设备测试覆盖浏览、四个宽度边界、搜索、明确提交与取消、
收藏夹管理，以及实际 Activity 重建中提交只执行一次。所有写入使用合成服务；
既有 `favorite(id)` 的默认夹行为不变，新界面通过 `GlamourCollectionService` 显式指定。

### 官方响应码与授权重试

状态：`10000/10002` 接受策略已在现有模块调用链中对齐；其他响应码语义不在本批推断。

- [x] 核对 `network` 会话校验和各功能模块对 `10002` 的处理。官方客户端接受该码，不能
  将人工“未登录”夹具当作官方失效证据；须避免错误撤销和重复写入。
- [x] 区分接受响应、有效领域负载、功能资格和会话失效；接受码本身不授予全部能力。

公开脚本位置、当前修复范围和真实请求证据边界见[响应码证据](response-code-evidence.md)。

### 写能力与部队招募读取的授予流程

状态：部队招募只读验证、签到及领奖、论坛互动、图片上传与招募回应的首次明确操作已完成实现；真实账号写入待验证。

- [x] 完成签到及领奖的首次确认、单次实际操作及成功授予路径，并验证界面与本地发布边界。
- [x] 将显式能力尝试接入论坛互动、独立图片上传与招募回应，保留各自成功结构和资格条件。
- [x] 完成部队招募无副作用验证器与独立客户端接入的合成验证，区分功能不可用与整个会话失效。
- [x] 能力撤销清除受保护内容；禁止用自动签到、点赞、关注、发布或删除探测。

本批通过可选 `RisingStonesExplicitCapabilityProvider` 把一次用户明确操作绑定到当前凭证，
[账号服务](../account-data/src/main/java/top/cxmeow/risingstones/feature/account/data/RisingStonesAccountApiService.kt)
在有效成功结果后才完成能力授予；失败或未知网络结果不自动重发。已有会话与公开方法签名保留。
[独立客户端](../app/src/main/java/top/cxmeow/risingstones/app/MainActivity.kt)另接入部队只读验证器，
只授予 `RecruitmentAuthenticated`。`ForumWrite`、`ForumImageUpload`、`RecruitmentWrite`
已由各自服务校验 HTTP、业务状态及操作成功负载后独立授予。换号后的迟到结果不能授予能力；
图片令牌读取与对象上传之间再次检查凭证归属，未知结果不自动重发。招募继续禁止向本人或
身份未知的记录回应。所有真实写入仍待用户选择具体操作后验证，不能把合成测试或用户此前的
消息已读授权作为写入证据。最新设备回归与真实 Android 读取复核见[兼容性矩阵](compatibility-matrix.md)。

## P1：补齐完整领域、跨功能导航与遗漏数据

### 作者身份与跨功能返回

状态：有明确官方作者身份的跨功能入口与独立阅读栈已补齐，通过合成设备回归；本批真实账号读取待验证。

- [x] 从帖子、评论、动态、副本招募、社区招募详情、跑团评论与回复、幻化和消息作者进入社区个人主页。
- [x] 将投影台作者作品页与社区个人主页分清职责，同时保留两类有效入口。
- [x] 用独立阅读层保存来源列表、筛选、滚动位置和选择，支持作者→内容→作者的逐层返回。
- [ ] 社区招募列表的作者身份证据仍待补；官方列表组件尚未证明社区 UUID，不用记录编号代替。
- [ ] 核对官方内容链接的原生路由；未知来源保留正文与明确可用操作，不猜测内容类型。

仓库证据：[个人主页界面](../profile-ui-compose/src/main/java/top/cxmeow/risingstones/feature/profile/ui/compose/RisingStonesProfileScreen.kt)
保留每位用户各分区的数据与滚动位置，`openRoot` 用于外部作者入口，`open` 保留关注/粉丝内部历史。
五个可选作者导航提供器由 [App 阅读层](../app/src/main/java/top/cxmeow/risingstones/app/CommunityReadingHost.kt)
接入，原有公共屏幕签名不变。消息作者元数据与同一次显式消息读取返回，打开主页与返回不额外
读取消息或确认提醒；发出的评论、本人招募回应使用 `Self` 并显示“我”。
作者入口复用 `AccountRead`，会话撤销时清空阅读层与资料；不以登录成功推断全部功能可用。
2026-09-20 六个功能界面一百五十项及 App 阅读栈十项合成设备用例通过，覆盖四个断点、
配置重建、逐层系统返回、弹层恢复、旧请求隔离与后台撤销。当前设备冷启动显示登录入口，
因此不追加本批真实账号作者读取成功的结论；此前有效记录继续保留。
官网 `chunk-0819b39c.a493ceca.js` 有作者 `gotoInfo`，`chunk-87ae4bd0.7d5fc4f1.js` 承载个人主页；
分消息类别及招募详情身份字段证据见[移植记录](web-parity-plan.md)。

### 我的部队与相册

状态：四层阅读、资料编辑、图片上传和照片互动的原生实现已完成；真实写操作和非空照片、
评论仍保留线上验证边界。准确表单、身份关系和能力设计见[部队操作设计](guild-actions-design.md)。

- [x] 部队资料、成员列表、成员动态与无部队状态的原生阅读及验证。
- [x] 相册列表、照片详情、评论与楼中楼阅读，以及消息来源进入照片详情的原生流程及验证。
- [x] 部队资料编辑：头像裁剪、住宅公开、标签、平日及周末活跃时间、简介。
- [x] 照片上传、删除、点赞、发表评论与回复、删除自己的评论；本地合成验证通过。
- [ ] 部队招待入口。
- [ ] 真实账号的上述写操作与权限矩阵验证。

官网路由：`/mguild`、`/mguildedit`、`/guild/main/recruit`、`/mgphoto`、`/mgphotodes`。
`chunk-0819b39c.a493ceca.js` 中有 `guild/getGuildInfo`、`getGuildMember`、`guildMemberDynamic`、
`getGuildPhotos`、`setGuildInfo`、`getGuildLabelList`、`uploadGuildPhoto`、`getGuildPhotoDetail`、
`GuildPhotoCommentDetail`、`likePhoto`、`commentPhoto`、`guildPhotoSubCommentDetail`、
`deleteComment`、`deleteGuildPhoto`。这些名称均位于 `guild/` 路径下。

设计见[部队阅读设计](guild-reading-design.md)。新增独立 `GuildRead` 验证及 `guild-*` 四层模块，
账号摘要提供入口，`MessageTargetKind.GuildPhoto` 已映射到仅需照片 ID 的原生详情。2026-09-20
设备内五个只读请求成功，成员有数据，动态与相册为空；照片详情和评论的非空线上结构仍待验证。
同日保留登录数据升级并冷启动后，原生账号入口、部队资料、两类成员、动态与相册空态及返回
实际通过，`GuildRead` 为真。无部队、非空相册、详情、评论、楼中楼及消息来源使用合成服务
验证；当前账号没有对应非空样本，不能将这些设备用例描述为真实内容线上验证。

### 消息互动与未读提醒

状态：列表、来源读取已有；互动契约与提醒类别待补。

- [x] 有明确身份的消息作者进入社区个人主页，本人发出内容进入本人主页。
- [ ] 评论消息直接回复、删除自己的评论、拉黑与投诉。
- [ ] 核对并保存回复所需的主评论与根评论标识；只保留内容 ID 不足以直接回复。
- [ ] 补齐动态更新、数据开放、新粉丝提醒的展示与显式确认行为。
- [ ] 核对系统消息中的官方活动链接分类与有效打开方式。
- [ ] 保留已实现的六类消息及已回应记录列表；对 `myFbResponseDetail`、`myNeResponseDetail`、
  `myOtherResponseDetail` 另行核对响应详情与联系人返回路径，避免把列表读取当作全部回应流程。

官网证据：`chunk-0819b39c.a493ceca.js` 的 `Commentup`、`commitObj`、`showcommitFun` 使用
`parentid`、`rootparent`；入口脚本包含 `sysMsg/myDataOpen`，未读字段另有 `dynamicTip`、
`dataOpenTip`、`newFensNum`。读取或确认提醒可能改变已读状态，只有用户明确打开或确认后调用。

仓库证据：[消息模型](../message-domain/src/main/java/top/cxmeow/risingstones/feature/message/domain/MessageModels.kt)
中的 `MessageTarget` 只有内容标识与招募频道，`MessageService` 只有读取接口，未读摘要尚未
包含上述三类提醒。

### 数据中心的可读目录与功能详情

状态：所列原生读取与分享流程已接入；真实非空数据与第三方接收验证仍分别记录。

- [x] 将官方鱼王、零式、投影目录接入独立客户端，并在界面使用名称、分类和资源映射。
- [x] 完成鱼类排行、鱼饵排行、套装收集、种族性别等详情的原生任务路径与筛选。
- [x] 将钓鱼、投影、零式整理为有明确含义、三语标签和领域模型的内容。
- [x] 前线七个接口归并六页，补齐独立时期、职业、近七日、最佳、地图职业和成就语义。
- [x] 绝境战使用六个专用数据集，补齐阶段、散点、队友分组及区域服务器、职业通关和搭档次数。
- [x] 按公开页面 `fishSea6` 调用与字段消费实现海钓路线、最高分和次数；真实账号非空结果仍待验证。
- [x] 按官网页面调用与字段消费补齐 `frontlineActiveDetail`；实际为成就记录，与三十五项
  官方成就目录关联。非空结构由公开源码和合成夹具覆盖，真实账号结果仍待验证。
- [x] 补齐蜃景五阶幻境武器、阶段切换、材料进度、收集筛选及半魂晶历史返回。
  复用同批探索读取，完整目录包含一百一十件武器；精确派生与资源边界见[数据中心契约](data-center-contracts.md)。
  非空个人武器记录仍未新增真实账号证据，匿名武器图标响应也单独保留未通过边界。
- [x] 接入钓鱼、零式、投影、前线、绝境和蜃景六类本地图片生成、原生预览与系统分享。
  内容取自已载入快照，不调用业务写接口；二维码只包含公共看板地址。
- [x] 绝境战副本与分区选择、独立重试、摘要刷新同步、更多记录、坐标选点和返回路径已接入；
  非空绝境记录仍保留真实账号验证边界。

仓库证据：[App](../app/src/main/java/top/cxmeow/risingstones/app/MainActivity.kt)已显式注入
`BundledPersonalDataCatalogProvider`。该提供方离线读取官方公开目录快照，含三百三十五条普通
鱼王、五十八个零式副本、六百一十八套有效套装、五十六项时尚配饰和一百二十六项染剂，保留
“无染色”编号零；不依赖旧宿主 schemaVersion 1 文档，不授予个人数据能力。
[界面](../personal-data-ui-compose/src/main/java/top/cxmeow/risingstones/feature/personaldata/ui/compose/RisingStonesPersonalDataScreen.kt)
已通过可选 `PersonalDataDashboardService` 接入三块看板共十四个分区，关联名称、版本、成就、
染剂、配饰、投影类别及零式层级。附加目录含十三条海钓鱼、两类各三十五条成就和三十六个投影
类别。前线通过独立可选 `PersonalDataFrontlineService` 接入七分区读取与六页原生内容，成就
目录缺失可单独重试，地图职业失败不抹掉地图总计；近七日、未知值与日期精度保留明确语义。

请求层已复检授权前后、恢复后和响应后的当前能力，整个读取最多恢复一次；取消和认证失败
继续抛出，普通分区失败可保留旧内容。状态机在切换、取消、撤权后阻止旧结果回填，目录失败
允许重试。钓鱼及前线百分比、零式与投影总览首行选择和指标文案已按实际官网渲染修正。
完整字段、单位、筛选、目录关联及仍未知的非空语义见[数据中心契约](data-center-contracts.md)。

四条详情已经接入默认钓鱼和投影看板，使用可选 `PersonalDataReadingService`。排行保留全量
记录并在本地分类、搜索及继续显示，种族比例和天数使用明确单位。套装以官方目录显示名称、
物品图标和取得状态，保留同套不同记录，完整进度按不同套装编号计算；目录与个人记录分别
重试，缺目录不伪造零进度。单栏、双栏、返回缓存、配置重建和服务实例切换均有合成设备回归。
旧服务不显示新入口，此批没有新增四详情的真实账号线上验证结论。

官网路由：`/statistics/fishing/fish`、`/statistics/fishing/baits`、`/statistics/glamour/sets`、
`/statistics/glamour/races`。`chunk-2f7f5fe4.86e07a6e.js` 包含两个遗漏的数据接口；
`chunk-7690576a.97d2f158.js` 有 `weaponFurtherestStageComputed`、`currentRelicTitleComputed`、
`phantomWeaponStageRef`；`chunk-f27e63c4.14a654ff.js` 有 `UltimateShare`、`saveImage`。

### 社区创作与内容管理

状态：领域或服务待补。

- [ ] 发布、编辑、删除帖子与攻略；富文本、图片及其他官方支持内容的编辑与上传。
- [ ] 发起投票并配置选项与规则，区别于已有的参与投票服务。
- [ ] 发布、删除动态，转发帖子、攻略、动态、招募及幻化等已确认来源。
- [ ] 动态评论、楼中楼回复、点赞与删除自己的评论。
- [ ] 编辑中退出、提交失败、成功回流及本人历史中的管理入口。

官网路由：`/editposts`、`/editwiki`、`/editvote`、`/editdynamic`、`/formdynamic`。
入口脚本与 `chunk-0819b39c.a493ceca.js` 包含 `posts/create`、`updatePosts`、`deletePosts`、
`relay`，以及 `dynamic/create`、`comment`、`like`、`deleteDynamic`、`deleteComment`。
当前[动态服务](../dynamic-domain/src/main/java/top/cxmeow/risingstones/feature/dynamic/domain/DynamicModels.kt)
只有关注流、详情、评论与回复读取；已有评论提交契约不等于帖子创作契约。

### 招募发布与管理生命周期

状态：成员与活动阅读已补齐；发布及管理的领域或服务待补。

- [ ] 五类招募发布、编辑、上下架、擦亮、删除、我的招募与作者招募历史。
- [ ] 部队和其他招募回应，及不同招募类型的结果/联系人展示。
- [x] 跑团活动列表和详情、成员完整分页和详情，入口为跑团详情中的成员/活动阅读层。
- [ ] 跑团活动创建、编辑、上下架与删除。
- [ ] 跑团成员创建、编辑、排序与删除。
- [ ] 跑团评分、收藏、发布和删除评论、我的评论，以及相应的管理状态。

官网路由：`/publish/recruit/*`、`/me/recruit`、`/recruit/roleplay/member/detail`、
`/recruit/roleplay/member/edit`、`/recruit/roleplay/act/detail`、`/recruit/roleplay/act/edit`。
入口脚本包含 `createRecruit*`、`updateRecruit*`、`shelve*`、`polish*`、`destroy*`、
`responseRecruitGuild`、`responseRecruitOther`、`getRecruitRpActListByRpId`、`getActDetail`、
`createRpAct`、`updateRpAct`、`getMemberDetail`、`createRpMember`、`updateRpMember`、
`sortRecruitRpMember`、`starRp`、`recruitRpMyComment`。通配名称用于定位对应分类，不能用来
推导未核对的接口地址。当前[招募服务契约](../recruitment-domain/src/main/java/top/cxmeow/risingstones/feature/recruitment/domain/DutyRecruitmentModels.kt)
没有上述完整生命周期。

## P2：补齐社交、设置、活动与管理范围

### 关系、屏蔽与个人资料

状态：关系和资料读取已有；新增操作与独立页面待补。

- [ ] 推荐关注、未关注好友、批量关注、邀请好友。
- [ ] 通用关注与取消关注；屏蔽/拉黑列表、解除屏蔽/拉黑。
- [ ] 单条动态屏蔽、已屏蔽动态列表与恢复。
- [ ] 资料修改、隐私设置保存、社区徽章读取和编辑。
- [ ] 独立近期成就流、社区等级、个人招募与个人幻化入口。
- [ ] 奖品收货地址与角色资料同步；账号关联流程遵守官方网页登录认证边界。

官网 `/mfriend` 主要承载关注与粉丝，当前原生已有读取，不应整页重复实现。其他路由包括
`/minvite`、`/mpush`、`/mpushadd`、`/mshield`、`/mset`、`/privacy`、`/mset/badge`、`/mlv`、
`/mset/delivery`、`/accountglamourlink`。入口脚本有 `userRelation/getUnFollowFriend`、
`bulkFollow`、`blockList`、`blockUser`、`cancelBlock`，`dynamic/shieldDynamic`、
`getMyShieldDynamicList`、`cancelShield`，`badge/getUserBadge`、`updateUserBadge`，
`userInfo/savePersonalSet`、`getResently`、`groupAndRole/syncUserInfo`。

仓库证据：[个人主页分区](../profile-domain/src/main/java/top/cxmeow/risingstones/feature/profile/domain/ProfileModels.kt)
尚无近期、招募和幻化分区。资料中的 `achieveInfo` 不等于 `getResently` 独立近期流；
`chunk-87ae4bd0.7d5fc4f1.js` 使用 `GetResentlyFun` 单独读取。

### 幻化投稿、造型师与积分活动

状态：领域或服务待补，优惠券领取的已有服务单独见 P0。

- [ ] 幻化投稿、编辑、删除、置顶及本人投稿管理。
- [ ] 造型师资格、报名、活动专栏与活动商品。
- [ ] 积分余额、获取/消耗记录、积分商品与兑换。
- [ ] 兑换记录、周边发货情况、收货地址与不可修改信息的提交确认。
- [ ] 投稿举报、当前处罚、幻化投稿申诉。

官网路由：`/publish/glamour`、`/glamour/stylist`、`/glamourstylistregister`、
`/me/glamour/points/pointshistory`、`/me/glamour/points/goodshistory`、`/glamourappeal`。
入口脚本的 `glamourFashion` 包装器包含 `fashionPeriodInfo`、`checkFashionCondition`、
`getFashionUserInfo`、`signUpFashion`、`getPointsRecord`、`getPointsGoodsList`、
`getFashionItemsList`、`exchangeGoods`、`getExchangedGoodsList`、`lastAddress`；幻化包装器还有
`createGlamour`、`updateGlamour`、`deleteGlamour`、`updateUserTop`、`report`、`currentPunish`、
`appeal`。记录列表、兑换与发货不能合并为一个“积分记录已完成”状态。

### 举报、申诉及其他需要补证的入口

状态：领域或服务待补；历史或高影响入口先补当前可达性与准确契约证据。

- [ ] 通用投诉 `/report`、账号申诉 `/mappeal`，包含目标类型、原因、附件和结果反馈。
- [ ] 核对账号注销 `userRelation/destroyUser` 的有效入口与完整明确确认流程。
- [ ] 核对光之收藏家关联、历史迁移、`/glamourtestregister` 测试资格等入口是否仍有效；
  当前不可达时记录原因和证据，不伪造可用功能，也不默默从清单删除。
- [ ] 核对 `/search`、`/searchm`、`/sresult` 的完整搜索分类。现有原生帖子、攻略与幻化搜索
  不证明已覆盖官网全部分类；审计时尚缺这些页面的主要公开分块脚本，不能猜类型值。
- [ ] 补论坛“周热门”的精确参数与原生筛选。`chunk-f9087f4a.5df94ce8.js` 有该选项、
  `tieFilterHotFun` 和 `hotType_choosetie`；2026-09-20 复核发现选项没有子项或参数绑定，
  离线执行官方选择与确认方法时查询与“最新回复”相同。`postsHotWeek` 仅出现在未暴露的攻略
  热门分支，不能据此猜帖子参数。该项保留待验证，原生不显示没有有效查询证据的入口。
- [ ] 核对照片/视频查看与普通内容分享的原生完成范围。当前论坛图片、视频仍有外部打开
  路径，动态图片已有基础预览；是否需要其他原生操作应按官网实际行为确认，不能一概当成
  只需美化的内容。

## 验证清单与功能完成门槛

以下缺口继续保留，新增实现不得默认继承其他账号、浏览器或其他功能的成功结论：

- [ ] 签到、领奖、论坛点赞/收藏/回复/投票/图片上传的当前 Android 真实操作。
- [ ] 部队招募读取及招募回应、点赞、发布与管理的当前能力和成功行为。
- [ ] 投影台关注、收藏夹管理、点赞/收藏、优惠券及后续写操作的实际结果。
- [ ] 粉丝列表的显式读取及提醒确认、其他消息分类的 Android 操作和新交互。
- [ ] 非空动态评论/楼中楼、非空朝圣交错路等当前缺少线上样本的结构。
- [ ] 各功能认证失败与功能不可用区分、会话撤销、旧请求隔离、取消传播、刷新失败保留。
- [ ] 新交互的 599dp、600dp、839dp、840dp 边界及旋转/窗口变化后状态保持。
- [ ] 公共 API、三语资源、分层/安全门禁与独立 Maven 消费的对应验证。

每个闭环完成时同时填写：原生入口、使用的公共服务、读取/写入或提醒确认性质、错误与返回
路径、源码/夹具/模拟器/真实账号证据。真实验证只记录日期、响应类别和能力布尔结果，不记录
凭证、标识、个人数值或内容。没有线上证据的部分保留“待验证”，但不重置已完成的有效证据。

## 最终 UI/UE 与动效专项

以下工作在功能完成后实施；功能开发所需的基础自适应和可访问性仍需随功能完成。

- [ ] 从全 App 视角统一顶层导航、信息架构、页面层级、常用任务路径与返回行为。
- [ ] 结合官方移动站和成熟社区应用评估列表密度、内容层级、操作位置、搜索与发布体验。
- [ ] 统一组件、排版、间距、加载/空态/错误提示、图表样式及阅读宽度。
- [ ] 统一图片浏览手势与细节交互；已有数据的视觉图表表现可以在此优化，缺目录或数据不能
  延后到这一阶段。
- [ ] 优化路由转场、共享内容过渡、加载切换、列表增删、返回和宽度形态变化的原生动效。
- [ ] 验证系统返回、手势取消、中断与快速连续操作、无障碍动画偏好和滚动性能。
- [ ] 用实际设备或模拟器性能证据验证流畅度；构建成功不代表动画与交互体验达标。
