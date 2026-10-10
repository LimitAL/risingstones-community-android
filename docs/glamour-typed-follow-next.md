# 关注失败类型保留：下一版本本地候选

基线为beta.7发行源码d3c5965a5f083e4fa34e650b17e5287c23743802；beta.7不包含本候选API。GlamourUiState原followError仅String，profile/list在presentation层提前丢失Business.code/reason，null message甚至使消费者无法感知失败。新增内存中的followFailure保留实际Throwable，同时维持legacy字符串；写入和读取失败、retry/start、clear及认证state重置遵循原流程，取消继续抛出。不会持久化异常，不增加HTTP、不解析旧字符串猜code，不重设计全局通知。

4项实际ViewModel回归分别验证读失败身份/code及成功refresh清理、写入Business显式原因、null message可观察和retry清理、Cancellation不发布错误。执行终态另按冻结源码证据记录，不能把源码检查当测试通过。

该Kotlin data class新增主构造参数会改变JVM构造及copy/copy$default签名，旧预编译消费者二进制不兼容；默认参数仅提供相应Kotlin源码调用兼容。必须下一正式非SNAPSHOT版本全库重建、Maven消费者验证及下游重新编译。今天SDK集中同步已用于beta.7，该候选只本地提交验证，不追加远端写入。Android消费补丁保留独立归档，在正式包含该API的新SDK发布和消费者验证前不应用，不提前改变固定依赖。真实MainHTTP/英简繁18配置UI及全局通知仲裁仍NOT-RUN/OPEN；NaN/Infinity决定不变。

首次候选d0cd37实际focused编译FAIL30s：followFailure误置于GlamourInteractionUiState，GlamourUiState的copy无此参数，尚无新JVM或lint通过。失败源码/log/receipt独立保留；最小修复移到正确GlamourUiState末尾并移除interaction误置字段。静态apply-check不代表编译或API合同通过。

## 本地修正后终态

实际测试生产源码ff714ce602d0e34903f1ea69eb2041a91a533aac，644输入前后保持。focused门禁PASS37s：75tests/4reports零失败，其中4项新增实际ViewModel回归执行通过；lint零错误/1warning、AAR及SDK UI消费者编译通过。规范Gradle9.5全仓门禁PASS3m23s：1332tests/128reports零失败、41lint报告累计零错误/87warning/4hint、1953tasks（1874实际执行/79up-to-date），整库和SDK应用重编译通过。debug APK CRC/v2签名通过，其默认SNAPSHOT版本仅为开发验证，不是发行。

宿主独立归档为sdk-typed-follow-ff714ce-archive.json及sdk-typed-follow-full-ff714ce-archive.json，报告ZIP均CRC及每项哈希核验通过；d0cd37首次编译失败保持原状。下一正式版本Maven发布、发布后真实仓库消费者、Android接入及真实MainHTTP/三语18配置UI尚未执行。不可将beta.7发行结果继承到本候选，今天不追加SDK或Android远端同步。

公开Maven隔离入口增加对GlamourUiState默认构造、followFailure getter及Business.code的实际编译消费，避免只验证源码内部引用或旧API；将用独立本地非SNAPSHOT候选验证完整release AAR与22隔离API+aggregate。此新增仅消费者验证源码，以上ff714生产门禁证据保持独立，新的Maven执行终态待记录。

## 隔离 Maven 消费终态

实际候选 e943992308206cfc1262062f06d992a96220ef28 使用独立本地非 SNAPSHOT 版本 `0.1.0-next.localverify.e94399230820`。40 公共模块 release 发布、22 隔离 API 入口及聚合消费者全部 PASS；包含新增默认构造、followFailure getter 和 Business.code 的公开 AAR 消费。1000 文件哈希与记录一致，120 AAR/JAR ZIP CRC 通过，40 POM 自身版本和内部依赖均固定到候选版本。宿主证据 sdk-typed-follow-local-maven-current.json 与 sdk-typed-follow-e943-integrity.json。

该验证只使用本地发布仓库；候选没有远端提交/tag/正式 Maven 发布，不代表正式发布后的消费者。生产源码与 ff714 全仓门禁保持相同，新增消费者源码的独立 Maven 编译已执行。Android 接入和真实 UI 仍未执行。
