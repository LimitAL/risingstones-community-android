# 搭配业务错误原因与 iOS 对齐

基线 c5df0bb474e207001d3b9a57f88ed6b735b5b5f6 /固定发布0.1.0-beta.6；参考 iOS dev e7fd304fbd0fbd97d3ad5d46f477003593f05d78。SDK共享认证 mutation 的拒绝业务code曾构造Business(code,null)，丢弃实际msg/message，而iOS保留response.msg。最小改动复用现有stringValue别名读取；原code/accepted/auth冲突/cancellation/刷新策略不改，消息安全与本地化由消费者负责。

现有有限HTTP测试扩展9个mutation×5响应（msg、message、两者优先、null、缺失），逐项检查code/reason、单次请求、零refresh；保留既有成功/authlooking消息测试。完整SDK/消费者/设备/live API未验证。本地独立worktree保留原仓 clean c5df，不改变Android当前固定依赖。

发布须另行非SNAPSHOT版本、精确远端CI/签名附件/Maven以及消费者验证后才更新Android。今日唯一集中同步已用于Android PR11，不新增碎片化远端写入；该补丁只本地准备与验证。

首次752668 module检查实际FAIL：56tests/1failure，原GlamourBrowsingApiTest读取错误保护要求reason为null，private-fixture不得展示；lint尚无合格终态。失败receipt/log/JUnitZIP独立保留，原读取断言未修改。

修复收窄到实际已发送request.method为POST/PUT/DELETE，仅这些mutation传递现有msg/message；GET读取继续reason=null，保持原读取保护及9×5写入回归，新增GET两alias脱敏回归。method与已发送响应同次返回，不额外执行网络/重放或改变授权刷新/cancellation。前文共享分支全传播为历史首次失败范围；新冻结源码待验证。读取与iOS显式msg差异仍OPEN，不擅自放宽既有保护。

## 规范门禁与本地发布前消费验证终态

实际测试生产源码 commit 为 bec8b06120c3fc7907fb5773124c1f15936d875c，644输入前后哈希及干净状态保持。模块57tests/7reports、lint零错误通过。SDK自身规范Gradle9.5/JDK25全仓 testDebugUnitTest/lintDebug/assembleDebug 通过4m34s：1328tests/128reports零失败、41lint报告累计零错误/100warning/4hint，1953tasks全部实际执行。首次规范离线配置缺Kotlin2.3.20缓存失败保留，随后同源码联网填充公共缓存通过。debug APK版本仍为历史默认0.1.0-SNAPSHOT，仅用于门禁，不是非SNAPSHOT发行制品。

独立本地验证版本0.1.0-beta.7-localverify.bec8b06120c3通过 publishPublicLibrariesToLocalRepository、verify-local-publications.sh 的40公共模块及必需POM元数据检查；120AAR/JAR的CRC、40POM精确版本及SDK内部依赖版本通过。verify-maven-api-consumption.sh 实际从本地Maven仓库编译22隔离API入口和aggregate全部通过，无composite源码替换。这是本地发布前验证，不代表该版本已在远端发布，也不替代发布后真实Maven消费者。

宿主独立证据归档：`sdk-full-bec8b06-archive.json`、`sdk-local-maven-bec8-current.json`、`sdk-local-maven-bec8-integrity.json`；本地发布consumer run `1716c9abbe7c475f8732edf403a35028`。正式非SNAPSHOT远端Maven发布、签名发行、发布后消费者、新Android依赖及真实设备UI均NOT-RUN。后续依此顺序推进，Android仍固定0.1.0-beta.6。


## beta.7 发布候选

候选版本为0.1.0-beta.7；使用既有签名及GitHub Packages工作流，不修改凭据、权限、签名配置或下游自动化配置。发布前已核实main为c5df0bb474e207001d3b9a57f88ed6b735b5b5f6、beta.7 tag及Release不存在、今天SDK尚无集中同步。最终生产644输入与已验证bec8相同。正式版本是否发布成功以精确远端tag、CI及实际发布制品/正式仓库消费者证据为准；这里记录候选，不预先标记发布完成。
