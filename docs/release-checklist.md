# 公开发布检查清单

本清单区分已经自动化的准备工作和必须由仓库所有者决定的事项。所有事项完成前，不应发布
正式源码版本或二进制制品。

## 当前验证基础

截至 2026-07-27，工程已在不包含任何父级源码和构建缓存的临时目录中，从零通过仓库结构
门禁、全部单元测试、Lint、Debug APK 组装、36 个公共库发布、制品检查，以及独立 Maven
消费编译。该次隔离构建执行了 1874 个 Gradle 任务。

公共 API 门禁会审查 36 个库的 `api` 声明，并分别编译 10 类只声明被测制品的独立消费场景，
覆盖协程、OkHttp/Json、WebView 认证、数据服务、Lifecycle/StateFlow、论坛领域状态和
Compose 类型，最后保留跨功能聚合编译。

Android 17 Pixel Fold 模拟器已完成独立 Debug APK 冷启动、匿名论坛、匿名招募、打开官方
登录页和无凭证返回的冒烟验证。参考界面的设备测试覆盖紧凑、中等和展开宽度。

2026-09-20 的后续验证已覆盖三块分区看板与四类详情：本地公共制品仍为 36 个，隔离 API
消费扩展为 19 种，包含可选看板状态机、Compose 面板及附加目录提供方。前线扩展也已加入
两种既有个人数据独立消费夹具，覆盖七类返回值、状态机、Factory 和全部图片回调。绝境扩展
沿用两种消费夹具，覆盖摘要、五分区、日期、散点派生、状态机和面板图片回调。离线目录必须在 APK
与 AAR 中保持一致，并在空白设备执行安装包资源检查；合成看板记录不替代真实账号证据。

蜃景武器扩展沿用两种个人数据消费夹具，检查同批类型化快照、独立目录、图像回调与新增状态流；
旧探索服务及公共构造器保持兼容。随制品携带的武器目录必须包含五阶各二十二件及编号 51000。

2026-09-20 部队阅读扩展新增四个 `guild-*` 公共模块，本地制品增至 40 个，独立 API 消费增至
22 类并保留聚合消费。新增消费覆盖部队服务、验证器、状态机、可选界面及账号部队导航提供器；
原账号界面签名保持兼容。全仓 1148 项 JVM 测试、Lint、Debug 组装及对应安全门禁通过。
真实 Android WebView 会话已验证 `GuildRead` 和原生部队阅读入口；非空照片与评论仅有源码和
合成测试证据。发布前仍需按[兼容性矩阵](compatibility-matrix.md)核对当前候选版本及线上边界。

同日部队操作扩展已完成原生实现与合成验证：全仓 1211 项 JVM 测试通过，部队/裁剪 18 项、
App 阅读栈 14 项、能力摘要 9 项设备用例通过，四个窗口边界另逐档复验。新增作用域、操作
服务、图片上传及可选界面通过 40 个制品和 22 类独立消费检查。真实会话仅验证身份、标签
读取及原生只读路径；`GuildWrite` 与 `GuildImageUpload` 仍未验证，不得描述为线上写入通过。

这些结果只是准备证据。正式发布时必须针对准确的标签源码和签名候选版本重新执行。

## 所有者决定

- [x] 选择 MIT 许可证，并将未经修改的权威文本加入 `LICENSE`。
- [x] 完成当前源码与随仓库材料的直接许可证兼容性初查。
- [ ] 复核[依赖许可证清单](dependency-licenses.md)，并针对最终候选版本检查传递依赖、
      NOTICE、官方服务条款和商标。
- [ ] 确认 GitHub 仓库已创建，并启用私密漏洞报告。
- [ ] 启用要求外部 Actions 使用完整提交 SHA 的仓库策略。
- [x] 确认源码仓库地址为
      `https://github.com/LimitAL/risingstones-community-android`。
- [x] 确认 Maven 托管位置为本仓库 GitHub Packages，并保持
      `top.cxmeow.risingstones` group 和现有 artifact 坐标。
- [x] 使用仓库维护者公开的 Git 提交名称和邮箱补齐 POM 开发者信息。
- [ ] 建立发布签名密钥的保管、备份和恢复流程。
- [ ] 使用专门测试账号完成 WebView Cookie 真实验证，并只在兼容性矩阵记录脱敏结果。
- [ ] 配置候选发布工作流需要的签名秘密变量。

## 版本与源码

- [ ] 将 `risingStonesVersion` 设置为非 `SNAPSHOT` 版本。
- [ ] 设置正数 `risingStonesAppVersionCode`，并让 `risingStonesAppVersionName` 与库版本一致。
- [ ] 确认目标提交已打标签，工作区不含生成物、凭证、`local.properties`、签名文件或额外
      服务配置。
