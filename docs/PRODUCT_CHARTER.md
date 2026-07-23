# 产品章程

## 文档状态

本文是描述当前合并产品基线和权威路线的 living document。

- M4 已通过 PR #55 正式验收并合并；
- M4.1 文档对齐已通过 PR #59 合并；
- Flyway 连续至 V32；
- 当前开发阶段：M5，Issue #56 / Draft PR #58；
- 下一规划阶段：M6 Ecosystem and AI；
- 权威路线：[`ROADMAP.md`](ROADMAP.md)。

详细 M4 验收证据见 [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)。

## 愿景

建设一个生产级、独立部署、可开源商用的通用审批与流程协作平台，在审批领域对齐钉钉和飞书公开可验证的核心能力，并能够以清晰边界接入公司现有项目、第三方系统和受治理 AI 能力。

平台以自有审批领域模型、Approval DSL、Form Schema 和平台治理证据为产品核心。Flowable 是通过 Engine SPI 使用的执行内核，而不是业务系统直接依赖的产品接口。

## 产品原则

- 业务系统不直接依赖 Flowable API；
- 生产代码不查询或修改 Flowable `ACT_*` 内部表；
- 流程、表单、Release Package、运行时绑定和审计证据均可版本化、验证和追踪；
- 身份、租户、权限、审计、worker、lease 和 engine identity 由服务端权威产生；
- 浏览器、H5、微信小程序和 App 的可见性与禁用状态不是安全边界；
- 外部系统调用与平台数据库事务边界必须被如实表示；
- 不以删除或覆盖历史记录的方式实现修复、重试或回放；
- 高风险能力默认 fail closed，并要求明确权限、原因、幂等和审计证据；
- AI 输出必须区分事实、推断和建议，并保留模型、Prompt、知识来源和人工决策证据。

## 平台范围

平台负责：

- 流程设计、静态验证、确定性编译、发布、部署和 Release Lifecycle；
- 动态表单、UI Schema、字段规则、节点权限和多端渲染；
- 审批任务、动态责任解析、复杂协作、委派和离职交接；
- 抄送、催办、沟通、评论、附件、已阅和消息；
- tenant-scoped 工作日历、SLA policy、SLA instance 和执行治理；
- durable intent、append-only attempt、lease、retry、DEAD 和 governed replay；
- 审计、运维、诊断、detect-only 一致性检查和异常恢复；
- 流程版本 Release Package、部署证据、生效版本和 runtime binding；
- detect-only 运行实例迁移评估；
- M5 通过阶段门禁后交付的受治理运行实例迁移；
- REST、Webhook、SDK 和连接器边界；
- M6 经过独立验收的模板生态和 AI 辅助审批。

平台不负责：

- 复刻完整办公套件或第三方品牌；
- 让宿主系统绕过平台治理直接操作 Flowable；
- 通过直接 SQL 修改流程引擎运行时状态；
- 将浏览器输入当作可信租户、操作者、权限或引擎证据；
- 将外部引擎调用伪装成与平台数据库共享的原子事务；
- 在没有官方 API 验证、执行后验证和 reconciliation 的情况下提供实例迁移；
- 让 AI 绕过审批权限、伪造操作者或直接产生不可审计的业务状态变化。

## 当前已交付能力

### M0–M2：平台、流程与表单基础

- Monorepo、Engine SPI、多端和 CI；
- 采购付款审批纵向切片；
- Approval DSL、流程树、确定性 BPMN/DMN；
- Form/UI Schema、字段权限、多端 Renderer；
- Form/Release Package、Preflight、部署、版本中心和安全导入导出。

### M3：协作与生产治理

- 委派、交接、加签、减签和并发审批；
- 通知 intent/attempt、重试、死信和 replay；
- 评论、附件、可见范围；
- 审计哈希链、完整性验证；
- detect-only 一致性和 operational failure；
- 管理权限、故障注入、性能和正式验收。

### M4：Operations / 企业级运行治理

- principal-backed 身份、租户隔离和企业责任；
- 管理 capability、资源 scope、高风险 reason/idempotency/audit；
- 工作日历、不可变 SLA policy 和权威时间计算；
- SLA intent、attempt、worker lease、retry、DEAD 和 replay；
- Process Release Lifecycle、单 ACTIVE release；
- release-bound start、immutable runtime binding；
- detect-only migration assessment 和非变更 Web 报告。

## 当前限制

M5 正在开发，真实运行实例迁移尚未通过正式验收。

M4 migration dry-run 不是：

- migration plan；
- engine execution command；
- rollback；
- 自动迁移；
- 自动重试；
- 浏览器执行控制台。

M5-A 当前 `SUPPORTED_WITH_LIMITATIONS` 只证明部分简单公开 API 能力，不批准生产执行。

## 用户

- 流程管理员、流程发布者、普通员工、部门审批管理员；
- 审计人员、运维人员、平台管理员；
- 集成开发者和开源贡献者；
- M6 后续的模板作者、连接器开发者和 AI 治理人员。

管理角色不自动获得业务任务参与权，业务参与者也不自动获得管理 capability。

## 成功标准

- 一个流程定义可在 PC、H5、微信小程序和 App 使用；
- 宿主只通过边界接口接入，不污染审批核心；
- 流程、表单、发布、部署、SLA、迁移和运行时绑定可版本化和审计；
- 写操作具备有界幂等语义，关键状态具备明确恢复路径；
- 多 worker 不产生重复业务效果或覆盖原始尝试；
- 跨租户访问不泄露资源存在性；
- 运维不依赖直接修改平台业务表或 Flowable 内部表；
- 指标保持低基数；
- AI 输入输出遵守字段权限、tenant isolation、脱敏、版本和审计要求；
- 每个正式阶段由提交 Head、永久 workflow、测试矩阵、artifact 和治理记录共同验收。

## 近期路线

### M5：Governed Process Instance Migration and Release Operations

Issue #56 / Draft PR #58 正在按 M5-A 至 M5-G 推进：

- 公开 API 能力验证；
- 不可变计划和持久化协议；
- 服务端执行、verification 和 reconciliation；
- 运维 UI、安全、并发、故障注入和正式验收。

### M6：Ecosystem and AI 

M6 单独启动，不混入 M5 PR：

- 钒钒、飞书孉等翞接器；
- Java/TypeScript SDK、事件订阅和签名 Webhook丛；
- 流程模板中心、第三方翄件和兼容性治理；
- AI 材料检查、摘要、风险和 意见建议；
- Provider/模型\/Prompt\/知识来源版本、脱敏、权限、成本、禁用和审计；
- 人工确认优先的受控自动化。

## 开源边界

通用审批能力、基础连接器，部署模板、协议、治理边界和测试工具进入 Apache-2.0 开源仓库。

公司 SSO、客户专在接口、行业迄法、私有 Prompt、生产密钥、客户部署配置和受限商业适配保留在私有扔展仓库。私有扔展不得削弱核心租户隔离、审计、并示和 AI 数据治理 表；
- M莾生亡前法查询或修改 Flowable `ACT_*` 表。
