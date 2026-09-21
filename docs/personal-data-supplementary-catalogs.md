# 数据中心附加目录来源与消费规则

核对日期：2026-09-20。本批使用独立匿名本机无头浏览器，从空白页打开官方移动入口并重新加载，
通过浏览器挑战后以同源 GET 核对公开脚本。浏览器默认跳转桌面页，随后明确 GET 移动入口，确认
其仍引用 `app.22e9274d.js`。入口与以下四份脚本均返回 HTTP 200；脚本内容的 SHA-256 与此前审核
缓存一致，且响应为 Webpack JavaScript，不是挑战 HTML。浏览器已经关闭。

本批拦截非 GET、业务 API 与未审查主机请求，没有读取凭证、账号或个人数据，没有调用数据提醒
确认、写接口或操作 Android 设备。公开源码和匿名资源成功不代表已验证真实账号的非空业务记录。

## 来源与资源

脚本中的数组是编译进去的公开静态目录，没有发现可替代它们的稳定官方 JSON 端点。本项目打包
经审核的精简快照，运行时不下载或执行这些脚本，也不依赖压缩变量名和 chunk hash。

| 来源 | 定位锚点 | 本批用途 |
| --- | --- | --- |
| [钓鱼页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-2395dd56.6833c83b.js) | `4fc3` 的 `OceanFishData:y`；`9bee` 导出 `c` 的数组 `r` | 海钓鱼与钓鱼成就 |
| [前线页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-5427d794.02834a98.js) | `dc50` 的 `PvpAchievements`；`9bee` 导出 `e` 的数组 `a` | 前线成就 |
| [投影页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-e60b7080.4dd92828.js) | `7799` 导出 `a`；`4852.itemCategoryComputed/vanityRankingDataComputed` | 投影类别与选择规则 |
| [零式页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-614eb724.8da5b1c5.js) | `367f.getLoadImageUrl/getGroupInstanceStatus` 与 render | 图片与成就层的展示规则 |

资源为 `personal-data-data` 内的 `official-supplementary-catalogs.json`，当前 14,962 字节；文件中
记录三个目录来源的 URL、字节数、SHA-256、核对日期、原始数量和排除规则。该内部格式的
`formatVersion=1` 不代表官方 JSON 契约，也不同于旧宿主目录解码器的 `schemaVersion=1`。

| 目录 | 原始字段 | 发布数量与规则 |
| --- | --- | --- |
| 海钓特殊鱼 | `ItemId/Name/Icon`，原值均为字符串 | 13 条；没有 `Patch`，不并入普通鱼王版本目录 |
| 钓鱼成就 | `achieve_id/achieve_name/achieve_detail/medal_id/achieve_icon` | 35 条；成就 ID、图标 ID 为整数；保留原顺序与文本 |
| 前线成就 | `achieve_id/achieve_name/achieve_detail/medal_id/achieve_type` | 35 条；没有图标编号，`iconId=null`；当前公共类型不暴露页面未使用的分类和勋章序号 |
| 投影类别 | `id/Name/Icon/OrderMajor/OrderMinor`，原值均为字符串 | 原 116 条；只发布当前页面大类 1/3/4 的 36 条：武器 25、装备 6、饰品 5 |

`BundledPersonalDataSupplementaryCatalogProvider` 独立实现 `PersonalDataSupplementaryCatalogProvider`。
原 `BundledPersonalDataCatalogProvider` 增加该可选接口并委托给新提供器；旧目录资源、旧构造器和
原宿主解码器不变。读取使用 classpath，无需 Android Context，不联网、不接触会话、不授予能力，
取消继续传播。每次返回独立列表，调用方修改列表不会污染离线缓存。

## 海钓与成就关联

`currentOceanFish` 将上述 13 条目录与 `fishBig4` 的 `catalog_name` 按名称相等关联，取
`fish_num/log_time`；未取得的目录仍显示。默认按取得时间倒序，可切数量倒序；有取得日期的
条目优先，均未取得时按 `ItemId` 升序。初始展示两条，不是服务端分页。

普通鱼王默认版本为字符串 `"2"`；`PatchInfo` 顺序为 2 重生、3 苍穹、4 红莲、5 暗影、6 晓月、
7 金曦，官网没有“全部版本”选项。普通鱼王初始展示五条。主页面鱼王和海钓图标均调用 `ed35.a`
的物品图标公式，即 `ff14-eo.web.sdo.com` 的六位图标与千位目录；分享组件另有
`fishing/icon/0{Icon}_hr1.png` 路径，不能混同主页面消费方式。

