# 石之家社区（非官方）Android 客户端

面向石之家官方论坛及相关能力的非官方 Android 客户端，同时提供可供其他 Android 应用按需
接入的领域层、数据层、状态管理层和可选 Compose 界面。本项目与服务运营方不存在隶属、授权
或背书关系。

本项目范围仅限石之家官方论坛及其相关官方服务。账号认证完全在应用内 WebView 加载的官方
页面中完成；应用不读取网页表单中的登录信息。页面完成认证后，应用只读取
`ff14risingstones` Cookie，并使用同一 WebView User-Agent 校验石之家会话。

## 功能模块

- `core`：与认证来源无关的会话、能力和请求授权契约。
- `network`：石之家官方 API 的 HTTP 客户端和 Cookie 会话校验。
- `auth-webview`：官方网页登录、Cookie 捕获和 Android Keystore 加密存储。
- `ui-compose`：可选的默认 Compose 登录界面。
- `account-*`：官方账号摘要、签到记录、奖励和每日签到能力。
- `forum-*`：官方论坛列表、帖子与攻略的标题／正文搜索、详情、评论、帖子链接和资源解析。
- `profile-*`：个人资料、隐私过滤、发布与收藏历史、关注和粉丝列表。
- `message-*`：未读摘要、系统、提及、评论、点赞、招募消息及已回应记录。
- `dynamic-*`：关注动态流、详情、评论与楼中楼读取，以及可选的正文点赞、图文评论、本人内容删除、
  原生动态发布、帖子/攻略转发和五类招募转发；写入实现与真实账号验证状态分别记录。
- `recruitment-*`：副本、新人、部队、其他及跑团招募能力。
- `guild-*`：我的部队资料、成员、成员动态、相册、照片详情、评论及楼中楼阅读。
- `glamour-*`：官方投影台社区与关注流、条件及物品筛选、本人和作者作品、收藏夹管理与优惠券领取。
- `personal-data-*`：官方个人数据中心及可选展示目录契约。
- `app`：可独立安装、编译和运行的客户端。

每组功能通常分为以下四层：

- `*-domain`：平台无关的领域模型与服务契约。
- `*-data`：官方接口、传输对象、解析和映射。
- `*-presentation`：不依赖具体界面设计的状态机。
- `*-ui-compose`：可选的 Material 3 参考界面。

接入方可以只使用前三层，并保留自己的导航、界面库和页面布局。

## 获取源码

```bash
git clone git@github.com:LimitAL/risingstones-community-android.git
cd risingstones-community-android
```

## 构建要求

Gradle 守护进程要求 JDK 25，Java/Kotlin 编译目标为 JVM 17，Android SDK 为 36：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

有已启动的模拟器时，可额外运行主要界面的设备测试：

```bash
ANDROID_SERIAL=<模拟器序列号> ./gradlew :auth-webview:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :forum-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :dynamic-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :message-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :profile-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :glamour-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :personal-data-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :recruitment-ui-compose:connectedDebugAndroidTest
ANDROID_SERIAL=<模拟器序列号> ./gradlew :guild-ui-compose:connectedDebugAndroidTest
```

持续集成会执行单元测试、Lint、Debug APK 组装、分层边界、安全边界、三语资源一致性、
Maven 发布和独立消费验证。新增官方资源主机或论坛外链转换规则时，必须同步审查网络白名单。

## 作为组件接入

所有公共库使用 `top.cxmeow.risingstones` 作为 Maven group。开发阶段可以通过 Gradle
组合构建直接引用本仓库，发布阶段可以使用相同坐标的 Maven 制品，因此不需要改变源码导入。

认证接口不要求接入方使用 WebView Cookie。其他应用可以实现自己的
`RisingStonesSessionProvider`，但业务模块不得依赖具体账号系统。只有需要直接观察账号状态的
默认界面才依赖 `ObservableRisingStonesSessionProvider`。

完整接入方式见[接入指南](docs/host-integration.md)。

## 当前能力状态

独立客户端已经具备 Cookie 登录、官方论坛、官方账号、动态、消息、招募、投影台和个人数据中心的
domain、data、presentation、默认 Compose 界面与自适应导航。

个人数据中心通过可选扩展接口支持蜃景与朝圣交错路，包括职业进度、道具、成就和按需历史。
钓鱼、零式、投影、前线、绝境和蜃景支持本地生成分享图片、原生预览与系统分享；不会上传图片
或为分享新增业务请求。
投影台支持装备、眼镜与配饰候选搜索、收藏到指定收藏夹、收藏夹管理和优惠券领取；
搜索读取已有当前 Android 会话证据，新增写入流程以公开源码、合成服务和设备测试验证，
尚未操作真实账号。
具体页面与尚待补齐的能力见[官方移动站移植记录](docs/web-parity-plan.md)。
完整功能闭环与后续实施顺序见[剩余功能清单](docs/remaining-functional-scope.md)。

匿名论坛与招募读取链路已经在当前官方线上环境验证。需要身份的账号摘要、签到、论坛写入、
投影台和个人数据等能力，必须由当前 WebView 会话逐项完成无副作用探测或人工验证后才会显示
入口。应用不会因为一次登录成功就默认授予全部能力。

会话管理页只展示各项能力是否已验证，不展示 Cookie、User-Agent 或账号标识。“复制脱敏报告”
只输出稳定的布尔结果。断开连接会撤销内存会话、删除本地密文，并只清除官方域名下精确名为
`ff14risingstones` 的 Cookie。

凭证密文写入 `noBackupFilesDir`，加密密钥不可导出；独立客户端同时禁止云备份和设备到设备
迁移。官方登录 WebView 显示期间会启用 `FLAG_SECURE`，离开页面后恢复原有窗口状态。

各接口当前验证情况见[兼容性矩阵](docs/compatibility-matrix.md)。
移动站路由、接口证据及移植缺口见[原生移植记录](docs/web-parity-plan.md)。

## 发布

公共库可以发布为包含 POM、Gradle metadata、源码 JAR 和文档指引 JAR 的 Maven AAR。
项目版本可以通过 `risingStonesVersion` 设置，独立应用版本可以通过
`risingStonesAppVersionCode` 和 `risingStonesAppVersionName` 设置。

正式签名只读取 `RISINGSTONES_RELEASE_*` 环境变量。候选发布工作流只生成短期工作流制品；
正式发布工作流由 `v0.1.0` 一类标签触发，将公共库发布到 GitHub Packages，并把签名 APK、
AAB 和 SHA-256 清单附加到对应 GitHub Release。发布成功后可通过 GitHub App 向配置的接入方
仓库发送通用 `dependency-released` 兼容验证事件。

项目源码采用 [MIT 许可证](LICENSE)。正式 Maven 制品托管在本仓库的 GitHub Packages。
详见[发布检查清单](docs/release-checklist.md)和
[依赖许可证清单](docs/dependency-licenses.md)。

应用使用原创的猫爪石印图标，不使用服务运营方的官方标识。名称、图标含义、配色和安全区
约束见[品牌与图标说明](docs/brand.md)。

## 参与开发

提交代码前请阅读[贡献指南](CONTRIBUTING.md)和[架构边界](docs/architecture.md)。安全问题请按
[安全说明](SECURITY.md)私下报告，不要在公开议题中提交凭证、个人响应或可复现的账号接管细节。
