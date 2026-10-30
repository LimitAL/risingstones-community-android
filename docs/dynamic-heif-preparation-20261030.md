# 动态图片 HEIF 预处理与错误边界

本地批次 `heic-preparation-20261030`，基线 `002836c34d50`。对照缓存 iOS `70d0065` 共享 ImageIO 的输入解码与 JPEG/PNG 输出语义，不代表真实服务或严格双端验证。

`prepareDynamicPublishingImage` 临时读取 URI，原始输入最多20MiB，不取得图库持久授权或保存原图。`normalizeDynamicPublishingImage` 在既有 JPEG/PNG/GIF/WebP 输入上，仅增加实测 BitmapFactory MIME `image/heif`；依赖设备解码，解码失败仍拒绝，不能据扩展名或供应器声明推断支持。没有加入未实测的 `image/heic` 别名。

归正方向、长边1920、透明PNG和非透明JPEG及原始大小质量分档保持不变。`DynamicImageUploadInput` 仍不接受原始HEIF，通用21MiB限制及上传、目录、身份、凭据、channel、签名时间、缓存和自动重试语义不变。

新的 `DynamicPublishingImagePreparationException` 继承 `IllegalArgumentException`，`tooLarge=true` 仅表示20MiB上限拒绝；`false` 合并无法读取、无法识别和解码失败。界面不显示底层异常文本或URI，也不把无法解码武断区分为损坏/不支持。协程取消继续传播 `CancellationException`，不冒充导入失败。

现有系统ImageIO生成两张96×64、8-bit RGB合成HEIC，方向1/6，ImageIO回读和容器核查通过。Studio API36上 BitmapFactory MIME为image/heif，归正后的JPEG尺寸、四色象限与旋转通过App本地源码组合原生回归；同轮原JPEG方向/1920和透明PNG回归保留。超限、坏数据、URI缺失分别断言类型。源图及生成证据不包含真实照片或凭据，所有网络守卫先在实际测试进程对合成主机/Socket做本地拒绝验证，没有真实token、PUT或发布请求。

API26–35、厂商差异、HDR、多帧、大尺寸HEIC、真实图库转码及权限、真实服务、Maven消费和严格iOS原图配对均未验。原有历史合成PUT外发结果未知，本轮不查询、重试或清理远端。
