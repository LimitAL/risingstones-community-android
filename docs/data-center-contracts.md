# 数据中心官方页面契约

核对日期：2026-09-20。本记录只依据仓库源码和官网公开 JavaScript，不包含真实账号响应，不证明账号已有这些非空数据。没有调用数据中心业务 API、提醒确认或写接口，也未操作设备。

公开脚本缓存位于仓库 `output/playwright/research/catalog-20260920/`。本机无头浏览器通过匿名公开 `mob/index.html` 确认当前入口仍引用 `app.22e9274d.js`，避免仅根据孤立旧 hash 推断当前页面。所有页面契约以以下 chunk、模块和函数为定位锚点；这些压缩文件几乎都为单行，函数名比行号更精确。

## 1. 证据索引与公共接口

| 页面 | 路由 | 页面 chunk / 模块 |
| --- | --- | --- |
| 数据中心入口 | `/statistics` | `chunk-2f7f5fe4.86e07a6e.js` / `4a76` |
| 纷争前线 | `/statistics/frontline` | `chunk-5427d794.02834a98.js` / `dc50` |
| 捕鱼人 | `/statistics/fishing` | `chunk-2395dd56.6833c83b.js` / `4fc3` |
| 绝境战 | `/statistics/ultimate` | `chunk-f27e63c4.14a654ff.js` / `3c80` |
| 零式 | `/statistics/savage` | `chunk-614eb724.8da5b1c5.js` / `367f` |
| 投影外观 | `/statistics/glamour` | `chunk-e60b7080.4dd92828.js` / `4852` |
| 鱼类排行 | `/statistics/fishing/fish` | `chunk-76e1f3e0.dd73eab2.js` / `ff24` |
| 鱼饵排行 | `/statistics/fishing/baits` | `chunk-59f15b66.a40fe74f.js` / `abdf` |
| 套装收集 | `/statistics/glamour/sets` | `chunk-1ee4a37b.ffefd54c.js` / `0b24` |
| 种族性别 | `/statistics/glamour/races` | `chunk-18f3172a.f05b70f0.js` / `bb15` |

路由声明在 `output/playwright/research/recruitment-20260920/app.22e9274d.js`。鱼类、鱼饵、种族页共用 `chunk-6b5a5c38.ecd305bd.js`；套装另依赖 `chunk-01700682.481dafd2.js`。本文中的“五看板”对应上述前线、钓鱼、绝境、零式、投影；公开 SPA 没有另一个名为“战斗总览”的独立旧路由。

共享接口模块 `9f2d` 重复打包在多份页面 chunk 中，其 `i = 4ec3.a + "dataCenter/"`，经既有仓库客户端对应 `/api/home/dataCenter/`。所有下面列出的接口均为 GET，读取 `response.data`。除绝境详情的 `territory_type` 外，wrapper 未添加业务参数。不要给四条详情猜加 `page`、`limit`、`fish_type`、套装 ID 或种族参数；筛选、排序、渐进展示都是本地操作。传输层通用参数另由共享客户端负责，不属于这些 endpoint 的业务查询。

| `9f2d` 导出 | GET 后缀 | 页面消费位置 |
| --- | --- | --- |
| `h` | `dataOpenStatus` | 五看板 `mounted` |
| `u/v/w/x/y/z/A` | `frontline1TotalNew` / `frontline2WeekNew` / `frontline3JobNew` / `frontline4Best` / `frontline5Map` / `frontline6MapJob` / `frontlineActiveDetail` | 前线 `mounted` |
| `t/r/p/q/o/s` | `fishTotal1` / `fishNum2` / `fishBait3` / `fishBig4` / `fishAchieve5` / `fishSea6` | 钓鱼 `mounted` |
| `C` | `gaoNanFirst1` | 绝境 `mounted` |
| `G/E/D/B/F` | `gaoNanTeam2` / `gaoNanJob3` / `gaoNanFriend4` / `gaoNanDeadPoint5` / `gaoNanPhase6` | 绝境 `fetchData`，每次传 `territory_type=currentSelectInstance` |
| `H/I` | `getLingShi` / `getLingShiTotal` | 零式 `mounted` |
| `l/i/k/n/j/m` | `getDressRace1` / `getDressColor2` / `getDressOrnament3` / `getDressVanity4` / `getDressFullset5` / `getDressTotal7` | 投影 `mounted` |

五看板在挂载流程还调用 `a7ce.f("pvp"/"fishing"/"jue"/"lingshi"/"vanity")` 提醒确认。该调用不是下述读取契约的一部分，不能复制进自动能力探测或只读状态加载。

`dataOpenStatus` 的官方显示条件也并非统一“非零即有数据”：前线为 `pvp === "1"`；钓鱼为 `fishing.toString() === "1"`；投影为 `vanity.toString() === "1"`；零式为 `parseInt(lingshi) >= 1`；绝境为 `jue1..jue7` 中任一转字符串后等于 `"1"`。这些是页面数据可用条件，不额外授予请求能力。

## 2. 比例和单位：必须按实际 render 转换

