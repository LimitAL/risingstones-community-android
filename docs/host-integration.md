# 接入指南

本项目支持源码组合构建和 Maven 制品两种接入方式。两种方式都使用
`top.cxmeow.risingstones` 坐标，因此可以在不修改源码导入的情况下切换。

## 使用源码组合构建

建议将本仓库与接入方工程放在同一级目录：

```text
工作目录/
├── risingstones-community-android/
└── your-android-app/
```

在接入方的 `settings.gradle.kts` 中加入：

```kotlin
includeBuild("../risingstones-community-android")
```

只声明需要的层：

```kotlin
dependencies {
    implementation("top.cxmeow.risingstones:forum-domain:0.1.0-SNAPSHOT")
    implementation("top.cxmeow.risingstones:forum-data:0.1.0-SNAPSHOT")
    implementation("top.cxmeow.risingstones:forum-presentation:0.1.0-SNAPSHOT")
}
```

Gradle 会把这些坐标替换为本地源码模块。

被包含的 Android 工程会独立解析 SDK，不继承接入方目录中的 `local.properties`。请为 Gradle
进程设置 `ANDROID_HOME`，或在本仓库内创建不提交的 `local.properties` 并填写 `sdk.dir`。

## 使用 Maven 制品

每个 Android 库会发布 release AAR、POM、Gradle module metadata、源码 JAR 和文档指引
JAR。生成本地 Maven 仓库：

```bash
./gradlew publishPublicLibrariesToLocalRepository
bash scripts/verify-local-publications.sh
```

制品位于 `build/repository`。本地测试时，可以在接入方配置：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("../risingstones-community-android/build/repository") }
        google()
        mavenCentral()
    }
}
```

正式版本使用本仓库的 GitHub Packages Gradle registry：

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/LimitAL/risingstones-community-android")
    credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orNull
        password = providers.environmentVariable("GITHUB_TOKEN").orNull
    }
}
```

GitHub Actions 接入方使用获得本 Package 读取权限的 `GITHUB_TOKEN`；本地开发使用具备
`read:packages` 权限的 GitHub token。依赖版本必须改为已发布的固定版本，禁止使用动态版本或
`SNAPSHOT`。

当前制品使用 Kotlin 2.4 metadata。Kotlin 接入方应使用 Kotlin 2.4 或更新版本，不支持通过
关闭 metadata 兼容性检查规避版本要求。公共 API 以 Kotlin 为主。

`integration-tests/maven-consumer` 会直接消费生成的 Maven 制品，不使用源码替换。它分别
编译协程、OkHttp/Json、WebView 认证、数据服务、ViewModel、领域状态和 Compose 公共签名，
最后再执行一次跨功能聚合编译：

```bash
bash scripts/verify-maven-api-consumption.sh
```

## 模块选择

| 需求 | 建议模块 |
| --- | --- |
| 自行实现界面 | 对应功能的 `*-domain`、`*-data`，以及可选的 `*-presentation` |
| 使用默认 Compose 界面 | 对应的 `*-ui-compose` 及其传递依赖 |
| 官方接口传输 | `network` |
| 与认证来源无关的能力契约 | `core` |
| 使用官方网页 Cookie 登录 | `auth-webview`，以及可选的 `ui-compose` |

`KeystoreRisingStonesCookieStore` 将 AES-GCM 密文保存在接入方应用自己的
`noBackupFilesDir` 中。独立客户端另外在应用清单中禁止所有应用数据进行云备份和设备迁移。

`glamour-ui-compose` 和 `personal-data-ui-compose` 是可选参考实现。独立客户端在基础会话
校验后分别执行只读能力探测，仅在成功后显示对应入口。接入方可以完全省略这些界面模块，只
使用 domain、data 和 presentation 层。

## 创建官方服务

`network` 的 `RisingStonesResponsePolicy.accepts(code)` 对齐官方客户端对 `10000/10002`
的公共接受规则。它不处理 HTTP 错误、不校验端点负载，也不授予功能能力。需要自行实现
数据服务的接入方仍须校验必要数据，并处理已有端点专属结果；不要将 `10002` 作为通用
未登录码而刷新或重放请求。源码依据及验证边界见[响应码证据](response-code-evidence.md)。

接入方可以复用自己的 `OkHttpClient`，官方请求仍由本项目的数据模块完成：

```kotlin
val transport = OkHttpRisingStonesHttpClient(
    client = okHttpClient,
    defaultHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "YourApp/1.0",
    ),
)
val client = RisingStonesPublicApiClient(transport)
val forumService = OfficialForumApiService(
    client = client,
    sessionProvider = sessionProvider,
)
```

匿名论坛读取可以传入 `sessionProvider = null`。鉴权操作需要
`RisingStonesSessionProvider`，其不透明授权器会直接把凭证写入请求头目标。功能模块不会
读取网页登录信息或接入方凭证模型。

会话只应暴露已经用当前凭证来源验证成功的能力。缺少能力是受支持状态，对应操作应隐藏或
禁用，不应乐观调用接口。

## 替换界面与扩展数据

如果接入方需要不同的导航、Compose 组件库、设计系统或页面布局，可以不依赖
`*-ui-compose`。presentation 模块提供状态机，但不规定页面入口和呈现位置。

个人数据展示目录通过 `PersonalDataCatalogProvider` 注入。原 `PersonalDataApiService` 构造器
仍默认使用空实现，保持已有接入行为；独立客户端显式注入 `BundledPersonalDataCatalogProvider`，
从库中读取经核对的官方鱼王、零式、套装、时尚配饰与染剂目录，不需要 Android Context 或网络。
目录读取成功不证明个人数据可用，也不授予 `PersonalData`。

```kotlin
val service = PersonalDataApiService(
    risingStonesClient = officialClient,
    sessionProvider = sessionProvider,
    catalogProvider = BundledPersonalDataCatalogProvider(),
)
```

旧 `PersonalDataCatalogJsonDecoder` 仍只解析接入方提供的 schemaVersion 1 文档，不是官方传输格式。
离线目录来源、关联字段与维护边界见[数据中心契约](data-center-contracts.md)。目录快照不会自动
跟随官网更新；维护时重新核对公开页面，使用仓库生成脚本更新并通过目录测试。
任何额外数据源的实现和接口配置都应保留在接入方自己的工程中。

`PersonalDataViewModel.clearProtectedContent()` 用于会话撤销时清理所有请求、选择、目录与个人
数据缓存。服务的 `hasCommunityIdentity` 是普通布尔契约，宿主必须将会话变化传递给界面或显式
调用清理，不能只在首次构造时检查。参考界面和独立客户端已接入该清理；ViewModel 的所有入口
及异步返回仍会复检能力。原 String 错误字段仅返回 `load_failed` 或 `authentication_required`，
接入界面应映射为本地化提示，不应显示这些内部标记。

