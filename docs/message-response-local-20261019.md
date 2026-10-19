# 响应列表状态读取（本地，2026-10-19）

`MessageApiService` 新增实现 `MessageResponseService`。调用 `readResponseMessages(MessageQuery(MessageCategory.Responses, recruitmentChannel = …))` 一次返回 `MessageResponsePage(page, statusesByKey)`；key 与 `page.items` 对应。仅响应类别接受此入口，其他类别继续使用原 `MessageService`/`MessageAuthorService`。

`MessageResponseStatus.isRecruiting` 取 recruit_status/status 任一为1；未知状态安全视为结束。结束行仍保留 contactInformation，target 为 null，旧扁平 API 同样禁止结束目标。当前栏正 Int 招募 ID（或正 Int id 后备）才产生目标；不猜其他栏 ID。不按本地时间推定关闭。contact_info_mask 去首尾空白保留内部换行，秒级时间拒绝非有限/溢出值。

本次未修改 CommunityMessage 或 MessageAuthorPage 构造器。ABI 基线明确为 ae82d0a：新建本机 Maven 基线后冻结 Kotlin 默认参数构造/copy 消费者 class，换当前候选依赖运行通过；40个既有域/数据 class 的公开成员/描述符无移除。候选本机版本 `0.1.0-local-response-candidate-20261019`，40制品结构检查通过；message-data、message-ui-compose 与 aggregate 三项当前 Maven 源码消费者通过；1334 JVM/128 suites 零失败/错误/跳过，41 lint报告0错误87警告4提示，test/lint/assemble及40本机发布完整门禁3m54s通过。证据位于 Android 对应检查点引用目录。此文不承诺 ae82 之前版本构造器 ABI，也不代表远程发布或真实 API 资格。

iOS 对照 origin/dev5801861 的 ResponseDTO/Card；Android UI 自行负责结束态导航、明确复制与反馈。本地合成联系方式，未请求真实社区数据。严禁本批远程发布。