| 来源 | 官方实际表达式 / 函数 | 显示语义 | 修复前差异 |
| --- | --- | --- | --- |
| 捕鱼总览 `succ_rate` | `4fc3` 主 render：`(100*parseFloat(total.succ_rate)).toFixed(0)+"%"` | 原值是比例，显示整数百分比；如合成 `0.845` → `85%` | `PersonalDataApiService` 原值写入 Percent，UI 直接拼 `%`，会显示 `0.845 %` |
| 前线总览 `win_rate` | `dc50` 主 render 对 `total.win_rate`、`totalComputed.win_rate` 均 `(100*parseFloat(...)).toFixed(0)+"%"` | 原值比例，整数百分比 | 修复前同样直接显示原值加 Percent |
| 前线职业胜率、地图/地图职业胜率 | `dc50` 主 render：`currentCommonUsedJobComputed.win_rate`、`currentMapJobStatsComputed.win_rate` 同上 | 整数百分比 | 现分区 flatten 后显示未经转换的数字 |
| 前线职业使用比例 `use_rate` | `commonUsedJob`：`(100*parseFloat(use_rate)).toFixed(0)` | 整数百分比；“其他”是 `100 - 各已四舍五入比例之和` | 现无此 typed 派生 |
| 前线职业 `kda_rate` | `dc50` 主 render：有真值时 `(100*parseFloat(kda_rate)).toFixed(0)+"%"` | 文案“超过…的该职业玩家”，不是 KDA 本身 | 现无语义标签 |
| 前线逐日胜率 | `currentWeekYAxisData`：`fight_times==0 ? 0 : parseFloat((win_times/fight_times*100).toFixed(2))` | 由当日次数算两位百分比，不直接取原 `win_rate` | 现仅列表原字段 |
| 前线本周胜率 | `currentWeeklyCalculatedDataComputed`：`sum(win)/sum(fight)*100` 后 `toFixed(0)`，NaN 为 0 | 七日总胜/总场的整数百分比，不能平均每日胜率 | 现未计算 |
| 前线 `kda` | `dc50` 角色数据 render `parseFloat(kda).toFixed(2)` | 无单位的两位比值，不乘 100 | 现原值字符串 |
| 种族 `continue_rate` | `bb15.raceAllChartDataComputed` 与 `4852.raceChartDataComputed`：`parseFloat((100*parseFloat(continue_rate)).toFixed(1))` | 一位百分比，旁边显示 `continue_days + 天` | 现仅原字段 |
| 前线 `clear_time` | `dc50` 主 render `totalComputed.clear_time+"小时"` | 已是小时，不除 3600 | 现无正式单位/标签 |
| 零式总览 `elapsed_time` | `367f` render 直接 `overall.elapsed_time + " h"` | 已是小时 | 现 Hours 方向正确；但不应累加多行 |
| 零式逐副本 `status.elapsed_time` | `367f` render `(parseFloat(status.elapsed_time)/60/60).toFixed(2)+"h"` | 原值秒，转换两位小时 | 现 generic 原值无语义，不能和总览用同一单位模型 |
| 绝境首通 `elapsed_time` | `3c80.elapsedTime` 按 3600、60 拆时分秒 | 原值秒 | 现 `firstClearElapsedSeconds` 方向正确 |

比例允许缺失，不得依据 key 后缀统一乘 100：例如前线 `kill_rank/heal_rank/damaged_rank/damage_rank/dead_rank/assist_rank` 的雷达满分直接为 100，源码 `parseInt(rank)` 后绘制，**不再乘 100**。`dc50` 的 `V/F` 函数按 `>=80` 显示 S、`>=50` 显示 A，其他不显示等级。

## 3. 前线完整读取与页面字段

证据：`chunk-5427d794.02834a98.js` / `dc50` 的主 render、`mounted`、下表各 computed。接口均整数组，无服务端分区参数。

| 数据集 | 官方明确消费的字段 | 本地筛选 / 计算 / 目录 |
| --- | --- | --- |
| `frontline1TotalNew` | `data_time`；`fight_times/kda/kill_times/win_rate`；`gc_id/pvp_rank/series_level`；`clear_time/occupy_count/avg_kill/avg_assist/avg_dead`；六种 `*_rank`；分享还取 `win_times` | `total` 找 `data_time="total"`；`totalComputed` 按 `total/v51/30days`；`v51Overall` 单找 v51；头部四指标始终用 total。`gc_id` 在 `flagSrc` 与“恒辉队/双蛇党/黑涡团”文本比较，不能按名称中的 id 猜数字枚举。角色场均击倒/助攻/死亡 render 两位小数 |
| `frontline2WeekNew` | `part_date/fight_times/win_times/kill_times/dead_times/assist_times` | `weeklyChartDataComputed` 取昨天起前 7 日，缺日补零，按日期升序；切换对战/冠军/击倒/死亡/胜率/KDA；逐日 KDA 是 `(kill+assist)/max(dead,1)` 保留两位；七日 KDA 为总和比值，死亡 0 时直接总击倒+总助攻 |
| `frontline3JobNew` | `data_time/job_name/times/use_rate/kill_times/win_rate/kda/kda_rate/lb_times/avg_kill/avg_assist/avg_dead/avg_damage/avg_heal/avg_damaged` | `currentJobsPeriodFilter` 使用同三种时期，本地选职业；`commonUsedJob` 按使用比例降序，职业名称关联官方职业图标目录；`lb_times` 文案依时期带“年”字，源码未给独立起止时间，不能擅自推导年度范围 |
| `frontline4Best` | `best_type/territory_type/log_time/career/result_rank/kill_times/dead_times/assist/total_damage/total_damaged/total_heal/team1_score/team2_score/team3_score` | 五种最佳 `kill/assist/damage/damaged/heal`；注意助攻字段是 `assist`，不是其它行的 `assist_times`。`resultRank` 将字符串 `0/1/2` 映射冠军/亚军/季军；`career` 为职业名称；`gradePoints` 顺序 team3、team2、team1，各条宽度用分数占总分 |
| `frontline5Map` | `territory_type/fight_times/win_times/kill_times/win_rate` | `activeMapTab` 按名称“昂萨哈凯尔/尘封秘岩/荣誉野/周边遗迹群/沃刻其特”比较 `territory_type`；此接口该字段消费为名称，不能用同名字段就套副本数字 ID |
| `frontline6MapJob` | `territory_type/job_name/job_num/job_win_times/job_kill_times/job_win_rate` | 先按地图关联，再按职业；全部职业使用地图总计，单职业映射上述 job 前缀四项；换地图把职业重置“全部” |
| `frontlineActiveDetail` | `achieve_id/achieve_name/achieve_detail/log_time` | `mounted` 读后按日期倒序；与 `PvpAchievements`（模块变量 `v["e"]`）按 `achieve_id` 关联，既显示已取得，也可展开未取得官方目录。核对基线的 Android 完全漏掉此 GET 和分区 |