### 数据中心四类阅读详情

官方 `PersonalDataApiService` 实现可选 `PersonalDataReadingService`，提供鱼类与鱼饵全量排行、
种族性别比例和天数、独立套装记录。旧 `PersonalDataService` 不需要新增实现方法；默认主界面
只在服务支持此扩展且当前个人数据能力有效时显示四个入口。

自定义界面可使用 `PersonalDataReadingViewModel`，通过 `open(PersonalDataReadingPage.Sets)`
进入详情，调用 `close()` 返回。`state` 提供按页缓存、查询、分类、排序、继续显示范围及套装选择；
`clearProtectedContent()` 必须随能力撤销调用。若独立使用可选界面，可传入现有状态机：

```kotlin
RisingStonesPersonalDataReadingScreen(
    viewModel = readingModel,
    onNavigateBack = readingModel::close,
    itemIconUrl = service::itemIconUrl,
)
```

`refresh()` 仅重读当前个人记录，`retryCatalogs()` 独立重试套装目录。分类、搜索、排序和
`showMore()` 均在已读取的全量数据中执行，不添加官网没有的分页参数。排行默认“全部”，使新分类
和缺少分类的旧记录仍可见；官网旧详情默认普通钓场。未知分类保留服务返回文字，不猜新增固定分类。

套装“有记录”包含部分套装，多条记录分别保留，不能合并部分记录推导完整套装。完整套装进度按
目录套装编号去重；目录缺失、空目录项或无效物品编号保留未知状态。条目键用于内存选择，不应写入
日志或分析系统。种族比例保留小数比例，界面乘百；缺少比例或天数显示未知，不补零。

官方实现的 `itemIconUrl()` 仅生成 `ff14-eo.web.sdo.com` 的 HTTPS 物品图标地址，来自已核对的
官网公开资源公式。图片匿名加载，不使用会话授权器；未提供图片的宿主可保留默认空回调。独立
UI 制品已经加入公共 API 消费检查，宿主不必同时依赖 data 模块来使用领域模型和页面。

默认主界面使用按服务实例归属的子 ViewModelStore。同一服务与同一宿主 Store 在配置重建时保留
已加载结果；更换服务实例会同步清理旧主看板、阅读详情和探索模型，再构造新模型。宿主应在同一
会话内复用服务实例，且仍需通知同一服务内部的能力撤销；仅更改普通布尔值不会主动触发 Compose。


## 论坛分类、筛选与游标分页

`OfficialForumApiService` 同时实现可选的 `OfficialForumBrowsingService`。新接入方通过
`fetchCategories(kind)` 获取包含子分类的目录，通过 `fetchBrowsePage` 选择最新发布、精华
或置顶，并将返回的 `pageTime` 原样传入下一页。刷新或改变分类、搜索条件时清空游标。
搜索使用 `searchBrowsePage`，也遵循同一游标规则。

既有 `OfficialForumService`、`OfficialForumListQuery`、`OfficialForumPage` 和分类模型的
签名保持不变；原有实现仍可接入。默认 ViewModel 自动识别扩展契约，并从独立的
`browsingState` 暴露分类与筛选状态。没有实现扩展的服务继续使用原列表接口。

## 论坛评论与投票

`OfficialForumApiService` 还实现可选的 `OfficialForumInteractionService`，一次详情读取返回
正文和 `voteResultsAvailable`。此集合表示服务端已展示投票结果，与当前用户是否参与投票
分别处理；结果中存在票数字段（即使为零）时，不再显示投票入口。旧服务接口仍可接入，
旧的详情模型和构造签名保持不变。

`OfficialForumDetailViewModel.interactionState` 管理评论草稿、回复目标、单张图片、投票选择
及错误。提交只能由明确用户操作触发，普通互动要求 `ForumWrite`，选图上传另外要求
`OfficialForumImageUploadService.canUploadCommentImages` 对应的 `ForumImageUpload`。
获取上传凭证不等于验证上传或评论能力，不能据此自动授予写权限。

参考界面使用系统图片选择器，在内存中检查格式和 21 MiB 大小上限，缩小尺寸并转换为
JPEG 或 PNG；动态图只使用首帧。支持纯图片评论。上传成功后评论失败会复用当前图片地址，
避免重试再次上传。草稿和待提交操作保留在同一 ViewModel 中，不写入持久存储；旋转不会
重新发送。`resumeCommentComposer()` 保留既有回复目标；目标已经删除或移出筛选时，仍可
恢复并丢弃草稿，不能继续发送。图片导入由可选 UI 模块的独立 ViewModel 保留，系统选图
回调使用稳定注册标识，因此读图中旋转或选图器打开时切换宽度不会静默丢图。
会话失效、切换凭证或能力撤销时，调用 `clearProtectedContent()` 并清除所属
ViewModelStore。独立应用会同步重建仍显示的论坛页面，防止复用已经清理的模型。

评论表情与提及通过可选的 `OfficialForumCommentAuthoringService` 接入。官方实现使用
`AccountRead` 读取当前用户关注的人；只有显式打开选择器时才请求一次，姓名筛选在本地完成。
候选来自固定的 `followList?page=1&limit=5000`，不表示提供全站用户搜索。旧服务仍能编辑和
提交普通评论、使用 1–46 号官方表情；未实现扩展时不展示提及入口。

`commentEditorState` 保存文本、UTF-16 光标与选区、有效提及范围、候选和选择器状态。
自定义编辑器应在每次实际替换文本时向 `updateCommentEditor` 传入旧文本的半开区间
`OfficialForumCommentTextChange(start, end)`；粘贴相同文字也属于替换，必须提供范围。
只有光标变化时可以省略范围。文本已经变化却没有替换范围时，状态机会清除全部提及身份，
防止把同名对象误认为原对象；手工输入的 `@姓名` 不会产生通知身份。

`submitDraftComment()` 只为仍在草稿中的有效选择生成官方 `at-text` 标记与 `atInfo` 索引表单。
既有 `submitComment(target, text, image, onSuccess)` 和五参数 `OfficialForumCommentDraft`
保持兼容，前者始终按普通文本处理，不借用当前草稿中的身份。宿主若直接调用
`submitCommentWithMentions`，必须自行确保 HTML 与元数据对应；发送仍要求 `ForumWrite`。
未验证的写能力不会因候选读取成功而授予。关闭编辑器保留内存草稿、光标和回复目标，
丢弃、提交成功或会话撤销会清空草稿及候选；取消中的候选请求即使迟到也不能回填。

