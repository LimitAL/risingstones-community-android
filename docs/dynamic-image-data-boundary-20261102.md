# 动态图片预处理的数据模块入口

正式宿主不依赖可选 Compose UI。dynamic-data 增加 prepareDynamicPublishingImage、normalizeDynamicPublishingImage 与 DynamicPublishingImagePreparationException，返回既有 DynamicImageUploadInput。只进行短暂 URI 读取、方向归正、尺寸约束和 JPEG／PNG 编码，不获取凭据、请求网络、保存文件或申请持久图库权限。

既有 dynamic-ui-compose 的公开实现和异常类型保持原样，不增加 UI 到数据实现层的依赖；新入口沿用同一算法。最大输入 20 MiB，长边 1920；原始字节低于 5／10 MiB 时 JPEG 质量分别 80／60，否则 40。透明图输出 PNG。HEIF 解码依赖平台既有能力；本次没有扩大 MIME、修改上传路径、时间、作用域、缓存或重试规则。

新单测覆盖分档边界、平台解码前拒绝超限、错误类别和原因保留；独立 Maven 消费者仅编译新入口，不执行媒体或业务操作。原有 HEIF／方向原生证据仍属于原批次，不能作为本次新数据入口原生通过证明。

发布候选尚未推送或发布；本地完整门禁和后续必要复验分别记录，不能用本地 Maven 生成冒充 GitHub Packages 消费验证。历史首轮合成媒体 PUT 外发结果仍未知，不查询、重试或清理远端。

## 本地验收范围

完整 JVM 结果 1602 项、零失败／错误／跳过；完整 testDebugUnitTest、lintDebug、assembleDebug、verifyReleaseManifestSecurity 与本地 Maven 生成通过。撤回 UI→data 包装依赖后，再跑受影响两个模块单测、UI lint 和本地 Maven 生成通过。40 个 beta8 本地 Maven 制品校验通过；dynamic-data 新入口消费者 33 秒、dynamic-ui-compose 既有消费者 4 秒编译通过，均为离线实际 AAR 消费，不是源工程替换。仓库布局、分层、公开依赖、中文文档、凭据和敏感数据静态检查通过，未改门禁或基线。

随后全部 22 个孤立消费面及聚合消费者均已通过离线 beta8 Maven 编译，未改消费者边界或回归规则。App 最终非源依赖门禁、新数据入口原生 HEIF／方向复验、GitHub Packages 真实发布与下载验证仍未完成。Android 发布候选的宿主个人数据清单仍有审批阻塞；不把上述本地结果当作已满足远端发布资格。


后续新数据入口原生复验：既有 Studio API36 AVD 上3项、1.043秒通过，包含两张合成 HEIC 解码与方向／颜色验证、透明PNG、JPEG尺寸／方向、超限与读取失败区分及取消。实际测试先验 DNS／Socket fail-closed，无真实凭据或上传，4份生成图片已审阅，原APK／设置及iOS／IDE恢复核对通过。仅该API36合成样本范围，不代表所有HEIF编码／API26–35或真实服务验证。宿主18文件清单已获具体批准且10项正反例通过，旧审批阻塞已解除。