现有 Android definitions 只列部分角色字段，并将所有其它分区递归 flatten。`avg_kill/avg_assist/avg_dead` 没进入总览模型；职业、最佳、地图职业无法区分各自字段名和单位；“本周战绩”并非任意原始行展示。

以上差异描述核对时的旧通用模型。后续原生实现以可选 `PersonalDataFrontlineService` 承载七个
完整分区，官方服务同时保留旧接口兼容；界面选择新前线时不再请求旧通用模型。记录类型明确
区分字段、时期、日期与单位，并使用独立状态机管理七个分区。未知时期、字段和日期保留未知，
接口重复记录原序保留；使用官网首条规则的派生视图不反向删改原始类型化记录。

前线周数据采用设备本地日期，与官网本地日期计算一致；整日没有记录才补零，存在记录而字段
缺失时不补零。数值先在十进制范围内校验，再转浮点，避免负极小值或略超上界的值被舍入成
合法零、一或一百。军团、职业图片和目录来源见[附加目录说明](personal-data-supplementary-catalogs.md)。
本批源码、合成测试和设备结论见[兼容性矩阵](compatibility-matrix.md)，不代表真实账号非空响应
已经验证；旧模型中的其它差异继续按剩余范围逐项处理。

## 4. 钓鱼看板与鱼类、鱼饵详情

### 看板

证据：`chunk-2395dd56.6833c83b.js` / `4fc3` 的 `mounted`、`fishRankingDataComputed`、`baitRankingDataComputed`、`currentPatchFishes`、`currentOceanFish`、`seaComputed`、`unlockedAchievementsIds`。

| 数据集 | 字段 / 含义 | 官方 join、排序、展示 |
| --- | --- | --- |
| `fishTotal1` | `total_times` 为“钓鱼抛竿次数”，`succ_rate` 为比例；`sea_times` 为出海次数，`max_sea_score` 为最高分 | `data[0]`，成功率见第 2 节；现英文资源 Total catches、中文“垂钓总数”没有准确表达抛竿次数 |
| `fishNum2` | `catalog_name/fish_num/fish_type` | 按数量降序取 top5，分类存在时先分类再 top5 并反转作横条；若首行没有 fish_type，主看板隐藏分类并支持旧无分类列表 |
| `fishBait3` | `catalog_name/bait_num/fish_type` | 同上，以 bait_num 排序；字段是鱼饵使用次数，不是鱼类捕获数 |
| `fishBig4` | `catalog_name/log_time/fish_num` | 普通鱼王目录 `FishData` 以 `Name == catalog_name` 关联；目录 ItemId/Icon/Name/Patch。按版本 Patch 过滤；有取得日期优先，默认日期倒序、可切数量倒序，同为未取得时 ItemId 升序。显示未钓起目录项，不只服务返回项。初始 5 项 |
| `fishBig4` 同一响应 | 同上 | 另与 `OceanFishData` 以名称关联，显示出海特殊鱼；初始 2 项，可切时间/数量排序；当前普通鱼王单目录模型不能完整表达这套出海目录 |
| `fishAchieve5` | `achieve_id/achieve_name/achieve_detail/log_time` | 已取得按日期倒序；`FishingAchievements`（`_["c"]`）关联 `achieve_id` 找图标，也显示未取得成就。现只处理已返回行 |
| `fishSea6` | `territory_type/max_sea_score/sea_times` | `seaUnlockedComputed/seaComputed` 将字符串 `900` 对应近海、`1163` 对应远洋；有对应行才开放分区，有近海无远洋时改默认近海；否则默认远洋。核对基线的 Android 漏整个 endpoint，不能由总览两个海钓字段替代 |

主看板当前 `fishRankType` 实际有 **7** 项：“普通钓场、出海垂钓、云冠群岛、憧憬湾、法恩娜、俄匊斯、奥克塞西亚”；`baitTerritoryForbidden` 明确禁止最后四项，鱼饵只有前三项。不要把旧详情页仍仅四项的列表误当主看板最新全部分类。