投票文字或图片类型由 `vote_type` 表达，单选或多选由选项的 `option_type` 表达；公共模型的
`allowsMultipleSelection` 和 `deadline()` 提供对应判断。状态机检查选择范围、截止时间、
已参与和结果可见状态，并在单次成功后阻止重复投票。

## 动态读取

按需引入 `dynamic-domain`、`dynamic-data`、`dynamic-presentation`，以及可选的
`dynamic-ui-compose`。四个模块使用同名 artifactId，支持独立源码与 Maven 接入。

将 `RisingStonesDynamicSessionValidator` 包装在既有会话校验器外层；它只通过官方关注流
读取探测 `DynamicRead`，不会执行点赞或发布。`DynamicApiService` 每次请求先检查能力，
遇到鉴权失败只尝试一次会话刷新，错误不携带原始响应内容。

默认界面接收 `DynamicViewModel`，通过 `onOpenReference` 将来源交给接入方导航，
通过 `canOpenReference` 隐藏尚未验证能力的来源入口。
会话断开、切换账号或撤销能力时调用 `clearProtectedContent()`；旋转时保留同一模型和服务。
独立应用已完成上述组装。帖子、攻略、招募和幻化来源使用原生详情；转发动态通过
`openLinkedDynamic` 保留返回路径。独立详情可使用 `RisingStonesForumPostScreen`、
`RisingStonesRecruitmentDetailScreen` 和 `RisingStonesGlamourDetailScreen` 嵌入接入方导航。
招募入口明确接收板块，不能根据条目编号猜测类型。部队招募和幻化仍受各自能力约束。

`RisingStonesWebCookieSessionProvider.refreshAuthorizer()` 重新验证当前凭证并替换能力集，
不重复写入未变更的凭证；明确失效时先撤销内存授权，再清除目标 Cookie 与密文。临时网络失败
保留现有会话以便重试，协程取消继续传播。独立应用的会话恢复由 ViewModel 管理，每次
运行时只恢复一次，避免旋转重复校验并清空界面。


## 消息读取

`message-*` 四个模块使用同名 artifactId，可分别接入领域、数据、状态机和参考界面。
将 `RisingStonesMessageSessionValidator` 包装到会话校验链中；它只调用 `sysMsg/getTip`，
成功解析未读摘要后授予 `MessageRead`。不能改用列表接口做探测或后台预取。

`MessageService.fetchUnreadSummary()` 无副作用，`readMessages(query)` 可能确认已读。
仅在用户选择类别、切换频道、刷新或加载更多时调用后者。默认 `MessageViewModel.ensureLoaded()`
只读取摘要，成功读取列表后重新查询未读数，不自行归零。评论列表每页可能稀疏，
不要依据不足 10 条直接判定没有下一页。`10401` 是类别错误，不等于整个会话过期。

`RisingStonesMessageScreen` 通过 `onOpenTarget` 和 `canOpenTarget` 接入原生详情导航。
系统消息的已审查链接通过可选 `onOpenOfficialLink` 交给调用方。未知来源保留正文，
不会构造猜测的跳转地址。退出账号或撤销能力后调用 `clearProtectedContent()`，
旋转时复用原模型；紧凑布局类别到列表，中等与展开布局并列展示。


## Cookie 与登录 User-Agent 配对

官方会将 User-Agent 与 Cookie 一起校验。捕获、加密存储、恢复和重新验证都必须保留同一对值，
不能把登录时的 WebView UA 替换成应用版本标识、当前浏览器 UA 或额外拼接的标识。
默认 Cookie 授权器会同时写入这两个请求头；HTTP 传输先设置默认请求头，再以不区分大小写的
规则覆盖会话请求头，确保显式登录 UA 最终生效。接入方的 OkHttp 拦截器也应保留这一规则。
登录候选去重包含 Cookie、对应 UA 和认证返回代数，同一个 Cookie 的 UA 发生变化时需要重新校验。


## 个人主页接入

`profile-domain`、`profile-data`、`profile-presentation`、`profile-ui-compose` 使用同名
artifactId，分别提供领域契约、官方接口、状态机和可选参考界面。复用已由 `getUserInfo`
验证的 `AccountRead`，不增加重复的能力探测。

使用 `ProfileApiService` 与 `ProfileViewModel`，参考界面为 `RisingStonesProfileScreen`。
默认读取 `ProfileOwner.Self`。从帖子等外部来源创建新的主页阅读实例时，调用
`openRoot(ProfileOwner.User(uuid))`；它设置阅读根并清除原有历史，不会把尚未打开的默认
本人主页加入返回路径。同一根的重复 `openRoot()` 不重置阅读位置或再次加载。
主页内部进入其他用户时调用 `open(ProfileOwner.User(uuid))`，由 `navigateBack()` 恢复
之前的用户、分区与分页；外部阅读根没有内部历史时，由宿主返回来源页面。
分区读取通常由用户选择、刷新或翻页触发；返回未完成的普通分区可重新读取，粉丝分区除外。
`ProfileSection.Followers` 可能确认新粉丝提醒，不应后台预取。返回已读列表不会再次请求。

发布和收藏列表通过 `ProfileContentTarget` 回调交给接入方打开原生帖子、攻略或动态详情。
使用 `canOpenContent` 根据会话能力过滤入口。收藏分区只允许 `Self`，不能将其他人的 uuid
用于本人收藏接口。其他人的非公开游戏字段在数据层已删除，`Private` 与 `Unavailable`
分别表示未公开和无可用数据；不要从公开标记推断不存在的数据。

退出、切换账号或失去账号读取能力时调用 `clearProtectedContent()`，或清除该会话拥有的
ViewModelStore。旋转时复用状态机可保留用户、分区与分页；不要把个人响应写入日志或保存状态。


## 跨功能作者导航与返回

五个可选 Compose Provider 位于各自 `*-ui-compose` 包内。在需要作者入口的界面外包裹
对应 Provider，回调只把目标交给宿主导航；不需要更改原有 Screen 调用签名，也不要求
来源 domain、data 或 presentation 依赖 `profile-*`。

| Provider | `onOpenAuthor` 类型 | 入口含义 |
| --- | --- | --- |
| `RisingStonesForumAuthorNavigation` | `((String) -> Unit)?` | 帖子、攻略及评论的社区作者 UUID |
| `RisingStonesDynamicAuthorNavigation` | `((String) -> Unit)?` | 动态及评论的社区作者 UUID |
| `RisingStonesGlamourAuthorNavigation` | `((String) -> Unit)?` | 社区主页独立按钮，保留已有作者作品入口 |
| `RisingStonesRecruitmentAuthorNavigation` | `((String) -> Unit)?` | 有明确身份的招募作者、跑团评论及回复作者 |
| `RisingStonesMessageAuthorNavigation` | `((MessageAuthorTarget) -> Unit)?` | 消息头部作者，独立于消息内容跳转目标 |

