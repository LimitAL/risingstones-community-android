# SDK beta.6 幻化详情元数据

本候选补齐详情职业名称、性别ID和标签稳定类别码。新增默认参数保证旧Kotlin源码调用兼容，
但data class生成的JVM构造器及copy签名改变，所有二进制消费者必须使用同版domain/data制品
重新编译。无名称的裸职业ID不补名，未知整数与类别码原样保留；数字元数据向零截断，字符串
只接受整数。完整门禁、40个同版公共制品及独立Maven消费通过后，沿用既有签名发布工作流。
下文为精确候选来源与消费边界；发布是否完成以实际tag、CI、Maven与附件收据为准。

Candidate `0.1.0-beta.6` integrates the two audited detail contract commits
`43aa3277af6a8d184de14ba0b5a32b1aaf101976` and
`beb9cb1e5dee653eaf069494e081cb92d556081a` through an ordinary merge. The iOS
reference is `dcaa8aea216cf5dac0386422921ce760d4348bdb`, included in current
`aa3b777fcf660a95a5369ee20baab669c5268105`.

`GlamourDetail.jobs` retains named job objects from the detail response;
bare job IDs do not acquire invented names. `genderIds` retains unknown
integer IDs. `GlamourDetailTag.categoryCode` preserves nullable and unknown
category codes. Numeric metadata values truncate towards zero; strings must
parse as integers. These rules are private to the new detail metadata mapper;
general numeric helpers and existing tag ordering remain unchanged.

Default values keep old Kotlin source calls valid. The generated data-class
JVM constructor and `copy` signatures change, so all binary consumers must
recompile against matching domain/data artifacts. Do not mix beta.5 and
beta.6 glamour libraries. All forty public modules use the same immutable
release version, provided to the existing release workflow as a Gradle
property; no signing or repository credentials are changed.

The isolated `glamour-data` Maven consumer now reads the three metadata
fields, constructs `GlamourJob`, and invokes both changed data-class copies
using AAR/POM dependencies. It supplies no source composite. Full repository
tests, lint, Debug assembly, release manifest security, forty local
publications and all twenty-two isolated API consumers plus the aggregate
consumer must pass before release. The existing tag release workflow then
verifies signed artifacts and publishes GitHub Packages; downstream formal
Maven compilation is verified after publication.

The independent detail candidate already passed 56 JVM tests (nine new),
two module lints and assemblies at `beb9cb1`. This document is release
preparation, not a declaration that beta.6 has been published. Exact main/tag,
CI, package and attachment evidence is recorded by the coordinator. Real
authenticated detail/renderer/template UI acceptance remains separate.

## 本地候选验收

本批完整候选已通过1326项JVM测试，失败/错误/跳过均为0；41模块Debug lint无error，
Debug组装与Release清单安全通过。40个同版`0.1.0-beta.6`本地制品的AAR/POM/module、
源码及文档JAR、必需发布元数据均已核对。22个独立API场景和聚合场景全部编译通过，
其中新的glamour-data场景实际引用jobs、genderIds、categoryCode及变更的copy签名。
这些场景只消费本地版本化Maven制品，不使用源码组合构建。

编译输入在完整门禁与消费期间保持一致。远端main精确CI、不可变tag、签名Release及
GitHub Packages发布仍须完成；发布后的Astria正式远端Maven编译另由既有兼容工作流验证。
本地通过不代表远端已经发布，也不代表真实账号、模板渲染或设备UI验收完成。
