# 搭配业务错误原因与 iOS 对齐

基线 c5df0bb474e207001d3b9a57f88ed6b735b5b5f6 /固定发布0.1.0-beta.6；参考 iOS dev e7fd304fbd0fbd97d3ad5d46f477003593f05d78。SDK共享认证 mutation 的拒绝业务code曾构造Business(code,null)，丢弃实际msg/message，而iOS保留response.msg。最小改动复用现有stringValue别名读取；原code/accepted/auth冲突/cancellation/刷新策略不改，消息安全与本地化由消费者负责。

现有有限HTTP测试扩展9个mutation×5响应（msg、message、两者优先、null、缺失），逐项检查code/reason、单次请求、零refresh；保留既有成功/authlooking消息测试。完整SDK/消费者/设备/live API未验证。本地独立worktree保留原仓 clean c5df，不改变Android当前固定依赖。

发布须另行非SNAPSHOT版本、精确远端CI/签名附件/Maven以及消费者验证后才更新Android。今日唯一集中同步已用于Android PR11，不新增碎片化远端写入；该补丁只本地准备与验证。