四个字符串回调均接收已映射的社区 `uuid`，宿主将其映射为 `ProfileOwner.User(uuid)`。
消息目标必须分别处理：

```kotlin
val owner = when (target) {
    MessageAuthorTarget.Self -> ProfileOwner.Self
    is MessageAuthorTarget.Community -> ProfileOwner.User(target.uuid)
}
```

`Self` 来自官网发出评论、发出招募回应的当前用户头部语义，不需要先读取当前用户 UUID，
不能改用消息的回复对象或内容作者。参考界面对 `Self` 使用三语资源 `message_author_self`
显示“我”，不沿用未经证实的行作者名。其他显示名只用于展示，同名作者不合并；空身份
不补猜测值。
宿主在已验证 `AccountRead` 时提供回调，失去能力时传 `null` 并清除阅读作用域。未提供
Provider、回调为空或身份缺失时，作者保持静态展示；幻化的独立社区主页按钮不显示。

`MessageApiService` 实现可选的 `MessageAuthorService`：
`readMessagesWithAuthors(query)` 返回 `MessageAuthorPage(page, authorsByKey)`。
它与 `readMessages()` 具有相同的显式阅读及可能确认提醒语义；一次操作只选择一个方法，
不能为了补元数据先后调用两者。`MessageViewModel` 自动识别扩展，将元数据放入
`authorState.authorsByKey`，以 `CommunityMessage.key` 查找。旧服务继续读取原有页面，
不伪造作者。作者点击、消息选择、返回和组合重建不应再次调用消息读取；成功读取列表后的
现有未读摘要刷新仍然保留。

`DutyRecruitmentApiService` 实现可选的 `RecruitmentAuthorService`，提供：

| 方法 | 富结果 |
| --- | --- |
| `fetchCommunityRecruitmentsWithAuthors(query)` | `CommunityRecruitmentAuthorPage`：原页面与按条目 ID 索引的 `authorUuids` |
| `fetchCommunityDetailWithAuthor(id, kind)` | `CommunityRecruitmentAuthorDetail`：原回应资格 `interaction` 与可空 `authorUuid` |
| `fetchRolePlayReviewsWithAuthors(query)` | `RolePlayReviewAuthorPage`：原页面、`authorUuids` 及按根评论索引的 `previewAuthorUuids` |
| `fetchRolePlaySubcommentsWithAuthors(rootParentId, page, limit)` | `RolePlaySubcommentAuthorPage`：原页面与 `authorUuids` |

旧公共数据类构造和服务签名保持不变。官方社区招募列表尚无可证明的作者身份消费，
其富结果的 `authorUuids` 返回空；列表作者保持静态，不能为每行追加详情请求。副本使用
既有 `DutyRecruitmentSummary.uuid`，社区招募详情与 RP 评论使用明确返回的作者字段。
跑团成员是角色记录，不是社区作者。

`DutyRecruitmentViewModel.authorState` 独立暴露 `communityAuthors`、
`selectedAuthorUuid`、`reviewAuthors` 与 `subcommentAuthors`；后者外层键为根评论 ID，
内层键为回复 ID。评论预览复用原有楼中楼读取取得作者，已经加载的完整回复页及其作者
优先于后来的预览。两类状态机在成功首屏读取时替换对应索引，刷新失败保留旧内容和作者，
追加时保持已展示的第一条记录及其身份一致；频道、板块或详情切换清除对应作用域，并拒绝
迟到结果。撤销时调用 `clearProtectedContent()` 或清除所属 ViewModelStore。

默认客户端使用 App 内部的 `CommunityReadingHost` 组装这些 Provider 与阅读栈；这些 App
类型不是公共库契约。来源页面和覆盖层内已有页面保留组合及各自 ViewModelStore，只有栈顶
参与交互，返回恢复来源状态。宿主自建导航时也应为每个阅读入口保留独立的状态机与可保存
界面状态，旋转和窗口变化复用原实例，不重新触发消息或粉丝读取。阅读目的地与个人响应
只放在内存中，不写入持久保存状态；进程终止后不保证恢复个人阅读栈。

会话变化时同步清理作者索引、资料内部历史和宿主阅读栈，取消待完成请求并释放对应
ViewModelStore。默认客户端按当前能力与受保护内容修订号执行该清理；打开内容时仍检查
动态、幻化和部队招募各自的读取门禁。社区主页入口不会授予关注、点赞、回复等写能力。

## 手动只读接口结构检查

`app` 的 `OfficialReadOnlySchemaTest` 默认跳过线上请求。仅在用户已在测试设备完成官方登录、
且需要核对列出的读取接口时，使用 `officialReadOnlyProbe=true` 启用。
该检查使用设备已有凭证的不透明授权器，保留原始登录 UA，不导出 Cookie、不修改会话存储。
请求清单固定在测试源码中，不接受任意接口地址；不包含消息、粉丝或提醒确认接口。
输出只含响应码、字段名、类型及数组是否为空，所有实际字符串和数值均被替换。

```bash
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class top.cxmeow.risingstones.app.OfficialReadOnlySchemaTest \
  -e officialReadOnlyProbe true \
  top.cxmeow.risingstones.test/androidx.test.runner.AndroidJUnitRunner
```

输出的传输成功或结构信息不代表全部业务路径完成验证。不要在 CI 自动开启该参数。
默认固定组为数据中心；增加 `-e officialReadOnlyProbeGroup glamour` 可改用七个已审查的
投影台读取请求。未知组直接失败，不接受动态地址或任意请求参数。
`glamour-search` 固定组另含装备、眼镜、配饰候选与本人收藏夹四个只读请求。

## 蜃景与深层迷宫看板

`PersonalDataApiService` 现在实现可选的 `PersonalDataExplorationService`，仍可按原来的
`PersonalDataService` 使用；原有服务契约、五项看板枚举和参考界面函数签名均不变。
`RisingStonesPersonalDataScreen` 检测到此扩展时，自动加入蜃景与朝圣交错路入口。

自定义界面可调用 `fetchExplorationOverview(ExplorationBoard.OccultCrescent)`，或通过
`ExplorationViewModel` 和 `RisingStonesExplorationScreen` 复用状态与参考界面。历史使用
`fetchExplorationHistory`，仅接受当前看板支持的历史类型，不提供任意接口访问入口。

仍要求当前会话具有 `PersonalData`，并由授权器提供登录时配对的 User-Agent。宿主应将这些
ViewModel 放在会话作用域中；主动撤销能力时调用 `clearProtectedContent()` 或清除对应
ViewModelStore。参考客户端的会话作用域会在会话失效时整体清除。