### 独立详情

| 路由 | GET 与响应 | 本地交互 / 排序 | 可实现 typed 边界 |
| --- | --- | --- | --- |
| `/statistics/fishing/fish` | `9f2d.r()` → `fishNum2`，`data` 整数组；明确消费 `catalog_name/fish_num/fish_type` | `ff24.raceAllChartDataComputed` 本地严格 `fish_type===selected`；四项分类：普通钓场/出海垂钓/云冠群岛/憧憬湾；默认普通钓场；按数量升序绘横条，最大排顶部；图缩放最多约 10 行，但不是 API 分页 | 鱼类数量记录、可空分类、详情本地分类选择；显示标题取 catalog_name。没有经此页证实的 item ID 或图片字段，不能猜鱼 ID |
| `/statistics/fishing/baits` | `9f2d.p()` → `fishBait3`；消费 `catalog_name/bait_num/fish_type` | `abdf.raceAllChartDataComputed`；三项分类，默认普通钓场；数量升序横条；选择只赋值、关 popup，没有新请求 | 独立鱼饵数量语义；可与鱼类共用类型安全的排行容器，但不能错误统一为 fish_num |

原生可按最大数量优先的可访问列表代替图表底到顶排序，但应保留官方数量排序与所有行，不把“首屏十条”误当总量，也不能复制官网名称截断为 11 字的视觉实现丢失完整名称。

## 5. 零式看板

证据：`chunk-614eb724.8da5b1c5.js` / `367f` 的 `mounted`、`RaidData`、`getGroupInstanceStatus`、主 render。

| 数据集 | 明确字段 | 页面消费 |
| --- | --- | --- |
| `getLingShiTotal` | `territory_num/enter_num/finish_times/elapsed_time` | `data[0]` 分别称霸副本数、进入次数、通过次数、累计小时。修复前 `aggregateMetrics` 累加所有返回行与源码不一致 |
| `getLingShi` | `territory_type/log_time/no_limit/job_name/elapsed_time` | 按 `parseInt(territory_type)` 关联目录 `instanceId`；日期、职业、攻略秒数；`no_limit=="1"` 以灰色表达“该副本支持解除限制后通关”，不能擅自改成“本次一定使用了解除限制” |

目录层级为系列 → tier → raids：`name/abbr`、`subName.cn/en`、`achievementOnly/achievementText`、`instanceId/name/image`。`getGroupInstanceStatus` 计算 tier 是否有任一通过、是否全部通过，并保留该 tier 未通过的其它层。系列按公开数组反转后的次序展示，只展示至少命中某层的系列；成就型 tier 在已展示系列中依规则保留。`achievementOnly` 的旧系列只显示最终层成就时间，没有逐层时间、耗时、职业，且不计入总览；公开说明“巴哈姆特零式大迷宫”无通关职业。不得凭缺字段补造职业或耗时。

已有 `SavageRaidCatalogSeries/Tier/Entry` 可表达大部分目录；现 UI 完全未消费 `savageSeries`，只把 `territory_type` 当原始字段显示。需要 typed 逐副本记录关联目录，不改变既有 catalog 公共签名也能先消费。

## 6. 投影看板与种族、套装详情

证据：`chunk-e60b7080.4dd92828.js` / `4852` 的 `mounted`、各 `*RankingDataComputed`、`currentRaceComputed`、`commonRaceComputed`、`mirageStoreSet*`。

| 数据集 | 明确字段 | 页面消费 / 目录 |
| --- | --- | --- |
| `getDressTotal7` | `washing_num/color_times/vanity_times` | `data[0]`：幻想药使用次数/装备染剂使用数/武具投影次数。第四指标“套装收集度”来自目录+fullset，不是返回行的猜测字段。修复前错误地 aggregate 全数组且漏收集度；`personal_data_metric_washings` 简中/繁中误写“雇员洗剪吹/僱員洗剪吹”，与官方幻想药使用含义明显不符 |
| `getDressRace1` | `race/gender/continue_rate/continue_days/rate_rn/now_rn` | 主看板先按 rate_rn 数字升序取5；`now_rn=="1"` 当前种族性别、`rate_rn=="1"` 常用种族性别；比例*100，一位，天数。不要自行调用另一种族目录覆盖这里 API 的文本 |
| `getDressColor2` | `catalog_id/color_times/rn` | `catalog_id` 字符串对 `StainId`；目录提供 Name/Color/IsMetallic；图按 rn 倒序，数值 color_times；未命中名称官网回退编号。不能把 catalog_id 原样 label 化 |
| `getDressOrnament3` | `ornament/ornament_times/rn` | 数字 ornament 对时尚配饰目录 ID；目录 Singular/Icon；图按 rn 倒序，数量 ornament_times；这是时尚配饰使用，不是装备饰品类别 |
| `getDressVanity4` | `rank_type/dress_type/times/Name/Icon/vanity` | `Name/Icon` 为大写字段；名称缺失时官网回退 vanity 编号。rank_type `total/year` = 全部/最近1年；类别目录的 OrderMajor `1/3/4` = 武器/装备/饰品。排除 times 字符串0，本地按数量降序 top6，图再反转；无新请求 |
| `getDressFullset5` | `setitem/partitem/log_time` | 见下面套装详情；主看板同一关联逻辑，完整卡片显示完成日期，不完整卡片显示数量比 |

