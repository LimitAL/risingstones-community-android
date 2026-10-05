# SDK beta.5 集成说明

本次将原生探索、刷新快照保留和角色头像三个本地提交，与已发布的论坛搜索、动态发布、
招募接力及宿主入口提交通过普通合并整合，保留双方历史。版本候选为 `0.1.0-beta.5`；
正式发布是否完成以对应标签的 Release 工作流及 Maven 制品为准。

## 公共接口

- `PersonalDataNativeExplorationService` 提供带 `ExplorationQuery` 的探索读取；默认仍使用
  `OfficialWeb` 查询，原生查询是调用方明确选择的可选扩展。
- `OccultCatalogService`、目录模型、JSON 解码器和图标 helper 支持宿主注入目录来源。
  SDK 不包含宿主专有服务地址，也不增加账号能力。
- `ExplorationSection.hasSnapshot` 区分未取得快照与成功取得的空结果；刷新失败可保留先前内容。
  `ExplorationViewModelFactory` 可指定展示分区，原生投影读取该快照语义。
- `RisingStonesCharacter.avatarUrl` 是可空字段；`PersonalDataApiService` 的可选
  `cacheIdentityRead` 包装允许调用方统一身份读取，默认行为保持直接读取。

## 发布消费验证

独立 Maven 消费夹具新增角色头像、原生查询、目录解码及图标 helper、分区工厂和原生武器投影
的编译引用。这些夹具只消费发布 AAR 与其 POM 依赖，不能通过源码 composite build 补齐缺失 API。

发布前运行完整 JVM 测试、Debug lint 与构建、Release 清单安全检查、本地 Maven 发布，
再检查 40 个公共制品的发布元数据及 22 个独立 API 消费面与聚合消费。正式签名与远端 Maven
发布继续使用既有 Release 工作流。运行时设备验收由下游应用在升级不可变版本后完成。