紧凑宽度以横向分区选择配合单栏阅读，中等和展开宽度使用侧栏与内容区。已选分区及按需历史
缓存存于 ViewModel，窗口变化不触发重新加载；旧服务实现仍只显示原有五项看板。

## 投影台浏览扩展与作者入口

`GlamourApiService` 实现可选的 `GlamourBrowsingService`，原有 `GlamourService`、列表
请求和枚举签名保持不变。自定义界面使用 `fetchBrowsePage(GlamourBrowseRequest(...))`，
在后续页传回 `nextPageTime`；第一页或查询改变时清除游标。旧服务实现仍可使用默认界面，
界面只在检测到扩展时展示关注与新增筛选入口。

`GlamourViewModel.browsingState` 提供关注源、标签、部族及目录状态；`selectFollowing()`
进入关注源，`applyBrowsingFilter()` 应用社区筛选，`loadBrowsingCatalog()` 重试目录。
标签每分类最多一个，部族必须属于当前种族；目录读取成功前不会接受未知标签或部族。
刷新失败保留旧列表和游标，能力撤销时宿主须清除对应会话的 ViewModelStore。

`RisingStonesGlamourAuthorScreen` 接受 `GlamourAuthor` 并显示作品、统计及公开收藏夹。
默认列表和独立详情均提供作者入口。作者作品页与社区个人主页是不同功能，后者继续由
`profile-*` 提供；不能将作品页作为完整个人主页。自定义宿主应在旋转时复用服务实例与
会话作用域，让列表、筛选与选择持续保留。关注、点赞等写入只由明确用户操作触发。

## 物品搜索、收藏夹与优惠券

`GlamourCandidateSearchViewModel` 提供装备、眼镜和配饰候选，调用 `search(kind, query)`
才开始读取；选中时以 `selection(candidate)` 取得精确的编号查询条件。`resetCancel()`
关闭并取消候选读取，`clearProtectedContent()` 用于会话失效。眼镜保留款式分组，装备可分页。

`GlamourApiService` 同时实现 `GlamourCollectionService`，旧服务无需新增实现。
该可选扩展提供 `updateFavoriteFolder` 和 `favoriteInFolder`。默认界面根据扩展开放编辑和
指定收藏夹选择，旧服务仍可使用既有默认收藏行为。文件夹名称限制由
`GlamourFolderNameMaximumLength` 提供；只有本人的非默认文件夹可以删除。

`GlamourViewModel.interactionState` 保存选择、提交和独立刷新错误。打开收藏选择器只读自己
的收藏夹，`submitFavorite` 才写入。创建、修改和删除确认成功后 `folderMutationRevision`
递增，界面应观察此修订号关闭当前表单，避免配置重建后引用旧回调状态。写成功后刷新失败
使用 `folderRefreshError`，`retryFolderRefresh()` 只重读，不能自动重放成功的写入。

`claimSelectedCoupon()` 只对已加载且可领取的作品执行；未关注时官方领取接口会同时关注作者，
界面必须明确表达这个效果，不应先额外关注。实际账号的写入尚未验证，当前测试使用合成服务。

独立详情的内部作用域可跨 Activity 配置重建保留，在正常退出时清空。宿主仍需把它放入
会话作用域；账号断开、切换或能力撤销时清除父 ViewModelStore，确保详情和作者内容一并释放。

## 招募浏览扩展与回应

`DutyRecruitmentApiService` 实现可选的 `RecruitmentBrowsingService` 和
`RecruitmentResponseEligibilityService`，保留原有服务方法与构造签名。副本完整筛选使用
`DutyRecruitmentBrowseQuery`，通过 `fetchDutyFilterCatalog()` 取得目录、标签和大区；
跑团评论排序使用 `RolePlayRecruitmentReviewQuery`。旧评论方法保留原排序行为，新查询默认最新。
社区查询的 `groupId` 接受服务器编号的逗号串；跑团与其他招募的全服参数由数据层分别归一化。

`DutyRecruitmentViewModel.browsingState` 保存完整查询及评论排序，`interactionState`
保存回应草稿、提交结果和楼中楼分页。界面通过 `applyDutyBrowseFilter`、`setReviewOrder`、
`openReviewReplies` 和 `loadMoreSubcomments` 驱动操作；刷新错误保留旧内容，分页错误只重试失败页。
筛选面板取消不请求列表，提交后按板块保存查询，窗口变化保留当前详情和草稿。

`canPerformAuthenticatedWrites` 只代表当前 `RecruitmentWrite`，不能用
`hasCommunityIdentity` 替代。回应还要求已加载副本或新人详情、未回应、且作者资格明确为非本人。
资格扩展在内存比较官方角色身份，只向宿主返回可空布尔，不暴露该比较所用账号字段。
旧服务或未知作者均不开放回应入口。

先 `openResponseComposer()` 编辑草稿，再 `submitResponseDraft()` 提交；表单最多 30 字符。
成功后观察 `responseSuccessRevision`，不要依赖旧 Activity 回调。缺少招募者联系方式仍是成功，
不能用输入草稿补位或再次提交。`hasRefreshedResponseContact` 标记成功后的详情 GET 已返回，
可使用新详情联系人；提交前启动的迟到 GET 不产生该标记。
资格暂时不可用时，可通过 `resumeResponseComposer()` 查看或丢弃已有草稿，提交仍检查资格。

默认主入口和独立 `RisingStonesRecruitmentDetailScreen` 均支持配置重建中的一次提交；退出详情
会释放其内部状态。宿主仍应复用服务实例并使用会话 ViewModelStore，在断开、切换账号或撤销
能力时清除作用域或调用 `clearProtectedContent()`，避免草稿、联系人和部队内容跨会话保留。
本地单元和设备测试使用合成写入，实际 `RecruitmentWrite` 仍需独立的成功证据。

## 跑团成员与活动阅读

`DutyRecruitmentApiService` 另实现可选的 `RolePlayDirectoryService`，提供完整成员分页、
成员详情、已上架活动列表和活动详情；旧服务无需增加实现，原有首批成员读取仍然可用。
成员请求使用 `RolePlayMemberQuery`，从第一页保存 `RolePlayMemberPage.pageTime`，后续页原样
带回；`hasMore` 根据原始响应行数计算，调用方不应根据已映射条数覆盖它。活动列表保持官方
次序，没有分页参数。

独立 `RolePlayDirectoryViewModel` 构造与 `setParent()` 不读取数据，显式
`open(parentId, RolePlayDirectorySection.Members)` 或 `Activities` 才加载目录。
`close()` 保留当前父项的两类列表与选择，`setParent()` 切换父项时取消读取并清空缓存。
`selectMember()`、`selectActivity()` 打开独立详情；刷新失败保留已确认内容，局部重试不会
重读整个父跑团。读取鉴权失败清空内容但保留最小导航上下文及可见错误，宿主显式调用
`clearProtectedContent()` 或清除会话 ViewModelStore 则完全关闭。

