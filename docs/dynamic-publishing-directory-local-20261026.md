# 动态发布上传目录与 channel 绑定（2026-10-26）

依据缓存 iOS70d0065 RisingStonesImageUploader 的 channel.keyDirectoryPrefix 与 fetchToken 前缀校验，仅收紧 SDK uploadPublishingImage：channel=dynamic 的凭据目录必须以大小写敏感的 `dynamic/` 开头，并继续通过既有安全分段校验。拒绝发生于构建上传地址、签名、PUT 和上传能力完成之前。

不修改评论 default/dynamic 合同争议、时间/缓存/重试、图片类型、App scope 或任何入口及持久权限。现有通用校验仍拒绝空段、独立点段、反斜杠、百分号编码、查询/片段及非ASCII分隔字符；不进行URL解码或规范化。合法嵌套、大小写目录名、点号和段内双点保持支持，未照搬iOS对任意双点子串的更宽拒绝。

新回归使用预先声明的非秘密合成凭据和终止式内存传输。修复前跨用途 guild/fixture 在发布场景产生第二次传输调用（被fixture拦下的PUT尝试），断言预期1实际2；修复后20种错误目录均在PUT前拒绝，4种合法目录继续成功。错误目录不完成能力，attempt正常关闭。既有发布用例仅将错误的通用guild合成目录改为dynamic目录，原断言保留；评论和其他旧回归语义未改。受保护的 DynamicApiServiceTest.kt 未修改，不重试此前被拒的旧发布合同测试插入。

完整SDK testDebugUnitTest/lintDebug/assembleDebug离线通过（3m8s）：1514项单测、147 suites，零失败/错误/跳过；其中上传器13项通过。41份lint报告零错误、87警告、4提示。分层、公开源码安全、敏感数据边界脚本通过。文档门禁仍在既有 dynamic-text-comment-local-20261025.md 缺中文失败；历史私有路径文档未改，不据此宣称文档检查全部通过。

没有真实token获取、外部PUT、凭据生成、上传或业务操作。没有修改App/iOS/IDE或访问设备，Android住房修复af423df526e9保持。未知结果恢复仍待用户决策。真实服务目录样本、媒体解码、完整channel合同、Maven制品及双端运行未验；本批仅完成发布目录基础校验，不表示媒体正式发布闭环完成。所有提交仅本地，无fetch/push/Actions/发布。