投影类别目录由 `4852` 引用 `V["a"]`，消费 `id/Name/Icon/OrderMajor/OrderMinor`；过滤禁用 `id=["0","62","7","9"]`，全部选项是本地 `999`。换时期或大类将具体类别重置全部。已有 `GlamourCatalogSummary` 只有 sets/fashionAccessories/stains，尚无这套武具分类目录；扩展应另行兼容，不从“编号看起来像部位”推断。

### 种族性别详情

`/statistics/glamour/races` → `9f2d.l()` → `getDressRace1`，无参数，不分页。`chunk-18f3172a.f05b70f0.js` / `bb15.raceAllChartDataComputed` 消费 `race/gender/continue_rate/continue_days/rate_rn`，全部行按比例升序构造横条；名称为 `race + 空格 + gender`；右侧显示一位百分比和天数。`rate_rn` 被解析，但该详情图实际按占比排。与主看板 top5/当前/常用的消费必须区分，不能只缓存裁剪后的五行给详情。

### 套装收集详情

`/statistics/glamour/sets` → `9f2d.j()` → `getDressFullset5`，无参数，不分页。精确定位：`chunk-1ee4a37b.ffefd54c.js` / `0b24` 的 `mirageStoreSetItemComputed`、`mirageStoreSetItemDisplayComputed`、`mirageStoreSetOverallStatus`、子组件 `partItemsComputed/fullsetStatus`、`fetchMirageStoreStatus/upCallback/resetPage`。

| 层次 | 官方算法 | 原生实现注意 |
| --- | --- | --- |
| 目录 | `be00.b` 套装目录过滤 `be00.a` 排除 ID；字段 MirageSetId/MirageSetName/MirageSetIcon/items；单品 ItemId/Icon | 已有 catalog 能表达套装与物品，但目录提供方应保留官方排除规则；ItemId=0 不计入套装物品总数 |
| 状态关联 | `row.setitem === MirageSetId.toString()`；`fetchMirageStoreStatus` 返回所有匹配行 | 同一个 setitem 可以有多条记录，官网 flatMap 为多卡，不得仅按 setitem 去重后丢历史/不同物品组合 |
| 物品状态 | `new Set(partitem.split(','))`；某物品 ItemId 字符串包含其中即点亮 | 这是物品 ID 集合，不是名称/槽位序号；只声明能匹配目录的 ID，不造条目 |
| “已套装幻影化”筛选 | 仅判断是否有该 setitem 的任意记录 | 与“完整收集”分开：部分记录仍属于已套装幻影化 |
| 单卡完整 | partitem 去重后数量等于目录非零 ItemId 数量 | 原文以数量判等，没有验证 ID 集合完全相同；合法数据可按此表现，异常未知 ID 不应替用户猜成已收集。需单列不完整/未知证据而非静默算全 |
| 卡片文字 | 已有记录显示已取得数/目录总数；无记录显示“未套装幻影化” | 单纯 API 返回空但目录未加载时，不能宣布全部未取得 |
| 排序 | 有记录的卡片先展示，按 log_time 正序/倒序；无记录的目录随后，按 MirageSetId 正序/倒序 | 两部分不按统一名称或 ID 排序。默认正序 |
| 本地展开 | 默认可见10；滚动每次+10；改筛选/顺序重置10并回顶部 | 没有远端 page 或 cursor；不要重复请求 fullset 充当分页 |
| 总进度 | 完整记录数 / 有效目录套装数，百分比 toFixed(1) | SPA 对完整行做 `Set`，对象身份并不等同按 setitem 去重；不能据这一细节认定“后端保证每套最多一条完整行”。typed 保留行；统计去重语义需明确后再选择，不凭真实账号样本外推 |

## 7. 绝境看板与现有 typed 模型的差异

证据：`chunk-f27e63c4.14a654ff.js` / `3c80` 的 `mounted/fetchData/checkStatus/team/job/friend/phase/elapsedTime/deadPointSeriesCoordinatesComputed`。

| 接口 | 明确字段 | 当前状态与待补 |
| --- | --- | --- |
| `gaoNanFirst1` | `territory_type/clear_times/enter_before_clear/job_name/log_time/elapsed_time/dead_times` | 现 UltimateEncounterSummary 基本对应；官网按 log_time 最近记录自动选中副本，Android 先看总览再显式选详情属于导航差异，不应擅自当接口缺失 |
| `gaoNanTeam2` | `character_namee`（确有尾部 e）、`area_name/group_name/job_name` | 现模型字段匹配。官网按官方职业目录排序防护→治疗→进攻；角色名称作为内容、不能当 JSON 动态 label |
| `gaoNanJob3` | `job_name/job_times` | 现匹配；官网横条按次数升序，原生最大优先列表可表达同排序信息 |
| `gaoNanFriend4` | `team_chara_name/area_name/group_name/friend_times` | 现模型匹配，按次数降序，官网默认3人可展开；现 UI 未显示 partner 的服务器区域，领域数据已有，可直接补显示 |
| `gaoNanPhase6` | `phase/log_time` | 时间升序；`finish` 显示挑战成功，其它 p 显示 P；巴哈姆特绝境战733官网不展示此分区，不能将不存在阶段等同零进度 |
| `gaoNanDeadPoint5` | 主图明确消费 `point_x/point_y` | `G` 坐标变换：733为 `(x,-y)`；其它为 `(x-100,-(y-100))`。现领域保存原坐标、UI仅列原坐标，还没有地图图层；不要把已有原坐标错误覆盖为变换后的网络值 |