可选参考界面为 `RisingStonesRolePlayDirectoryScreen`，接收上述 ViewModel 和返回回调。
默认招募主入口及独立来源详情均已接入全屏子阅读层，父跑团保持组合以保留滚动位置；
成员与活动在四个宽度边界间保留目录滚动、各自选择和详情。完整目录只在用户打开时读取，
不把成员摘要伪装成完整列表。
阅读层沿用宿主的 `LocalDensity` 与 `LocalUriHandler`；文字链接及图片的链接按钮只在显式
点击后打开 HTTPS 页面，打开失败显示可见反馈，不在渲染或加载时跳转。

成员介绍为普通文本；活动通过 `RolePlayActivityBodyBlock` 保留图文顺序、格式和链接，
原始 HTML 另供自定义渲染器使用。`hasUnsupportedContent` 表示基本原生渲染尚无法完整
表达的结构，不能静默忽略。日期保留官方文本，不假定来源时区。

## 钓鱼、投影与零式分区看板

官方服务同时实现可选 `PersonalDataDashboardService`，每个 `PersonalDataDashboardSectionKind`
对应已核对的独立 GET。旧 `PersonalDataService` 与原构造器不变；默认主界面检测扩展后切换
这三块看板，不重复请求旧 `fetchBoardContent()`。旧服务仍走原有界面与读取路径。

`PersonalDataDashboardViewModel.open(board)` 按需加载该看板各分区与两组目录；`selectSection()`、
查询、版本、时期、类别、取得状态和继续显示均在本地处理。`refresh()` 刷新当前看板的个人记录，
`retrySection(kind)` 只重试该分区，`retryCatalogs()` 独立重试原目录与附加目录，普通失败保留
已有内容。关闭取消在途请求，成功缓存和每块看板的分区选择保留；能力撤销时必须调用
`clearProtectedContent()`，服务实例更换时释放旧状态机。

独立使用 `RisingStonesPersonalDataDashboardPane()` 时，宿主提供顶栏、返回与四详情跳转，传入
`itemIconUrl`、`achievementIconUrl`、`raidImageUrl` 回调即可使用官方图片公式，也可全部省略。
该页面不依赖 data 模块。主界面已经提供三档导航、认证失败隔离、筛选与滚动保存及四详情返回。

`BundledPersonalDataCatalogProvider` 额外实现 `PersonalDataSupplementaryCatalogProvider`，包含
海钓特殊鱼、钓鱼及前线成就和投影类别。该扩展不改变原目录构造器，不联网或授予鉴权能力。
来源、目录筛选限制和生成方式见[附加目录说明](personal-data-supplementary-catalogs.md)。

原生鱼王默认全部版本，排行按最大值在前显示十项并可继续展开；官网默认版本 2、不同分区初始
数量不同。零式保持目录系列顺序，成就型阶段仅显示成就信息及最终记录时间，不能据缺失字段
推导通关职业或耗时。目录不可用时保留原始领域记录与未知提示，不显示虚假的未取得或零进度。

## 前线战场分区看板

官方 `PersonalDataApiService` 同时实现可选 `PersonalDataFrontlineService`。七个分区整批读取，
总览和职业分别选择累计、5.1 以来或近三十日；地图切换清除单职业选择，全部职业使用地图总计。
`frontlineActiveDetail` 是成就记录，不能当作普通战斗活动历史。所有筛选和继续显示均为本地操作，
读取不会确认数据开放提醒，也不授予新的会话能力。

`PersonalDataFrontlineViewModel` 保留七个独立分区的加载、缓存、失败与重试状态。
`open()` 捕获本地日期并加载缺少的分区；`close()` 取消请求且保留选择；`refresh()` 重新读取业务
分区；`retryCatalogs()` 单独刷新目录。任一认证失败清空全部受保护内容，服务更换或能力撤销
也会清空主页面及其他阅读层。`hasAchievementCatalog` 与地图目录是否存在无关：目录缺失时可以
继续查看已有成就记录，但不能由空记录推出全部成就的取得状态。

周数据使用传入 `Clock` 的本地日期，覆盖前七天至昨天，不包含今天。整日无记录补零；已存在的
记录缺字段保留未知。日期模型区分日期、本地时间和带偏移的瞬间，不猜测数字日期或固定时区。最佳战绩和成就日期
也遵守此规则：无时区日期按原日历值显示，只有带偏移的瞬间转换为显示时区；成就排序显式
使用模型的时区，日期型记录仅在排序时按当地日首比较，不虚构显示时间。
七日胜率按胜场总和除以场次总和，KDA 按击倒和助攻总和除以死亡总和（零死亡用一作分母）。
未知计数、溢出及矛盾胜负不会生成看似有效的比率。

独立使用 `RisingStonesPersonalDataFrontlinePane()` 时，宿主提供顶栏、返回和模型生命周期，
并传入服务的职业、军团及成就图片回调。默认完整个人数据页会自动使用该扩展，并避免再次读取
旧通用字段看板。旧服务及公共构造器继续兼容。参考界面提供原生雷达、七日图表、职业分布、最佳
记录、地图职业统计及成就搜索，沿用紧凑单栏和中等/展开双栏导航。图片仅使用已核对的官方资源；
队伍编号不猜测军团归属。范围与来源见[前线契约](data-center-contracts.md)。


## 绝境战原生阅读

官方服务新增可选 `PersonalDataUltimateService`，原有绝境类型与方法继续兼容。
`fetchUltimateRecords()` 读取首通摘要，`fetchUltimateSection(territoryType, section)` 读取指定
副本的队伍、常用职业、搭档、阶段或死亡坐标。默认个人数据主界面识别扩展后使用新原生阅读层，
不会重复调用旧绝境看板。接口只读，复用当前 `PersonalData` 能力，不确认数据开放提醒。

`PersonalDataUltimateViewModel.open()` 只加载总览；显式选择接口返回的副本后加载其缺少的分区。
733 不读取阶段。再次选择或本地展开不重复请求，`retrySection()` 只重试相应分区，普通失败保留
旧内容；`refresh()` 同时更新总览与当前副本，各分区独立显示结果。详情的首通摘要始终派生自当前
总览，避免刷新后仍显示旧对象。关闭取消在途请求，保留当前选择及缓存；撤权、认证失败或服务
实例替换清除同作用域内容。宿主独立使用时仍应在能力撤销时调用 `clearProtectedContent()`。

