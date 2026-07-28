# 贡献指南

感谢你参与改进石之家 Android 客户端和可复用组件。

## 项目边界

贡献内容必须符合[架构边界](docs/architecture.md)：

- 只接入石之家官方论坛及其相关官方接口和资源。
- 认证流程只由内置 WebView 加载的官方页面承载；应用只读取认证完成后生成的石之家 Cookie
  和对应 User-Agent。
- 不读取或保存网页表单中的登录信息，不在客户端模拟官方网页的认证过程。
- 不得提交任何接入方专有服务、账号模型、源码路径或私有仓库配置。

提交前运行：

```bash
bash scripts/check-public-boundary.sh
bash scripts/verify-layer-boundaries.sh
bash scripts/verify-public-source-safety.sh
```

源码安全门禁会拒绝凭证、签名材料、常见真实密钥格式，以及不在审查白名单内的生产主机。
新增官方资源主机或论坛正文外链规则时，必须明确说明原因并更新白名单。

## 本地环境

工程要求 JDK 21 和 Android SDK 36，是完全独立的 Gradle 工程。不要加入本机绝对路径、
私有 Maven 仓库或包含秘密信息的配置文件。

基础验证：

```bash
bash scripts/verify-repository-layout.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

修改公共依赖或模块边界时，还要验证发布和独立消费：

```bash
./gradlew publishPublicLibrariesToLocalRepository
bash scripts/verify-local-publications.sh
bash scripts/verify-maven-api-consumption.sh
```

## 架构约束

- `*-domain` 保存可移植的领域模型和服务契约。
- `*-data` 保存官方传输、响应解码和数据映射。
- `*-presentation` 保存不依赖具体设计系统的状态机。
- `*-ui-compose` 是可选参考界面，接入方可以替换。
- `app` 负责组装独立客户端。

domain 和 data 模块不得依赖界面模块。接入方扩展数据应通过明确的公共契约注入，并在本仓库
提供空实现或仅使用官方服务的默认实现。

## 认证与能力

不得提交 Cookie、令牌、请求授权头、真实账号响应、设备标识或个人数据。测试夹具必须使用
明显虚构的值。

WebView 凭证必须保存在 `noBackupFilesDir`，由不可导出的 Android Keystore 密钥加密，
不得写入日志。未经安全审查，不得改回普通偏好存储，也不得扩大正式清单中的导出组件。

浏览器登录成功不代表所有鉴权接口都可用。只有当前凭证实际调用成功、且失效行为已经明确后，
才能增加对应 `RisingStonesCapability`。自动能力探测不得调用会改变账号状态的接口。

接口证据变化时更新[兼容性矩阵](docs/compatibility-matrix.md)，只记录脱敏后的结果类别和日期。

## 接口变更

官方响应可能在没有版本号变化的情况下调整。修改解码器时：

1. 在兼容的前提下保留已经验证的响应结构。
2. 为新结构增加脱敏单元测试夹具。
3. 区分加载、确认空结果、内容、刷新失败和认证失败。
4. 继续传播协程取消，不要将其转换为内容错误。
5. 同时验证独立客户端和至少一种组件消费方式。

## 界面变更

参考 Compose 界面必须说明紧凑、中等和展开宽度的行为。列表与详情功能在大屏上应保持选择
稳定，在紧凑宽度下使用列表到详情的导航。导航、选择或自适应布局变化时，应新增或更新
Compose 界面测试。
