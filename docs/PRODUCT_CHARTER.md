# 产品章程

## 文档状态

本文是描述当前合并产品基线的 living document。

当前正式基线：

- 已验收里程碑：M4；
- `main`：`58efb4255394fe3911700719669c4423a3ab212e`；
- M4 PR：#55，Merged / Closed；
- Flyway：V1–V32；
- 最终永久验证 Run：`29986751894`，`success`。

详细验收证据见 [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)。

## 愿景

建设一个生产级、独立部署、可开源商用的通用审批与流程协作平台，在审批领域对齐钉钉和飞书公开可验证的核心能力，并能够以清晰边界接入公司现有项目和第三方系统。

平台以自有审批领域模型、Approval DSL、Form Schema 和平台治理证据为产品核心。Flowable 是通过 Engine SPI 使用的执行内核，而不是业务系统直接依赖的产品接口。

## 产品原则

- 业务系统不直接依赖 Flowable API；
- 生产代码不查询或修改 Flowable `ACT_*` 内部表；
- 流程、表单、Release Package、运行时绑定和审计证据均可版本化、验证和追踪；
- 身份、租户、权限、审计、worker、lease 和 engine identity 由服务端权威产生；
- 浏览器、H5、微信小程序和 App 的可见性与禁用状态不是安全边界；
- 外部系统调用与平台数据库事务边界必须被如实表示；
- 不以删除或覆盖历史记录的方式实现修复、重试或回放；
- 高风险能力默认 fail closed，并要求明确权限、原因、幂等和审计证据。

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
- REST、Webhook、SDK 和连接器边界；
- 后续经过独立验收的 AI 辅助能力。

平台不负责：

- 复刻即时通信、文档、会议、考勤等完整办公套件；
- 复制第三方品牌、图标、源码或像素级界面；
- 让宿主系统绕过平台治理直接操作 Flowable；
- 通过直接 SQL 修改流程引擎运行时状态；
- 将浏览器输入当作可信租户、操作者、权限或引擎证据；
- 将外部引擎调用伪装成与平台数据库共享的原子事务；
- 在没有官方 API 验证、执行后验证和 reconciliation 的情况下提供实例迁移。

## 当前已交付能力

### M1–M2：流程与表单产品基础

- 采购付款审批纵向切片；
- Approval DSL 和流程树设计；
- BPMN/DMN 编译与版本证据；
- 动态表单、UI Schema、字段权限；
- Form Package、Release Package、发布预检和部署；
- PC、H5 和微信小程序运行时。

### M3：协作与生产治理

- 委派、离职交接、加签、减签和并发审批；
- 通知偏好、投递意图、尝试、重试、死信和 replay；
- 评论、回复、@人员、附件和可见范围；
- 版本化审计事件、tenant hash chain 和完整性验证；
- detect-only 一致性治理和统一 operational failure；
- 管理权限、故障注入、性能门禁和正式验收。

### M4：企业级运行治理

- principal-backed 身份、租户隔离和 server-owned request context；
- 企业责任来源、管理 capability 和资源 scope；
- 高风险 reason、idempotency 和先行审计；
- 版本化工作日历、不可变 SLA policy 和权威时间计算；
- SLA intent、attempt、worker lease、retry、DEAD 和 replay；
- Process Release Lifecycle 和单 ACTIVE release 约束；
- release-bound start、immutable runtime binding 和 fail-closed replay/read；
- detect-only migration assessment 和非变更 Web 报告。

## 当前限制

真实运行实例迁移尚未实现。

M4 的迁移 dry-run 只生成评估证据。它不是：

- migration plan；
- engine execution command；
- rollback；
- 自动迁移；
- 自动重试；
- 浏览器执行控制台。

在 M5 之前，旧实例继续保留并使用原 release/runtime binding，新实例使用当前 ACTIVE release。任何缺失或冲突的 release-bound evidence 都必须 fail closed，而不是伪造绑定。

## 用户

- 流程管理员：配置流程、表单、版本、日历和 SLA；
- 流程发布者：执行预检、发布、部署和生命周期操作；
- 普通员工：发起、审批、转办、沟通和查询；
- 部门审批管理员：在明确部门 scope 内执行授权管理；
- 审计人员：读取、导出和验证审计证据；
- 运维人员：查看运行状态、处理 DEAD 工作和执行受治理 replay；
- 平台管理员：管理租户、安全、容量和集成边界；
- 集成开发者：通过 REST、Webhook、SDK 和连接器接入平台；
- 开源贡献者：扩展通用能力、适配器和验证工具。

管理角色不自动获得业务任务参与权，业务任务参与者也不自动获得管理 capability。

## 成功标准

- 一个流程定义可在 PC、H5、微信小程序和 App 使用；
- RuoYi 5.x / 6.x 与其他宿主只通过边界接口接入，不污染审批核心；
- 流程、表单、发布、部署、SLA 和运行时绑定可版本化和审计；
- 写操作具备有界幂等语义，关键状态具备明确恢复路径；
- 多 worker 执行不会产生重复业务效果或覆盖原始尝试证据；
- 跨租户访问不泄露资源存在性；
- 生产身份和权限默认 fail closed；
- 运维不依赖直接修改平台业务表或 Flowable 内部表；
- 指标保持低基数，详细身份和相关信息进入结构化日志与 trace；
- 引擎升级或替换不要求宿主业务系统重写审批语义；
- 每个正式阶段由提交 Head、永久 workflow、测试矩阵、artifact 和治理记录共同验收。

## 近期路线

### M4.1：文档对齐

- 将 README、架构、运维和协议入口与已合并 M4 保持一致；
- 区分 living docs、immutable governance records 和 historical drafts；
- 不修改已冻结 M3/M4 验收记录。

### M5：受治理的运行实例迁移

M5 首先验证 Flowable 官方公开 API 和支持矩阵，然后才可能实现：

- immutable migration plan；
- activity mapping；
- authorization evidence；
- execution intent 和 append-only attempt；
- 默认关闭的服务端 executor；
- post-call verification；
- UNKNOWN outcome reconciliation；
- 运维、安全、并发和正式验收。

若官方 API 无法满足安全边界，M5 必须记录 `NOT_SAFE_TO_IMPLEMENT`，并采用旧实例继续完成、业务级重新发起或人工受控替代方案，不得通过内部表绕过。

## 开源边界

通用审批能力、基础连接器、部署模板、协议、治理边界和测试工具进入 Apache-2.0 开源仓库。

公司 SSO、客户专有接口、行业规则、私有 Prompt、生产密钥、客户部署配置和受限商业适配保留在私有扩展仓库。私有扩展不得削弱核心租户隔离、审计、幂等和 Flowable 内部表边界。