两类成就都把 API `achieve_id` 转整数，与目录成就 ID 关联。已取得行显示 API 的
`achieve_name/achieve_detail/log_time`，未取得行显示目录的名称与描述，展开后才显示未取得目录。
钓鱼成就按 `achieve_icon` 生成：

```text
https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/achievements/icon/{iconId至少六位补零}_hr1.png
```

前线所有成就使用同一公开图片
[`r7.png`](https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/r7.png)，不能由文件名猜造图标编号 7。
前线目录的额外分类字段准确名称为 `achieve_type`，不是 `type`；现页面成就列表未据它分组。

## 投影选择与排名必须分开

当前页面大类为 `OrderMajor` 1 武器、3 装备、4 饰品；工具等其它大类不在本页面选择范围。
`bannedCategoryArr=["0","62","7","9"]` 仅限制选择器。36 条目录中 7 双手咒杖、9 双手幻杖、
62 灵魂水晶保留用于“全部”记录关联，但 `selectable=false`；其余 33 条可进入选择器。编号 0
为空名称哨兵，未发布；本地“全部”的 999 也不是官方目录行。

`itemCategoryComputed` 先选择当前大类和允许选中的目录，再附加本地“全部”，随后依当前时期
记录的 `dress_type` 收窄，按 `OrderMajor/OrderMinor` 数值升序。源码对整个 `vanity` 数组执行
`map`，其它时期行产生 `undefined`：只有整个数组为空时才保留该大类所有选项；数组非空而
当前时期没有记录时，只剩“全部”。选择器不排除零次数行，不能借排名过滤后的数组生成选项。
改变时期或大类会把子类重置为“全部”。

`vanityRankingDataComputed` 在“全部”下使用完整当前大类目录，仍可包含 7/9/62；它另行过滤
当前 `rank_type`（`total/year`）和字符串 `times=="0"`，按次数降序取前六条，再反转供横条图使用。
原生可用最大值在前的列表表达排名，但不能把选择器的禁用类别直接从全部记录里删除。

## 零式图片与旧成就层

副本图片按目录 `image` 原编号拼接，不补零：

```text
https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/savage/loading/{image}_hr1.png
```

页面头图取最新通关记录对应图片；找不到时回退 `savage/default.png`。两个数据层图片 helper
分别为 `personalDataAchievementIconUrl` 与 `personalDataRaidImageUrl`，非正编号返回空，不构造
猜测地址。图标读取不附带个人数据授权。

`getGroupInstanceStatus` 按 `parseInt(territory_type)` 与目录 `instanceId` 关联。整个系列只有
至少一个副本命中才显示。在已显示系列内：

- 普通 tier 只有任意一层命中才展示；展示后包含该 tier 全部副本，未通关层为灰色。
- `achievementOnly=true` 的 tier 即使没有记录也展示图片和 `achievementText`；有记录时才显示
  最终层的成就取得时间。它不显示普通全通徽章、职业或攻略时长。
- 当前目录有六个成就型 tier：巴哈姆特普通与亚历山大零式的三个阶段。官网说明这些早期数据
  缺少逐层时间、耗时和职业，且不计入综合统计；巴哈姆特零式另缺通关职业。
- `no_limit=="1"` 表达在副本已经支持解除限制之后通关，以不同颜色呈现；不能改称本次必定
  开启了解除限制。

## 前线图片与名称目录

