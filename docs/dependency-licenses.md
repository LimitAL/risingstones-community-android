# 直接依赖与随仓库材料许可证清单

审计日期：2026-09-20（本次补充 ZXing、AndroidX ExifInterface 与随包静态目录）。

本清单用于发布准备，不构成法律意见。项目源码采用 MIT 许可证；本清单覆盖
`gradle/libs.versions.toml` 当前声明的直接别名和仓库内提交的文件。公开发布前仍需审计已经
解析的传递依赖、必要声明、官方服务条款和商标。

## 当前结论

- 当前所有直接构建或运行时依赖都声明 Apache License 2.0。
- AndroidX 和 Compose 测试依赖同样声明 Apache License 2.0。
- 仅用于测试的 JUnit 4.13.2 声明 Eclipse Public License 1.0。
- 生产资源包含项目 XML 字符串、样式及经公开官网脚本核对的静态 JSON 目录；没有打包官方图片、字体、音视频或原生库。
- Gradle Wrapper JAR 是构建输出之外唯一提交的第三方二进制，其
  `META-INF/LICENSE` 为 Apache License 2.0；生成的 `gradlew` 脚本带有相同许可证头。

直接依赖清单没有发现与项目 MIT 许可证直接冲突的内容，但仍不能替代对完整发布制品和服务
使用义务的确认。

## 直接依赖目录

证据链接指向实际使用版本的 POM；Wrapper 使用其内嵌许可证。Compose BOM 管理的版本按
`2026.06.01` 的解析结果列出。