`RisingStonesPersonalDataUltimatePane()` 由宿主负责顶栏及模型生命周期，可传入服务的封面、
职业和成就图片回调，不要求界面依赖 data。主页面提供紧凑单栏与中等/展开双栏，副本和分区
分别保存滚动，系统返回先退副本再退看板，配置重建与宽度变化保留选择。

日期区分日历日期、本地时间与带偏移瞬间；仅带偏移的值转换为显示时区。常用职业次数是通关
次数，搭档次数是共同进入次数，首通历程耗时是整数秒拆分的时分秒。角色排序只按防护、治疗、
进攻分组，同组保留响应顺序，未知职业后置。死亡散点图使用官网坐标变换与相同轴比例，无虚构
底图；下方同时提供可访问的图中/原始坐标列表，缺失或无效点有独立提示，继续显示不裁剪源记录。

## 蜃景幻境武器

`PersonalDataApiService` 增加可选 `PersonalDataPhantomWeaponService`，不改变既有构造器、
探索服务或分区枚举。`fetchPhantomWeaponExploration()` 返回同批的探索总览、物品获取和魔法球
类型化输入；`ExplorationViewModel` 检测到扩展时只调用这一方法，不再重复调用旧总览。
五阶武器目录通过 `BundledPhantomWeaponCatalogProvider` 离线读取，包含一百一十件武器、
六种半魂晶和三种消幻晶；目录可用不代表个人能力已验证。

`phantomWeapons` 是独立的状态流，`openPhantomWeapons()` 打开参考页；阶段、名称筛选、
仅看已获得及展开均在本地处理。`openPhantomWeaponHistory()` 沿用既有半魂晶历史，
`returnToPhantomWeapons()` 返回武器页并恢复原父分区，之后关闭武器页仍返回进入前的内容。
目录可单独重试，不重复业务读取；来源首次失败保持未知，刷新失败保留旧记录并显示失败。

参考界面仍由 `RisingStonesExplorationScreen` 提供，新增入口仅面向可选扩展。紧凑宽度单栏，
中等和展开宽度侧栏加内容，阶段滚动分别保存；配置重建、尺寸改变和历史往返保留选择。
根界面把探索认证失败纳入个人数据整体清理边界，撤权和服务替换会同时清除类型化进度。
自定义宿主仍需把模型放在当前会话作用域，并在撤权时清理。

图片由服务的三类可选回调提供，不包含用户数据。透镜参数 `step=1..4` 对应过程图，`step=5`
对应官网完成标记，越界返回空值。武器图标沿用官方通用物品地址，图片无法取得时仍能阅读名称、
取得状态与日期。材料是官网记录进度，不是当前背包库存，也不应解释为服务器制作资格。

## 数据中心图片分享

`PersonalDataShareResourceService` 是可选扩展；旧 `PersonalDataService` 和原有 Compose 入口
签名无需修改。官方 `PersonalDataApiService` 提供六类公共页面地址、素材映射与离线分享目录。
`PersonalDataShareBuilder` 从已经载入的类型化状态构建快照，不调用业务接口；必须读取成功的
数据不完整时返回 `NotReady`，可选字段区分 `Known`、`Empty`、`Failed`。

接入方可用 `PersonalDataShareRenderer` 替换图片绘制，或使用
`AndroidPersonalDataShareRenderer`。后者在工作线程中绘制 Canvas、生成公共页面二维码并编码
PNG；预览仅保留内存数据，远程图片加载失败仍保留文本。`PersonalDataShareViewModel` 负责
单次生成、取消、错误重试和清理；来源服务替换或撤权必须调用 `clearProtectedContent()`。

在 `LocalPersonalDataShareHost` 中提供 `PersonalDataShareHost` 后，原生预览显示系统分享按钮。
`share(artifact)` 只在用户明确点击后调用；`clear()` 用于关闭预览或撤权。宿主需以应用级对象
管理临时文件，不能持有 Activity，也不能因旋转或系统分享面板返回就立即删图。示例 App 仅
授权随机 PNG 的精确只读 URI，使用 `ClipData` 与 `FLAG_GRANT_READ_URI_PERMISSION`；关闭预览、
会话撤销和服务替换撤销后续读取并删除文件。接收方已读取或复制的内容无法由发送方收回。

## 首次明确签到和领奖

保持 `RisingStonesAccountService` 与原有屏幕参数不变。官方服务额外实现
`RisingStonesAccountActionVerificationService`；`canVerifyDailySignIn` 仅表示当前账号可以发起
明确操作，不代表已验证签到能力。默认界面先让用户确认具体签到或奖励，确认后才调用
`verifyDailySignIn()` 或 `verifyClaimReward(id)`；创建服务、加载账号和取消确认都不调用写接口。

凭证提供器可选实现 `RisingStonesExplicitCapabilityProvider`。`beginCapabilityAttempt` 绑定
当前凭证与精确 `Required` 请求上下文，授权器只能使用一次；对应服务验证成功结果后才
`complete()`，并始终在 `finally` 中 `close()`。不要在读取验证器、页面初始化或后台轮询中
调用这组方法。未实现该扩展的宿主仍沿用自己的已验证能力集合，不会被默认授予新能力。

WebView 提供器只在内存保存写能力；普通只读刷新保留满足前置读取能力的已验证结果，接受新
凭证、断开、恢复均撤销。恢复取消可回到旧凭证的只读会话，但旧写能力和进行中的尝试仍失效。
授权是本次操作开始点；已经开始的请求不能保证从服务端撤回，迟到成功也不能为另一凭证授予
能力。HTTP 鉴权失败可触发只读会话复核，原写入不会自动重发；网络结果未知时不得自动重试。

直接接入 WebView 提供器的宿主还应观察 `credentialRevision`，将其与 `sessionState` 一起用于
清理账号作用域的模型和确认框。该值是不含账号身份的不透明生命周期计数，避免快速换号时
中间状态被 `StateFlow` 合并，导致相同能力集合下沿用旧模型；普通刷新或授予能力不会改变它。

## 论坛与招募的首次明确互动

官方服务通过可选 `OfficialForumActionEligibilityService` 和
`RecruitmentActionEligibilityService` 暴露尝试资格。`canAttemptAuthenticatedWrites` 与论坛
的 `canAttemptCommentImageUpload` 仅允许用户主动发起一次操作；原来的
`canPerformAuthenticatedWrites`、`canUploadCommentImages` 仍只表示已经验证的能力。
旧提供器没有此扩展时，继续以宿主提供的已验证能力作为门槛。读取成功不会直接设置写能力。

论坛 ViewModel 的 `canInteract`、`canAttachCommentImage` 和招募 ViewModel 的 `canInteract`
用于参考界面的操作入口；对应原始 `canWrite` 属性没有改变为资格含义。已有点赞、收藏、
最终评论发送、投票提交、回应提交是明确操作，删除仍需确认。不要在进入详情、恢复草稿或
刷新列表时调用任何写方法。失败和身份冲突不自动重放原操作，结果未知时只能由用户决定后续操作。

