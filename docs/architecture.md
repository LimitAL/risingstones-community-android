# 架构与仓库边界

## 产品范围

本仓库只承载石之家官方论坛及其相关能力。

允许访问：

- `ff14risingstones.web.sdo.com`
- `apiff14risingstones.web.sdo.com`
- `ff14-eo.web.sdo.com` 的官方物品图标，匿名加载
- 石之家使用的官方对象存储
- 石之家官方网页登录流程需要的认证重定向

认证由 WebView 加载的官方页面完整承载。客户端不读取网页表单中的登录信息，也不模拟网页
认证过程；认证完成后只捕获石之家 Cookie 和对应的 WebView User-Agent，并用它们校验
石之家会话。

仓库中的功能、接口和资源都必须能明确归属于石之家官方论坛及其相关服务。任意接入方的专有
接口、账号模型、消息、通知、统计、数据目录或设计系统不得进入公共模块。

## 依赖方向

```text
app ───────────────┬──> ui-compose ──> auth-webview ──> network ──> core
                   ├──> account-ui-compose ──> account-presentation ──> account-domain
                   ├──> account-data ─────────> account-domain + network + core
                   ├──> forum-ui-compose ──> forum-presentation ──> forum-domain ──> core
                   ├──> forum-data ─────────> forum-domain + network
                   ├──> glamour-ui-compose ──> glamour-presentation ──> glamour-domain
                   ├──> glamour-data ──────────> glamour-domain + network + core
                   ├──> recruitment-ui-compose ──> recruitment-presentation ──> recruitment-domain
                   ├──> recruitment-data ─────────> recruitment-domain + network + core
                   ├──> guild-ui-compose ──> guild-presentation ──> guild-domain
                   ├──> guild-data ─────────> guild-domain + network + core
                   ├──> personal-data-ui-compose ──> personal-data-presentation ──> personal-data-domain
                   ├──> personal-data-data ──────────> personal-data-domain + network + core
                   ├──> auth-webview
                   └──> core

接入方应用 ────────> 功能模块 ──> network ──> core
接入方认证 ────────> core
```

## 模块职责

- `core` 不依赖 Android 界面、OkHttp、Compose 或具体账号模型。
- `account-domain`、`account-data` 和 `account-presentation` 只包含石之家官方账号摘要与签到
  契约；`account-ui-compose` 提供可替换的默认账号界面。
- `forum-domain` 保存论坛模型、服务契约、帖子内部链接解析和官方资源地址语义。
- `forum-data` 通过官方接口实现领域契约。
- `forum-presentation` 保存列表、搜索、分页、详情和评论状态机，不依赖具体设计系统。
- `forum-ui-compose` 提供可替换的论坛界面，定义紧凑、中等和展开宽度下的列表与详情行为。
- `dynamic-*` 独立承载关注动态、转发来源和动态评论；默认界面保持列表与详情的三档布局。
  只有当前会话成功读取关注流后才授予 `DynamicRead`，不从账号摘要推断权限。
- `profile-*` 独立承载社区个人主页、发布和收藏历史以及关系；沿用 `AccountRead`，资料映射会剔除其他用户未公开的字段。
- `message-*` 独立承载消息分类、未读摘要和显式读取状态；能力校验只调用计数接口，
  不预取或确认消息列表。
- `glamour-*` 保存投影台领域、数据、状态和可选界面。只有当前会话已经验证
  `GlamourAuthenticated` 能力时，独立客户端才显示入口。
- `recruitment-*` 保存官方招募契约、数据、状态和可选界面。匿名界面只开放已经验证的读取
  能力。
- `personal-data-*` 保存官方数据中心契约、请求、映射、状态和可选界面。个人记录请求要求
  已验证的 `PersonalData` 能力。
- 可选的鱼王、零式和幻化展示目录通过 `PersonalDataCatalogProvider` 注入。公共服务构造器
  保留空实现默认值，独立 App 显式使用 `BundledPersonalDataCatalogProvider` 加载已审核的
  官方离线目录；该目录不联网、不执行脚本，也不授予任何会话能力。
- 四类详情通过可选 `PersonalDataReadingService` 和 `PersonalDataReadingViewModel` 提供，旧
  服务保持兼容。排行筛选、套装排序与继续显示在本地完成；目录和个人记录分开重试。默认
  Compose 主界面以服务实例管理子模型作用域，更换服务时清除并释放旧主板、探索及详情模型。
- 三块目录看板由可选 `PersonalDataDashboardService`、独立分区状态机和参考内容面板承载。
  原服务保留兼容；主界面选择新分区时不同时读取旧字段模型。附加公开目录独立打包，不更改
  原目录类型。目录与记录可分别重试，认证失败及服务替换清理各页面的受保护缓存。
- 前线由可选 `PersonalDataFrontlineService` 和独立状态机承载七个读取分区，参考界面归并为
  六页。日期、比率、最佳成绩和地图职业保留独立语义；成就目录可用性不由地图目录推定。
  前线加入同一个服务作用域，更换服务、撤权及任一兄弟模型认证失败时一起清理。
- 绝境由可选 `PersonalDataUltimateService` 与独立状态机按副本保存五分区，首通摘要随总览刷新。
  733 不读取阶段；队伍按角色类别稳定排序，死亡图和列表共享有效坐标。日期保留来源精度，
  不将本地时间当作固定源时区；该模型与全部个人阅读层共用撤权和服务替换清理边界。
- 蜃景幻境武器通过可选 `PersonalDataPhantomWeaponService` 扩展现有探索服务，同批总览同时
  返回类型化材料输入。五阶目录离线独立提供；阶段、取得状态和材料进度在 presentation 派生，
  不增加业务读取或自动确认提醒。`ExplorationViewModel` 保留原状态契约，新增独立武器状态流，
  目录失败与读取失败分开，历史往返不重复总览；认证失败清除整个个人数据模型组。