2026-09-20 再次通过本机匿名无头浏览器核对[移动入口](https://ff14risingstones.web.sdo.com/mob/index.html)
与上述前线脚本，均返回 HTTP 200；入口仍引用 `app.22e9274d.js`，前线脚本 SHA-256 为
`734c6bf3f856abec226fe574009b9cec9f273e0b723b8480cb9b27173d2bca0c`。本节仅核对公开脚本和图片，
未调用业务 API、读取账号数据或操作设备。

`762c.b` 含 23 个职业的 `name/abbr/category/type/id`；`dc50.getDoWAndDoMUrl` 与
`dc50.getHollowedJobIcon` 按名称精确匹配后分别生成：

```text
https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/job/{category}/{abbr}.png
https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/job/{category}/{abbr}_hollow.png
```

普通图用于最佳战绩等卡片，hollow 图用于职业选择。`category` 只取目录中的 `DoW/DoM`，
不能将外部名称直接插入路径。目录包括青魔法师与驯兽师，表示其有图片映射，不能据此推导前线
参战资格。角色分类仅有防护、治疗、进攻，官网分别使用蓝、绿、红色；未知名称保留原文，
不猜角色、缩写或图标。招募模块的在线职业目录与幻影职业目录均不替代本目录。

`dc50.mapSelects` 依次为昂萨哈凯尔、尘封秘岩、荣誉野、周边遗迹群、沃刻其特，所有 `value`
均为空。`currentMapStatsComputed/currentMapJobStatsComputed` 以 `territory_type` 名称相等
关联，当前没有地图数字编号或地图图片公式的证据。内部名称列表每次返回独立副本，未知名称
不能转换为某个已知地图。

`dc50.flagSrc` 读取全部时期 `total.gc_id` 的军团名称，render 经 `fb94` 选择旗帜模块；
不是从最佳战绩队伍编号生成军团。入口内联 Webpack 的 `d.p=""` 且 HTML 无 `base` 元素，
所以下列相对文件在 `/mob/static/images/` 下解析。三张图片已通过匿名 GET 确认为 HTTP 200、
`image/png`：

| 精确军团名称 | 公开模块 | 已核实旗帜资源 |
| --- | --- | --- |
| 恒辉队 | `1dd8` | [ff14_8039bacdf367c87e.png](https://ff14risingstones.web.sdo.com/mob/static/images/ff14_8039bacdf367c87e.png) |
| 双蛇党 | `dbd6` | [ff14_608b95c61b087678.png](https://ff14risingstones.web.sdo.com/mob/static/images/ff14_608b95c61b087678.png) |
| 黑涡团 | `07a8` | [ff14_7ba5c921c4c67b6f.png](https://ff14risingstones.web.sdo.com/mob/static/images/ff14_7ba5c921c4c67b6f.png) |

这些是当前审核过的静态文件名，不是可由编号推导的稳定公式；未知军团返回空。背景装饰未接入，
职业图标与成就图片本次只确认源码公式，不能把旗帜图片的线上成功扩展为它们的可达性验证。

`gradePoints` 的顺序为 `team3_score/team2_score/team1_score`，render 对应 `hh/ss/hw` 类及
红、黄、蓝色。当前证据没有把这些队伍编号明确连接到军团名称，原生只保留队伍编号与积分，
不凭缩写、颜色或游戏知识补映射。缺失或无效积分保持未知，不伪装为零；总分为零时不计算比例。
`resultRank` 仅对字符串 `"0"/"1"/"2"` 显示冠军、亚军、季军，其它值不猜名次。

前线已取得与未取得成就的 render 均直接使用上述 `r7.png`，复用已有 35 条前线成就目录，
不套用普通成就图标或物品图标公式。内部 helper 为 `personalDataFrontlineJobIconUrl`、
`personalDataFrontlineGrandCompanyImageUrl`、`personalDataFrontlineAchievementImageUrl`；
`personalDataFrontlineMapNames` 提供名称目录。它们不联网、不接触授权、不授予能力。

## 重生成与验证边界

维护者先通过真实浏览器核对当前官网入口与来源脚本，保存公开 JS 后，在本地运行：

```bash
python3 scripts/generate-personal-data-supplementary-catalog.py \
  --source-directory <已审核公开脚本目录> \
  --captured-on YYYY-MM-DD
```

生成器只解析指定模块中的静态字面量，拒绝函数和表达式；不联网、不执行远程 JavaScript。
模块位置、字段或消费规则改变时必须重新审查，不能仅靠脚本成功认定兼容。原始 JS、目录全量
输入和浏览器缓存不提交。运行时只读打包资源，因此更新需要重新生成并发布库。

对应合成测试覆盖独立 classpath 加载、来源字段、关键编号、数量、选择器隐藏项、旧提供器的
可选接口、调用方列表修改隔离、取消以及图片公式。设备、实际业务数据、图片服务器可达性与
发布制品检查由各自验收记录说明，不由公开脚本读取或 JVM 测试代替。
