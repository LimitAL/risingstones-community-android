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

正式版本发布后，把本地仓库替换为公开 Maven 仓库，并把 `0.1.0-SNAPSHOT` 改为正式版本。

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

个人数据展示目录通过 `PersonalDataCatalogProvider` 注入。本项目默认提供空实现。任何额外
数据源的实现和接口配置都应保留在接入方自己的工程中。
