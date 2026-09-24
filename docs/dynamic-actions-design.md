# 动态互动原生移植设计

## 范围与官网证据

本批在既有动态阅读能力上补充正文点赞、评论及回复、删除本人动态和本人评论。
评论包含单张图片、官方表情和关注对象提及。均属于石之家官方社区服务，继续使用现有
`dynamic-domain/data/presentation/ui-compose` 四层，不新增模块或接入方依赖。

2026-09-22 使用本机无头 Chrome 移动模式读取官网 SPA；入口脚本
`app.22e9274d.js` 的 `cb99` 模块定义动态接口，`b775` 定义表单传输和业务码处理。
`chunk-commons.01300528.js` 的动态卡片及共享评论组件提供交互证据；详情分块为
`chunk-66537460.ead179a5.js`。该页对根评论和回复明确传入 `iszan=false`，因此共享
组件中的 `posts/like` 不作为动态评论点赞证据，本批不开放动态评论点赞。
公开脚本位于 `https://ff14risingstones.web.sdo.com/mob/static/js/`。
此次研究不执行真实账号点赞、评论、上传或删除。

| 官方 API（主机 `apiff14risingstones.web.sdo.com`） | 方法与参数 | 成功语义 |
| --- | --- | --- |
| `api/home/dynamic/dynamicDetail` | GET，`id` | 详情对象 |
| `api/home/dynamic/dynamicCommentDetail` | GET，`id,page,limit` | 评论分页 |
| `api/home/dynamic/dynamicSubCommentDetail` | GET，`root_parent,order=earliest,page,limit` | 回复分页 |
| `api/home/dynamic/like` | POST 表单，`id` | `10000/10002` 且 `data` 精确为 `1/-1` |
| `api/home/dynamic/comment` | POST 表单，见下文 | 评论组件只接受 `10000` |
| `api/home/dynamic/deleteDynamic` | DELETE 表单 body，`dynamic_id` | `10000/10002` |
| `api/home/dynamic/deleteComment` | DELETE 表单 body，`comment_id` | `10000/10002` |

写请求要求 HTTP 200，使用 `application/x-www-form-urlencoded`。评论字段为
`dynamic_id,content,parent_id,root_parent,comment_pic` 和 `atInfo[index][uuid/character_name]`。
顶层评论的两个父 ID 均为 0；回复根评论均为根 ID；回复子评论分别为被回复 ID 和所属根 ID。
正文 HTML 与图片至少一项有效，图片为单个 URL。成功后重新读取评论，不猜测返回的新评论 ID。
删除入口只采用官方 UUID 归属证据；内容作者管理他人评论暂不作为已确认权限。

## 会话、身份和图片

新增独立 `DynamicWrite`、`DynamicImageUpload`，基础明确尝试资格依赖已验证 `DynamicRead`。
登录或动态读取成功不是写入成功。写入与上传仅在用户明确触发且响应有效后分别授予能力，
不得用于自动能力探测。匿名动态响应此前已确认拒绝，匿名界面不开放相关入口。

整个草稿使用同一凭证与读能力世代绑定的作用域。页面切换、换号、读能力撤销或作用域关闭
使旧操作永久失效，后续恢复不能复活旧草稿。删除依据作用域内官方读取的 UUID，未知时不展示。
传输失败、无效响应或身份冲突不自动重发；明确认证失败只可只读重验，取消继续传播。

单图明确发送时才读取 `api/common/getCOSTokenI?channel=default` 并向既有官方 COS 上传。
上传凭证只保存在内存；COS 请求隔离 Cookie、登录 User-Agent、重定向和自动重试。
沿用官网每张 22,020,096 字节上限与已审查图片类型。图片结果必须属于原作用域，不能让调用方
拼接任意 URL。明确重试可复用同作用域内已成功的上传结果，不能宣称失败会回滚远端对象。

## 分层、交互和兼容

domain 增加可选操作服务、作用域、明确结果和评论草稿；保留原阅读接口与实体构造签名。
data 负责接口、身份关系、逐次授权和严格成功判定；presentation 管理回复目标、编辑选区、
提及身份、上传、单次提交、确认和失败保留；Compose 提供可替换的编辑与图片选择界面；
App 只组装可选服务。动态模块不依赖论坛或部队功能模块。

紧凑宽度 `<600dp` 沿用列表到详情单栏，中等宽度 `600dp..<840dp` 保留列表与详情，
展开宽度 `>=840dp` 保留稳定选择并限制阅读/编辑宽度。编辑器在当前资源打开，旋转不重复
发送；提交中避免重复点击；删除包含目标上下文和明确确认。所有可见文字维护三语。

## 验证计划与证据边界

接口合成测试覆盖精确 ID、编码、父子关系、点赞正负一、评论专属成功码、未知身份、
跨作用域图片、撤权与迟到结果、取消和禁止自动重放。状态机覆盖草稿与操作并发、局部刷新、
图片失败后显式重试，以及提及修改后身份失效。设备测试覆盖明确操作、取消、配置重建及
599、600、839、840dp；公共 API 通过独立 Maven 消费，认证改动通过安全门禁与负向自测。

源码证据、本地合成验证和真实账号验证分别记录。此前手动登录与消息已读授权不包含
本批真实写入；未执行真实写入之前保留待验证状态。