论坛图片选择只在本地读取，最终发送时才上传。上传资格独立于论坛互动资格，上传成功也不代表
评论成功；评论普通失败可在同一有效草稿中复用已上传地址。可选
`RisingStonesCapabilityAttemptGuard.isCurrent()` 在令牌 GET 已授权后、COS PUT 前检查当前
凭证和尝试生命周期，检查本身不授予能力或暴露凭证。WebView 提供器已经实现；自定义提供器
若要支持首次上传，需要让其返回的 attempt 同时实现该守卫，否则上传在 PUT 前失败关闭。
完成或关闭、换号、退出、恢复、前置读取能力撤销后的检查均返回假；协程取消继续传播。

论坛宿主应在会话资格变化时调用 `synchronizeActionEligibility()`，参考 UI 已接入。
`commentDraftRevision` 用于使本地选图和系统图片选择器的迟到结果失效，不能把旧草稿的图
恢复到新账号或新草稿。账号替换仍应由宿主清理 ViewModelStore，不能仅依赖能力布尔值变化。

招募回应还要求作者比较明确为非本人。首次资格使用 `AccountRead` 读取绑定角色并在内存比较；
旧提供器仅持有已验证 `RecruitmentWrite` 时保留原作者比较的读取授权。身份未知或本人都不开放
回应，首次资格不扩大部队读取权限。回应成功的 `data` 必须是对象，联系方式可缺失；此时显示
成功但未提供联系方式，不能补用用户刚填写的联系人，也不能为获取联系人自动重复提交。

## 部队与相册阅读

按需声明 `guild-data` 或 `guild-ui-compose`，后者通过公开依赖暴露 `guild-domain` 与
`guild-presentation`。新增四个制品不要求引用招募、动态或个人主页的界面实现。

`GuildApiService` 使用既有公共客户端及不透明会话提供器。`RisingStonesGuildSessionValidator`
包装基础校验器，重新读取 `userInfo/getUserBasicInfo` 的 `gc_id`，有部队时继续校验资料 ID；
仅成功后授予 `GuildRead`，0 对应 `OwnGuild.None`。该读取不会授予任何写能力。

`GuildService` 分别提供 `ownGuild`、`info`、`members`、`activities`、`photos`、`photo`、
`comments` 和 `replies`。`GuildId` 保留正十进制字符串，避免强行转换造成溢出；照片及评论
使用正整数 ID。相册和主评论请求到空页结束，主评论后续带服务端 `nextPageTime`，楼中楼
使用 `root_parent` 和短页结束。不要把照片详情的 `relayCount` 当作已确认的浏览数。

`RisingStonesGuildScreen(service, onNavigateBack)` 提供我的部队页，
`RisingStonesGuildPhotoScreen(service, id, onNavigateBack)` 可直接用于消息来源，无需预先知道
部队 ID。`RisingStonesGuildAuthorNavigation` 是可选的社区作者回调，动态点击则通过
`canOpenActivity` 与 `onOpenActivity` 交由宿主导航。没有对应权限时不提供回调。

默认账号页可通过 `RisingStonesAccountGuildNavigation(onOpenGuild) { ... }` 增加部队入口，
旧 `RisingStonesAccountScreen` 签名保持不变。该入口只做宿主导航，不依赖部队实现。
宿主必须在凭证替换、会话撤销或能力收回时清除功能 ViewModelStore；阅读层返回应保留仍有效
的来源 ViewModel，避免重读消息列表产生额外已读副作用。


## 部队资料、上传与照片互动

`GuildApiService` 另外实现 `GuildActionService`，旧 `GuildService` 与屏幕参数保持不变。
`GuildImageUploadApiService` 实现 `GuildImageUploadService`，使用同一公共客户端和同一会话
提供器。通过可选的 `RisingStonesGuildActionProvider(actions, images) { ... }` 为现有部队
界面增加资料编辑、相册上传与照片互动；没有提供该依赖时仍为只读界面。

```kotlin
val guild = GuildApiService(publicClient, sessionProvider)
val images = GuildImageUploadApiService(publicClient, sessionProvider)
RisingStonesGuildActionProvider(guild, images) {
    RisingStonesGuildScreen(guild, onNavigateBack = onBack)
}
```

读取成功只授予 `GuildRead`。首次明确操作使用独立 `GuildWrite` 或 `GuildImageUpload`；
宿主会话提供器需支持 `RisingStonesExplicitCapabilityProvider` 与
`RisingStonesCapabilityScopeProvider`，默认 WebView 会话实现已支持。资料管理、成员上传和
删除资格依当前角色/社区身份及目标资源单独读取，不能用一个写能力布尔值替代这些关系。

自定义界面应在打开资源时调用 `beginActionScope()`，将草稿、上传结果和最终提交绑定到
同一作用域，在离开资源、丢失读取能力或换号时关闭。不能给旧草稿重新捕获新凭证；读取
能力先撤销再恢复也不能复活旧作用域。默认 `GuildActionViewModel` 已实现此规则，宿主仍应
在会话替换时清除对应 ViewModelStore，及时移除界面中的旧内容。

封闭的 `GuildInfoUpdate` 表达六类资料修改。图片必须由官方上传实现返回，并属于相同
作用域和用途；不能把任意 URL 或另一会话的上传结果包装后提交。`GuildAlbumImageSource`
可供自定义选图器延迟读取数据，相册最多九张，每张最多 22,020,096 字节。上传失败保留
同一作用域中的成功结果，用户明确重试才继续；最终登记失败不会自动重新上传图片。

上传令牌只在明确上传时取得，不能用于能力探测。`OkHttpRisingStonesHttpClient` 对官方
COS 主机隔离默认头、CookieJar 及拦截器，写请求禁用自动重试、跳转和认证重放。自行实现
`RisingStonesHttpClient` 时也必须保留这些边界，禁止把 WebView Cookie 与配对的 User-Agent
转发给对象存储。关闭草稿不代表已上传的远端对象已回滚；官网尚无可确认的清理契约。

本地测试覆盖提交/取消、照片切换、凭证替换、上传阶段撤权、一次发送、EXIF 方位和裁剪
坐标一致性。真实账号目前只验证身份和标签读取，以及原生读取路径；写能力仍为未验证。


会话 `restore()` 遇到临时网络、服务不可用或格式异常时保持未授权状态，保留已存加密凭证
与网页 Cookie，供后续明确重试；只有确认认证失效才删除。不能把保留凭证解释为会话已通过
校验，也不能跳过读取能力验证直接开放界面。