| 依赖系列 | 目录别名 | 版本 | 用途 | 声明许可证 | 证据 |
| --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | `android-application`、`android-library` | 9.3.0 | 仅构建 | Apache-2.0 | [Google Maven POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.0/gradle-9.3.0.pom) |
| Kotlin Gradle 插件 | `kotlin-compose`、`kotlin-serialization` | 2.4.10 | 仅构建 | Apache-2.0 | [Maven Central POM](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.10/kotlin-gradle-plugin-2.4.10.pom) |
| AndroidX Activity 与 Core | `androidx-activity-compose`、`androidx-core-ktx` | 1.13.0 / 1.18.0 | 运行时 | Apache-2.0 | [Activity POM](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.pom)、[Core POM](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.18.0/core-ktx-1.18.0.pom) |
| AndroidX ExifInterface | `androidx-exifinterface` | 1.4.2 | 运行时，读取并纠正所选图片方向 | Apache-2.0 | 对应版本源码 JAR 中 `ExifInterface.java` 的许可证头；[Google Maven 制品](https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.4.2/exifinterface-1.4.2.pom) |
| AndroidX Lifecycle | `androidx-lifecycle-runtime-compose`、`androidx-lifecycle-viewmodel-ktx`、`androidx-lifecycle-viewmodel-compose` | 2.10.0 | 运行时 | Apache-2.0 | [Lifecycle POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.10.0/lifecycle-runtime-compose-2.10.0.pom) |
| Jetpack Compose BOM | `androidx-compose-bom` | 2026.06.01 | 版本平台 | Apache-2.0 | [BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom) |
| Compose Material 3 | `androidx-compose-material3` | 1.4.0 | 运行时 | Apache-2.0 | [Material 3 POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0.pom) |
| Compose UI 与工具 | `androidx-compose-ui`、`androidx-compose-ui-tooling`、`androidx-compose-ui-tooling-preview` | 1.11.4 | 运行时、调试 | Apache-2.0 | [Compose UI POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.11.4/ui-1.11.4.pom) |
| Kotlin 协程 | `kotlinx-coroutines-core`、`kotlinx-coroutines-test` | 1.11.0 | 运行时、测试 | Apache-2.0 | [Core POM](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.11.0/kotlinx-coroutines-core-1.11.0.pom) |
| Kotlin 序列化 | `kotlinx-serialization-json` | 1.11.0 | 运行时 | Apache-2.0 | [JSON POM](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.11.0/kotlinx-serialization-json-1.11.0.pom) |
| Coil | `coil-compose`、`coil-network-okhttp` | 3.5.0 | 运行时 | Apache-2.0 | [Compose POM](https://repo1.maven.org/maven2/io/coil-kt/coil3/coil-compose/3.5.0/coil-compose-3.5.0.pom)、[网络 POM](https://repo1.maven.org/maven2/io/coil-kt/coil3/coil-network-okhttp/3.5.0/coil-network-okhttp-3.5.0.pom) |
| OkHttp | `okhttp-bom`、`okhttp`、`okhttp-coroutines`、`mockwebserver3` | 5.4.0 | 运行时、测试 | Apache-2.0 | [BOM POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-bom/5.4.0/okhttp-bom-5.4.0.pom)、[OkHttp POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.4.0/okhttp-5.4.0.pom) |
| AndroidX 设备测试 | `androidx-junit`、`androidx-espresso-core`、`androidx-compose-ui-test-junit4`、`androidx-compose-ui-test-manifest` | 1.3.0 / 3.7.0 / 1.11.4 | Android 测试 | Apache-2.0 | [AndroidX JUnit POM](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/1.3.0/junit-1.3.0.pom)、[Compose 测试 POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-junit4/1.11.4/ui-test-junit4-1.11.4.pom) |
| ZXing Core | `zxing-core` | 3.5.4 | 运行时，离线生成分享二维码 | Apache-2.0 | [版本许可证](https://github.com/zxing/zxing/blob/zxing-3.5.4/LICENSE) |
| JUnit 4 | `junit` | 4.13.2 | 仅 JVM 测试 | EPL-1.0 | [Maven Central POM](https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) |
| Gradle Wrapper | 不属于版本目录别名 | 9.5.0 | 构建引导 | Apache-2.0 | `gradle/wrapper/gradle-wrapper.jar!/META-INF/LICENSE` |

<!-- catalog-aliases: android-application android-library -->
<!-- catalog-aliases: kotlin-compose kotlin-serialization -->
<!-- catalog-aliases: androidx-activity-compose androidx-core-ktx -->
<!-- catalog-aliases: androidx-lifecycle-runtime-compose androidx-lifecycle-viewmodel-ktx androidx-lifecycle-viewmodel-compose -->
<!-- catalog-aliases: androidx-compose-bom -->
<!-- catalog-aliases: androidx-compose-material3 -->
<!-- catalog-aliases: androidx-compose-ui androidx-compose-ui-tooling androidx-compose-ui-tooling-preview -->
<!-- catalog-aliases: kotlinx-coroutines-core kotlinx-coroutines-test -->
<!-- catalog-aliases: kotlinx-serialization-json -->
<!-- catalog-aliases: coil-compose coil-network-okhttp -->
<!-- catalog-aliases: okhttp-bom okhttp okhttp-coroutines mockwebserver3 -->
<!-- catalog-aliases: androidx-junit androidx-espresso-core androidx-compose-ui-test-junit4 androidx-compose-ui-test-manifest -->
<!-- catalog-aliases: junit -->
<!-- catalog-aliases: zxing-core -->
<!-- catalog-aliases: androidx-exifinterface -->

## 随仓库材料与远程内容

本项目不打包官方论坛图片、游戏图标或字体。`personal-data-data` 的随包 JSON 为公开脚本中的游戏目录，包含来源日期和摘要，并不包含账号响应。头像、
帖子图片、表情、招募图片和游戏数据图片均为官方服务返回或为该服务构造的远程地址，只在
运行时加载。

代码开源许可证不会授予使用官方服务、API、远程内容、名称或商标的权利。公开分发前，所有者
仍需单独审查官方服务条款，选择不造成官方背书误解的应用名称和图形，并判断是否需要运行时
署名或内容使用说明。

## 发布前复核

首次公开标签前：

1. 确认发布候选版本继续包含未经修改的 MIT `LICENSE`。
2. 解析正式运行时依赖图，根据准确的签名候选版本生成第三方声明，包含传递依赖和上游
   `NOTICE`。
3. 检查每个 Maven POM 的项目许可证、源码地址和维护者信息。
4. 添加最终启动图、品牌、字体或其他资产后，重新扫描随仓库文件并记录作者和分发许可。
5. 每次版本目录或 Gradle Wrapper 更新后重新执行本清单。