- `network` 保存官方地址、传输、请求与响应解码，以及接口语义。
  `RisingStonesResponsePolicy` 统一官网 `10000/10002` 接受规则；接受响应不代表负载有效或
  功能可用，各 data 模块和能力探测继续校验自己的数据契约。
- `auth-webview` 负责 WebView 登录、Cookie 捕获、凭证加密和公共会话实现。
- `ui-compose` 是可选模块；接入方可以使用其他 Compose 设计系统，也可以不使用 Compose。
- 所有功能界面都应查询能力集合，不得通过检查某种具体令牌来推断权限。

## 认证契约

`RisingStonesSessionProvider` 返回不透明的 `RisingStonesRequestAuthorizer`。授权器直接把
敏感请求头写入目标，不得通过 `toString`、日志、统计或异常暴露。

默认实现使用：

- `Cookie: ff14risingstones=<捕获的值>`
- 创建该 Cookie 的 WebView User-Agent
- 官方接口要求的浏览器来源请求头

恢复失败或密文损坏时，应撤销凭证而不是让应用启动崩溃。断开连接时先撤销内存授权，再清理
本地存储，并只删除官方网页与接口域名下精确名为 `ff14risingstones` 的 Cookie，不清空整个
WebView Cookie 容器。

默认存储使用不可导出的 Android Keystore AES-GCM 密钥，将 Cookie 和 User-Agent 的密文
原子写入 `noBackupFilesDir`。独立客户端禁止云备份和设备到设备迁移。登录界面只在官方
WebView 可见期间启用 `FLAG_SECURE`，退出时恢复原有窗口状态。

其他应用可以实现同一会话契约，但实现代码和额外接口必须保留在各自工程中，不得进入本仓库。

## 能力策略

认证成功与功能能力必须分开处理：

- `isLogin` 只证明浏览器会话存在。
- `getUserInfo` 无副作用探测成功后，才为当前运行时会话授予 `AccountRead`。
- 投影台列表探测成功后，才授予 `GlamourAuthenticated`；失败不撤销基础会话。
- 动态关注流返回成功且包含 `rows` 数组后，才授予 `DynamicRead`；每次校验重新判断，
  不继承旧会话的动态能力。鉴权失效清除展示状态中的动态、评论与楼中楼内容。
- 角色绑定和数据开放状态都返回可用对象后，才授予 `PersonalData`。
- `GuildRead` 根据当前账号的部队关系及部队资料读取验证，未加入部队是明确的读取结果；
  该能力独立于部队招募和全部写能力。成员、相册与消息来源通过同一受保护阅读栈导航。
- 只有当前生产接口使用该凭证来源成功后，才能增加对应能力。
- 界面应隐藏或禁用缺少能力的操作。
- 匿名论坛读取不需要会话提供者。

`DailySignIn` 不得通过自动探测授予，因为探测签到接口本身会改变账号状态。

部队有独立的实体、接口、权限和发布价值，使用四个 `guild-*` 模块。隐藏住宅值在数据层
移除，未注册社区的成员没有作者入口；动态摘要仅回调 ID，由 App 决定是否打开动态详情。
完整参数、权限与布局设计见[部队阅读设计](guild-reading-design.md)。

独立应用的传输、会话提供者和业务服务由应用运行时 ViewModel 持有。旋转只重新创建界面，
不会让已保留的功能 ViewModel 与新会话提供者分离。顶层目的地继续通过保存状态恢复。

## 源码与制品接入

开发阶段可以通过 Gradle 组合构建使用源码：

```kotlin
includeBuild("../risingstones-community-android")
```

并按标准坐标声明依赖：

```kotlin
implementation("top.cxmeow.risingstones:core:0.1.0-SNAPSHOT")
```

组合构建会将坐标替换为本地模块。正式制品发布后，可以移除 `includeBuild`，保留相同依赖
坐标并将版本改为已发布版本。详见[接入指南](host-integration.md)。

## 数据中心本地分享边界

六类分享复用现有 `personal-data` 四层模块。domain 保存强类型内容与可选渲染契约；data 从
公开脚本的版本化 JSON 提供成就、辅助职业和武器类别，不保存角色结果；presentation 从完整
已载入状态派生快照，并以单独 ViewModel 保留图片生成状态。Compose 用 Canvas 与 ZXing
绘制 PNG、预览和可读文字；二维码仅为公共看板链接，不含账号编号。

App 的 `PngShareController` 管理 `cacheDir/share` 内单张随机文件，以不可导出的
`PngShareFileProvider` 只读共享进程内登记的精确 URI。重启不恢复旧 URI 登记；会话观察器和
页面关闭均清理授权。图片上注明非官方客户端生成，不代表官方授权。分享不会调用
`myDataOpen`、上传、发帖或其他业务写接口。

## 显式操作的能力证据

只读会话校验与用户明确写入使用不同入口。部队招募验证器通过一个只读列表请求验证
`RecruitmentAuthenticated`；首次签到和领奖通过实际用户选择的操作验证 `DailySignIn`，
不以登录、账号摘要或按钮可见推断写能力。可选接口保留原有公共构造与方法签名。

核心单次尝试只公开不透明授权器；WebView 实现将其绑定到凭证世代和精确请求上下文，完成前
再次检查当前会话与协程状态。成功能力只存在内存中，并保留原有读取能力；同凭证刷新按
账号读取前置能力裁剪，退出或新凭证使所有旧尝试失效。写请求在传输和账号业务层均不自动重发。
状态机先保留明确成功结果，再单独处理后续读取失败，避免把刷新错误误报成写入失败。