- [ ] 使用正式版本和签名环境运行 `./gradlew verifyReleaseConfiguration`。
- [ ] 运行 `bash scripts/verify-repository-layout.sh`。
- [ ] 运行 `bash scripts/check-public-boundary.sh`。
- [ ] 确认源码安全门禁通过，所有硬编码生产主机都位于经过审查的白名单中。
- [ ] 确认敏感数据边界门禁通过，最终清单仍禁止备份、设备迁移和明文流量。

## 构建与消费

```bash
./gradlew --no-daemon \
  testDebugUnitTest \
  lintDebug \
  assembleDebug \
  verifyReleaseManifestSecurity \
  publishPublicLibrariesToLocalRepository
bash scripts/verify-local-publications.sh
bash scripts/verify-maven-api-consumption.sh
```

- [ ] 在全新、独立的检出目录中重复以上命令。
- [ ] 在紧凑、中等和展开 Android 窗口中验证独立应用。
- [ ] 至少使用一个独立示例工程，分别验证源码组合构建和生成的 Maven 仓库。

手动候选发布工作流会执行相同门禁，签名并校验 APK/AAB，打包全部 Maven 制品并生成
SHA-256 清单。它不会自动创建 GitHub Release，也不会自动向 Maven 服务器发布。正式发布
工作流只接受已有 `v0.1.0` 一类标签，在门禁全部通过后发布 GitHub Packages 和 GitHub
Release；已发布版本不可覆盖。公开仓库的工作流制品可能被其他人下载，因此只有在允许分发
该候选版本时才能手动运行。

本地验证两种消费路径时，可以使用：

```bash
# 源码组合构建由接入方自己的 Gradle 配置触发
./gradlew :app:testDebugUnitTest :app:assembleDebug

# Maven 方式由接入方指向本仓库生成的 build/repository
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  -PrisingStonesMavenUrl=file:///absolute/path/to/risingstones-community-android/build/repository \
  -PrisingStonesVersion=0.1.0-SNAPSHOT
```

## 签名与发布

正式签名只在以下环境变量全部存在时启用：

- `RISINGSTONES_RELEASE_STORE_FILE`
- `RISINGSTONES_RELEASE_STORE_PASSWORD`
- `RISINGSTONES_RELEASE_KEY_ALIAS`
- `RISINGSTONES_RELEASE_KEY_PASSWORD`
- 可选的 `RISINGSTONES_RELEASE_STORE_TYPE`，默认值为 `PKCS12`

GitHub Actions 还会从 `RISINGSTONES_RELEASE_KEYSTORE_BASE64` 恢复临时密钥库。解码后的文件
不得加入检出目录或上传为制品。只提供部分签名变量时，Gradle 配置必须直接失败。

`verifyReleaseConfiguration` 还要求：

- 非 `SNAPSHOT` 库版本。
- 一致的应用版本名和正数版本号。
- 已提交的 `LICENSE`。
- 完整有效的公共 Maven 元数据。
- 全部签名变量和存在的绝对密钥库路径。

项目地址、MIT 许可证、SCM 元数据、公开维护者名称与邮箱和 GitHub Packages 地址已经写入
`gradle.properties`。

发布前最后确认：

- [ ] 构建并验证签名 APK/AAB，过程中不输出签名变量。
- [ ] 校验摘要，并在干净设备上安装签名制品。
- [ ] 从准确的标签源码发布 Maven 制品，检查 POM、metadata、源码 JAR、文档 JAR和传递依赖。
- [ ] 如果选择 Maven Central，为所有必需文件生成 PGP 签名和仓库要求的校验文件。
- [ ] 根据最终解析依赖图生成第三方声明，包含传递许可证和上游 NOTICE。
- [ ] 发布说明区分已在线验证、仅匿名验证和待鉴权验证的能力。


## 数据中心图片分享回归

- 核对六类分享必须字段、空结果与读取失败分支，以及公共二维码不含角色或账号参数。
- 检查完整 PNG 可解码、二维码可扫描、文字不裁切，并复核 599、600、839、840dp 预览与返回。
- 检查重建保留同一预览、旧渲染不能覆盖新来源、会话撤销清空图片与已准备文件。
- 检查正式合并清单中的分享提供器不可导出、仅允许 `cacheDir/share`、仅登记精确只读 URI。
- 用真实接收应用手动验证选择目标、取消选择、接收后的内容以及接收方延迟打开；系统面板返回
  本身不表示对方已读取完成。合成平台测试已打开并取消系统分享面板，确认返回后文件仍可读取、
  关闭预览后撤销；尚未选择第三方接收目标，不冒充第三方接收结果。