现 `period/dead_time` 由 data 解析，但本次 `3c80` 死亡图没有消费它们；不能仅据现模型断言官网该页要求这两个字段。公开说明仅显示最近500次死亡，不能把该上限理解为客户端可发送的 limit 参数。七个已知 territory（733/777/887/968/1122/1238/1363）已与现 catalog 对应；新接口无需另起旧看板枚举。

## 8. 仓库现状、兼容扩展边界与优先级

源码锚点：

- `personal-data-data/src/main/java/top/cxmeow/risingstones/feature/personaldata/data/PersonalDataApiService.kt`：`fetchBoardContent`、`definitions`、`section`、`aggregateMetrics`、`entryTitleKeys`、`JsonElement.flatten`。
- `personal-data-domain/src/main/java/top/cxmeow/risingstones/feature/personaldata/domain/PersonalDataModels.kt`：`PersonalDataEntry/Field`、`PersonalDataService`、三类 catalog。
- `personal-data-presentation/src/main/java/top/cxmeow/risingstones/feature/personaldata/presentation/PersonalDataViewModel.kt`：只支持 board、UltimateEncounter 两层选择；`catalogs` 已加载但没有详情任务状态。
- `personal-data-ui-compose/src/main/java/top/cxmeow/risingstones/feature/personaldata/ui/compose/RisingStonesPersonalDataScreen.kt`：`PersonalDataSectionCard` 只有 Show all 展开；`PersonalDataFieldRow` 把任意 field.key 交 `humanizeKey`；整文件不消费 state.catalogs。
- `app/src/main/java/top/cxmeow/risingstones/app/MainActivity.kt` 当前构造 `PersonalDataApiService(publicApiClient, sessionProvider)`，在修复前使用 EmptyPersonalDataCatalogProvider；本批接入官方离线目录。

| 优先级 | 改动边界 | 理由 |
| --- | --- | --- |
| P0 前置 | 现传输取消/鉴权清理、官方目录提供方与 App 注入 | 本次修复范围。任意未知字段、响应错误不能作为正常内容/能力证据；目录没有成功加载时不伪造未收集状态 |
| P1 | 修正 fishing/frontline 百分比、Fishing/Savage/Glamour summary 单行选择、字段标题语义 | 数值与单位已经确定错误，不是纯 UI 美化。Fishing total_times 应为抛竿次数；Glamour washing_num 应为幻想药使用次数；Savage/Glamour不应累加响应行 |
| P1 | 增加可选 typed 阅读扩展接口，保留 PersonalDataService 与原 models 的既有签名 | 接口暴露 typed 分区/记录/错误和四详情任务。旧 service 不显示新入口，不从 flatten 拼回领域对象；官方 data 实现可复用同 GET 请求，并共享已确认全量数据 |
| P1 | 四详情闭环 fish/baits/sets/races：按需打开、保留父板、详情本地筛选排序、返回恢复、刷新失败保旧数据、撤权全清 | 无新服务端 API；不要在切分类/排序时重复 GET，不裁剪源数据导致详情不完整 |
| P1 | catalog 实际 join | 鱼王名称/版本/未取得、零式层级与成就特例、投影染剂/配饰/套装是已证实必需。原始 `catalog_name` 和大写 `Name` 均不在当前 entryTitleKeys 内，导致真实合法行变成“记录N” |
| P2 功能补齐 | `fishSea6`、`frontlineActiveDetail`、对应全量目录/分区；投影时期和武具分类；前线多时期/职业/最佳/地图本地交互 | 属于尚缺实际功能，不应归入最后整体 UI/UE 改造 |
| P2 展示完善 | 绝境坐标图、阶段语义、队友服务器、图表与分享 | 现大部分领域数据已具备；分享输出仍未实现。需明确与当前 typed 阅读阶段是否同批，不能将服务读取通过称分享已完成 |

推荐的语义模型边界：保留原服务，新增可选“统计阅读详情”扩展；排行记录独立表达名称、数量和可空分类；种族记录表达 race/gender、比例、天数和当前/常用证据；套装记录表达 setId、物品 ID 集合、记录时间，不混同目录定义；零式记录区分总览小时和逐副本秒；前线各分区独立字段，不依赖任意 Map。上述是实现建议，不声称官方拥有同名对象或字段。

## 9. 明确未知与禁止推断

