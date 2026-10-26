# 本人勋章摘要的本地 Maven / 旧消费者 ABI 补查（2026-10-19）

前批3c5c48e仅完成源码组合构建、SDK独立客户端与功能测试，并未覆盖旧二进制消费者。补查用勋章前3daa4a7模型离线编译固定旧消费者，运行时不重编译消费者，仅替换新模型：五参构造、显式copy5、旧Kotlin默认copy均NoSuchMethodError，原五个component正常。保留abi-before.json，未把源码通过误报成ABI通过。

模型加@JvmOverloads保原五参构造，保留显式copy5和旧copy$default静态描述符/原mask位；旧copy保留新medalWall，旧构造仍默认null（未知而非完整空）。当前六字段生成copy仍可显式替换/清空wall，未修改来源、日期、归属或任何采集路径。

验证只针对本次改变的AccountDashboard相对最近旧模型：旧编译消费者在当前源码JAR四种入口全过；随后抽实际本地Maven account-domain AAR的classes.jar运行同一旧消费者，四种入口全过。三项永久回归反射检查原描述符、32种旧mask（含不同logs/rewards值）、components与新copy保留/清空wall。不是任意版本全SDK/反射元数据结构完全不变的承诺。

只构建并写入SDK build/repository内独立0.1.0-medal-abi-20261019-SNAPSHOT版本的core/network/account-domain/account-data四模块：离线JDK25/JVM17，账号domain3/data25 tests + 本地Maven任务26s PASS。独立integration-tests/maven-consumer使用Maven AAR/POM、无includeBuild，只account-data surface（含新增medalWall getter）17s PASS。增强mask断言后domain3与Astria受影响21测试/消费者APK构建1m20 PASS；未重复已过profile/UI全矩阵。当前模型源码与发布sources.jar逐字一致。

本机审计证据索引：2026-10-19 批次 `fullsets-adaptive-20261019`，文件 `abi-before.json`、`abi-after.json`、冻结旧消费者 `abi/old-consumer.jar`（原文以 baseline/old-consumer.jar 简写）、`published-abi.json`（实际AAR/POM/metadata SHA）、`local-maven.log`、`maven-consumer.log`、`focused-fixed.log`。原始本机目录由私有审计记录定位，证据不随本仓库发行；这些名称为批次索引，不是仓库内相对链接。一次执行服务断连经确认未创建文件或启动进程后重试；CLI初缺本机Kotlin反射运行依赖，补已缓存依赖后运行，均非产品验收成功记录。

仅本机build/repository，无远程Maven发布、push、Actions、版本发行或签名升级资格。未改用户IDE配置、账号数据或远程清理备份。
