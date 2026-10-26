# 我的响应发布者与招募元数据（2026-10-19，仅本地）

参考本地iOS origin/dev5801861ca6ec430d8a13aad25feaf78cf3a3fe66的ResponseMessageDTO/Card。新增MessageResponseDetailsService/Page/Metadata/Publisher/Identity契约，一次读取返回既有response(page/statuses)与同key元数据。保留CommunityMessage、MessageResponsePage/Status、MessageAuthorPage的构造与copy，不追加旧data class主构造参数。

四栏：萌新identity映射语义身份，styleInfo的name/style；副本fb_type及progress/strategy/fb_time；部队guild_tag及weekday/weekend；其他category_name。labelInfo name容错读取/去重。目标大区/服务器无值由宿主提供本地化“全部大区”；endsAt读取有限正Unix秒；响应人数仅非负Int，拒绝负数/溢出。未知/畸形可选字段不丢整卡，重复key保留首行及其一致状态/元数据。状态仍由官方status/recruit_status控制，不凭本地时间推定结束。

发布者须非空字符串uuid及character_name，位置独立于目标大区；头像复用既有官方HTTPS审阅范围，否则无头像URL，宿主fallback。结束状态也可保留发布者。修正MessageAuthorService对Responses的语义：从误标Self改为有效Community(publisher uuid)，身份不完整则不提供作者；不从查看者、招募ID或内容作者猜测。此项是明确行为修复，不是维持旧错误语义。

本地版本0.1.0-local-response-metadata-20261019：1337 JVM/128 suites零失败/错误/跳过；41 lint报告0错误87警告4提示。test/lint/assemble/本机构建仓库发布完整门禁3m55s PASS，40个AAR/POM/module与CRC结构通过。message-data/message-ui-compose/aggregate三种源码消费者通过；message-data新API与旧Response构造/copy并存消费。

明确ABI基线49eba3f：37既有公开类成员/描述符无移除，9私有实现类排除且不承诺其ABI；冻结的旧Java ResponsePage/Status构造/copy消费者换候选依赖运行PASS。另复跑此前ae82冻结Kotlin CommunityMessage默认构造/copy消费者PASS，仅作该旧模型范围资格，不能冒称全部历史二进制兼容。Java基线夹具首次getter误写getIsRecruiting的编译错误修正为isRecruiting后再冻结，未修改冻结class以适配候选。

本机审计证据索引：2026-10-19 批次 `community-response-metadata-20261019`，文件 `sdk-verification.json`、`maven-result.json`、`abi-result.json`、`local-artifacts.json`。原始本机目录由私有审计记录定位，证据文件不随本仓库发行；此处为批次索引，不是仓库内相对链接。没有远程发布、push或Actions；不宣称真实API、iOS原生观察或宿主完整UI资格。宿主资格由Android独立检查点记录。