1. 本轮只有公开源码，没有真实成功响应；整数组消费不等于已验证所有接口的空值、数字/字符串混合、未知字段、异常结构、字段必须性。保留既有真实 Android 证据，不把它们重置未验证，也不把本轮源码证据当线上验证。
2. 鱼类/鱼饵排行榜没有经页面证明的 itemId/iconId；只可使用 catalog_name 与次数。鱼王 join 明确按名称，不应假定所有排行榜都可复用鱼王目录 ID。
3. fullset 同套多行语义、重复完整记录如何计算唯一收集度，公开客户端存在对象 Set 的实现细节；不能猜后端幂等或唯一约束。异常 partitem 空串/未知 ID 的处理需合成测试与明确策略。
4. 比例正常范围可按字段语义校验，但不应擅自截断、把缺失当0、把 `"84.5"` 猜作已经百分比再跳过*100；官网表达式就是比例*100。
5. log_time 等由官方 Moment 解析/格式化，源码没有为全部接口给定统一 Unix 单位。已明确的 elapsed_time 秒/小时区分见表；其它日期编码不能凭字段名猜。
6. 主看板分类比独立旧详情多，二者差异已列；可设计向前兼容保留未知分类，但不要把无证据字符串当新增官方固定标签。
7. 不显示未知 JSON key，更不能把动态日期键、角色名称键、嵌套路径当产品 label。官方角色名称只是已知语义字段的值。未知条目用语义明确的不可用/编号回退，不泄漏原响应。
8. 所有页面的分享与提醒确认均不包含在四详情 GET 契约内；不自动调用写/确认接口补“功能完整”。


## 10. 当前修复进度与直接来源

上述“现有实现”差异记录核对时的基线，不能代替修复后的验收结果。请求取消、授权恢复次数、
会话撤销、目录提供方、总览数值和错误文案已修复，四条结构化详情已接通原生入口和返回路径。
鱼类与鱼饵默认展示全部类别，兼容官网新分类与无分类记录；套装保留重复记录，完整进度按不同
有效目录编号计算，不合并部分记录。目录在五个主看板的完整语义关联、剩余分区与分享仍按
[剩余功能清单](remaining-functional-scope.md)逐项实现。
实际通过的测试与真实账号边界以[兼容性矩阵](compatibility-matrix.md)为准。

