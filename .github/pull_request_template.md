## 范围

- [ ] 本次变更只使用石之家官方论坛及相关官方接口和资源。
- [ ] 功能、接口和资源都能明确归属于石之家官方论坛及其相关服务。
- [ ] 不包含 Cookie、令牌、请求授权头、真实个人响应、签名材料或其他秘密信息。
- [ ] 新增的硬编码网络主机已经说明用途，并加入经过审查的源码白名单。
- [ ] 认证变更仍满足禁止备份、原子存储、凭证脱敏、精确 Cookie 清理和正式清单导出策略。

## 接口与架构

- [ ] 新增或变更的接口行为有脱敏证据，并在需要时更新兼容性矩阵。
- [ ] 鉴权功能仍由能力控制，自动探测不会调用会改变状态的接口。
- [ ] domain、data、presentation、可选界面和接入方职责保持分离。
- [ ] 已审查公共 API 与 Maven 依赖影响，包括 `api` 和 `implementation` 的选择。

## 用户体验

- [ ] 用户可见字符串同时提供英文、简体中文和繁体中文。
- [ ] 界面变更已定义并验证紧凑、中等和展开宽度行为。
- [ ] 在适用场景中，加载、确认空结果、内容、刷新失败和认证失败仍可区分。

## 验证

- [ ] `bash scripts/verify-repository-layout.sh`
- [ ] `bash scripts/check-public-boundary.sh`
- [ ] `bash scripts/verify-layer-boundaries.sh`
- [ ] `bash scripts/verify-public-source-safety.sh`
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] 修改公共依赖或模块边界时，已运行发布和独立 Maven 消费检查。
