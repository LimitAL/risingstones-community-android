# 架构与仓库边界

## 产品范围

本仓库只承载石之家官方论坛及其相关能力。

允许访问：

- `ff14risingstones.web.sdo.com`
- `apiff14risingstones.web.sdo.com`
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
- `glamour-*` 保存投影台领域、数据、状态和可选界面。只有当前会话已经验证
  `GlamourAuthenticated` 能力时，独立客户端才显示入口。
- `recruitment-*` 保存官方招募契约、数据、状态和可选界面。匿名界面只开放已经验证的读取
  能力。
- `personal-data-*` 保存官方数据中心契约、请求、映射、状态和可选界面。所有请求都要求
  已验证的 `PersonalData` 能力。
- 可选的鱼王、零式和幻化展示目录通过 `PersonalDataCatalogProvider` 注入。本项目默认提供
  空实现，不绑定任何额外数据源。
- `network` 保存官方地址、传输、请求与响应解码，以及接口语义。
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
- 角色绑定和数据开放状态都返回可用对象后，才授予 `PersonalData`。
- 只有当前生产接口使用该凭证来源成功后，才能增加对应能力。
- 界面应隐藏或禁用缺少能力的操作。
- 匿名论坛读取不需要会话提供者。

`DailySignIn` 不得通过自动探测授予，因为探测签到接口本身会改变账号状态。

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