- [官方移动站](https://ff14risingstones.web.sdo.com/mob/index.html#/index)
- [前线页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-5427d794.02834a98.js)
- [钓鱼页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-2395dd56.6833c83b.js)
- [零式页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-614eb724.8da5b1c5.js)
- [投影页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-e60b7080.4dd92828.js)
- [绝境页面](https://ff14risingstones.web.sdo.com/mob/static/js/chunk-f27e63c4.14a654ff.js)

脚本地址只用于定位已核对的证据，不作为应用运行时依赖；后续官网更新时需重新核对入口引用。

## 11. 绝境战补充核对

2026-09-20 匿名本机无头 Chrome 再次读取当前移动入口与绝境分块，分块 SHA-256 为
`260612e1b09ba45df5d6ed5b0e42dd115b615b3a09a646a3fd22e485204d5067`，与公开缓存一致。
移动入口 `publicPath` 为空且无 `base`。七个封面沿 `1613` 图片上下文定位，均在官方移动
静态资源目录返回有效 PNG：

| 副本编号 | `mob/static/images/` 下的文件名 |
| --- | --- |
| 733 | `ff14_03d05c7264a9e0e5.png` |
| 777 | `ff14_5b2d6831aac2af1d.png` |
| 887 | `ff14_37021af70e09f86c.png` |
| 968 | `ff14_e1176e944b308333.png` |
| 1122 | `ff14_d38ad293f3237cb6.png` |
| 1238 | `ff14_39cd1fd5bf0ae3ce.png` |
| 1363 | `ff14_191b305bd9531bfa.png`（默认图） |

旧 `UltimateEncounterCatalog` 中的 `statistics/ultimate/{简称}.png` 不是该页面的资源链。
新可选服务通过 data 层提供经过核对的封面，既有公共类型不强制新增构造参数。

`gaoNanFirst1` 的 `data` 数组允许 `null` 占位，官网显式过滤；其它五分区没有该过滤，不据此
放宽任意成员。非空成员及字段只用源码明确消费的 snake_case，六接口均没有额外 `rows/list`
包裹。职业 `job_times` 的主图标题为“常用职业通关次数”；`elapsed_time` 先按整数秒拆时分秒，
小时为零时省略，分钟和秒始终展示，不是单次战斗耗时。未知计数不补零。

首通队伍实际按职业类别排序：防护、治疗、进攻；**同类别保持输入顺序**，不能用完整职业
目录序号重排。官网未知职业的比较分支不是有效排序关系，原生应稳定置后。阶段时间与首通
时间均未指定源时区，应区分本地日历值与带偏移时间；733 的阶段分区明确不展示。

死亡图为散点图，没有官方场地底图。坐标变换见第 7 节；两个轴使用相同的对称范围，按最大
绝对值与 1/2/5/10 步长向外取整。原始字段留在领域模型，非有限坐标不得进入绘图；坐标列表
与图使用同一有效点集合并说明未绘制记录，不以图中无点直接宣称没有死亡。


## 12. 蜃景幻境武器阶段公开证据

当前官方移动主分块 `chunk-7690576a.97d2f158.js` 的页面模块为 `9da6`，目录为 `2cff`。
2026-09-20 本机匿名无头 Chrome 读取成功，摘要与[实施设计](web-parity-plan.md)一致。
其 `chunk-2d216257.ad184e2d.js` 依赖是 `html2canvas`，不承载新的武器业务请求。
以下是公开页面消费行为，不等同服务器制作资格或真实账号非空结果。

| 阶段 | 目录编号 | 材料内容 |
| --- | --- | --- |
| 半影 `penumbrae` | 47869–47890，共二十二件 | 六种半魂晶累计掉落次数，无目标数量 |
| 本影 `umbrae` | 47006–47027，共二十二件 | 四色魔法球，各自首条 `quest_point` 相对一万点 |
| 黯影 `obscurum` | 50032–50053，共二十二件 | 水晶混合黏土累计量，页面标题为幻境透镜 |
| 蚀影 `eclipticum` | 50978–50999，共二十二件 | 消幻晶 α、β、γ，分别相对一百个 |
| 秘影 `occultum` | 51000–51021，共二十二件 | 官网不渲染材料区域，不能从未使用标题推断配方 |

武器仅关联 `catalog_type=幻境武器` 的获取记录，以 `catalog_id` 匹配目录编号并取第一条；
记录存在即取得，不检查数量。已获得在前并按 `first_time` 降序，未获得按编号升序。默认阶段
取页面判断的最远阶段，高于最远阶段的选项禁用；阶段切换只重置本地选择与列表展开。
秘影第一件源码使用 `51e3`，必须保留为 51000，不能从旧名称表遗漏它。

页面可见阶段从高到低判断：至少三条消幻晶记录解析后数量各不少于一百，且任意蚀影武器编号
出现在获取记录中，显示第五阶段；任一消幻晶非零显示第四阶段；任一水晶混合黏土非零显示
第三阶段；任一魔法球点数大于零显示第二阶段；任一半魂晶非零显示第一阶段，否则隐藏武器区。
源码第五阶段按记录条数而非不同材料编号计数，也不限定 α/β/γ，不得改述为已验证三类材料
齐备。源码低阶段用字符串非零判断，原生应拒绝非法数值触发开放，不能将未知当作已完成。

六种半魂晶编号 47744–47749，名称依次为青色、碧色、绿色、橙色、紫色、黄色。已有行按次数
升序再按编号升序排列，目录缺席项补在后面；魔法球按 green/blue/red/yellow 取首条后反转。
黯影取第一条非零水晶混合黏土累计量 n，进度分别是 n/100、(n−100)/200、(n−300)/300、
(n−600)/600，阈值为 100、300、600、1200，达到一千二百显示完成。蚀影固定编号
50974/50975/50976，各取匹配的第一条数量，不累加；图标为 26229/26231/26230。
成功空集合与读取失败必须分开，失败不能使用官网缺席补零规则。

现有 `getMKDItemGet4` 与 `getMKDLight8` 已保留上述字段；半魂晶“掉落记录”复用既有
`getMKDIHistory6?catalog_type=半魂晶`。页面挂载另调用 `GET sysMsg/myDataOpen?type=mkd`
确认提醒，该副作用不属于武器目录和阶段切换，原生不得自动补调。

本次原生实现保留原始重复行，武器收集数量按目录编号去重；默认折叠数量仍按官网原始匹配
行数向上取偶数、至少两件、最多二十二件。未知半魂晶保留名称或编号回退，不丢掉未来目录
记录。水晶混合黏土首条非零候选若数量未知，进度保持未知，不能用后条一千二百覆盖成完成。
日期分别保存日历日期、无偏移本地时间和带偏移时刻；只对带偏移时刻进行显示时区换算。

2026-09-20 匿名无头 Chrome 验证四个元素、六个半魂晶、三个消幻晶、四张透镜过程图与一张
完成标记均可解码：尺寸分别为 64×64、80×80、380×380、51×15。五阶段首件武器对应三个
不同图标地址，公式与官网一致，但当前匿名浏览器均收到 HTTP 567、`text/html`，被浏览器
以 `ERR_BLOCKED_BY_ORB` 阻止；未读取 HTML 正文，不能据此推断具体挑战原因或 Android 结果。
本记录只证明公开资源可达性边界，没有重新读取真实武器记录，也没有确认数据开放提醒。


## 本地图片分享（2026-09-20）

官方六页 `shareData` 把当前内存内容交给 `html2canvas` 并打开图片预览，没有新增业务请求。
原生 `PersonalDataShareBuilder` 复用相同数据范围：钓鱼取最近两条鱼王；投影的套装收集率
按完整记录而非去重编号计算，并保留原始投影时期；零式使用完整七系列目录及每副本首条记录
的状态标记；前线雷达使用 `v51` 六项 0–100 分数，S/A 分别按单项 80/50 阈值显示，
常用职业按原始全部时期排序；绝境使用首通时间线与固定七副本进度，只有单条首通才显示
勋章；蜃景保留二十四辅助职业、幸福兔/撒娇罐宝箱和半影/本影四十四件中的最近武器。

`no_limit` 指通关时副本是否已支持解除限制，不能推导玩家实际是否解限进入。未知状态明确
显示未知；读取失败的可选记录与确定空记录分开显示。必须数据刷新失败时不会把缓存旧值
当作最新成功值生成卡片。日期型、无时区本地时间与带偏移时间继续保持各自精度。

分享元数据的离线生成器只接受两个审核后的源摘要，复现资源为 5,137 字节，SHA-256 为
`4f2df8ed6cc656b6bbfd5b760375964651321b9587b8fd697a3977d3c498f8a4`。
四类固定头图、标识及装饰共十四项已匿名解码；零式、绝境默认头图另各验证一项。
原生静态目录、图片公式、合成渲染与真实角色账号结果分别记证，不以静态素材可达证明
真实角色分享成功。没有对朝圣交错路猜测分享入口。
