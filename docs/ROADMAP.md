# Roadmap

本文是仓库的权威产品路线图。阶段编号以本文件为准；README 和其他 living docs 必须与本文件保持一致。

## 当前基线

- M4 已通过 PR #55 正式验收并合并；
- M4.1 文档对齐已通过 PR #59 合并；
- Flyway 连续至 `V32`；
- 唯一永久 workflow：`.github/workflows/approval-platform-validation.yml`；
- M5 正在 Issue #56 / Draft PR #58 中开发；
- M6 规划为 Ecosystem and AI。

## M0 Foundation — complete

- Monorepo、构建、CI、安全、多租户和质量门禁；
- Java Domain/Application/Engine SPI/Flowable 适配；
- TypeScript contracts、Vben PC、UniApp Mobile 和宿主接入基础；
- 产品章程、架构、DSL、表单和连接器协议。

## M1 First Vertical Slice — complete

采购付款审批形成 PC、H5、微信小程序可运行闭环：

- 版本化 DSL、Form Schema、BPMN 编译和 Flowable 部署；
- 动态责任解析和不可变身份快照；
- 条件、并行会签、审批动作、回调、催办、抄送和消息；
- 评论、@提及、附件、投影、幂等、并发、审计和 Outbox。

## M2 Designer and Forms — complete

- Approval DSL 与递归树设计器；
- Form Schema、UI Schema、节点字段权限和多端 Renderer；
- 静态检查、路径模拟、批量场景和覆盖率；
- 确定性 BPMN/DMN 编译、Form Package 和 Release Package；
- 发布/部署 Preflight、版本中心、结构 Diff 和安全导入导出；
- 生效版本、激活历史、runtime version snapshot 和并发 CAS；
- PostgreSQL/Testcontainers、性能基准和 committed-head 验证。

详细交付可从 Git 历史、M2 测试和后续验收记录追溯。

## M3 Collaboration — complete

M3 已正式验收并合并：

- 顺序/并行会签、或签、票签和权重；
- 加签、减签、委派、代理、离职交接和协办；
- 评论、回复、附件、可见范围、消息渠道和通知偏好；
- 通知 intent/attempt、重试、死信和 replay；
- tenant-scoped 审计哈希链、完整性验证和导出；
- detect-only 一致性检查和统一 operational failure；
- 管理权限、安全、并发、性能和正式验收。

验收入口：[`M3_FINAL_ACCEPTANCE.md`](M3_FINAL_ACCEPTANCE.md)。

## M4 Operations — complete

M4 已正式验收并通过 PR #55 合并，实际完成范围覆盖并扩展了原始 Operations 规划：

- principal-backed 身份、租户隔离和 server-owned request context；
- 企业责任来源、管理 capability、资源 scope 和高风险审计；
- 版本化工作日历、不可变 SLA policy、时区和 DST；
- SLA instance、execution intent、append-only attempt、lease、retry、DEAD 和 replay；
- 运维管理 API、Web operations、结构化错误和低基数指标；
- Process Release Lifecycle、单 ACTIVE release 和 immutable deployment evidence；
- release-bound start、immutable runtime binding 和 fail-closed read/replay；
- detect-only migration assessment、批次预演、`bindingEvidenceHash` 和 `reportHash`。

M4 未提供真实运行实例迁移执行；迁移 dry-run 不会修改 Flowable 或平台业务状态。

验收入口：[`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)。

## M4.1 Documentation Reconciliation — complete

- PR #59 已合并；
- README、架构、运维、协议和验收索引已与 M4 对齐；
- living docs、immutable governance records 和 historical drafts 已分类；
- 后续路线以本文件为权威。

## M5 Governed Process Instance Migration and Release Operations — in progress

跟踪：Issue #56 / Draft PR #58。

### M5-A — Public API capability validation

- 验证 Flowable 8.0.0 公开 migration API；
- 建立简单任务、并行、多实例、子流程、timer/job、变量、identity link、并发和超时支持矩阵；
- 禁止访问 `ACT_*` 内部表；
- 在证据充分前不增加 V33、生产 worker、执行端点或前端执行按钮。

当前结论：`SUPPORTED_WITH_LIMITATIONS`。简单 active user-task 迁移已有隔离证据，但复杂场景仍需验证。

### M5-B — Governed model and persistence

- migration plan、instance snapshot、authorization、intent、attempt、verification、reconciliation 和 completion evidence；
- tenant scope、append-only evidence、deterministic hash 和精确 source/target release binding；
- 只有通过 M5-A 门禁后才允许设计 V33。

### M5-C — Immutable plan and approval gates

- 从 detect-only assessment 创建计划；
- revalidation、activity mapping、seal、approve、reject 和 cancel；
- sealed plan 不允许静默修改实例、目标 release 或 mapping。

### M5-D — Server executor and reconciliation

- 默认关闭的服务端 executor；
- lease、CAS、bounded batch、canary、pause 和 kill switch；
- 外部引擎调用不放入长数据库事务；
- UNKNOWN 结果不得盲目重试，必须 verification/reconciliation；
- 不提供伪事务 rollback。

### M5-E — Operations API and UI

- 先提供只读计划、attempt、verification 和 reconciliation 视图；
- 后续操作只创建受治理命令，不允许浏览器直接修改执行证据；
- Mobile 默认只读。

### M5-F — Fault, security and observability

- 数据库、审计、引擎、超时、崩溃、lease、重复命令和并发故障注入；
- tenant/operator/permission spoofing 与跨租户边界；
- 指标只使用闭集低基数标签。

### M5-G — Formal acceptance

- 正式治理文档、永久 workflow、artifact/digest、测试矩阵和冻结证据；
- 未得到明确指令前 PR 保持 Open + Draft。

## M6 Ecosystem and AI — planned

M6 继承原始 Roadmap 中 Ecosystem and AI 的产品方向，并在 M5 之后单独启动。

### M6-A — Connector foundation

- 钉钉、飞书连接器；
- 组织、身份、消息、第三方待办和业务回调；
- 凭据管理、签名、限流、重试、死信和 provider evidence；
- 连接器不能改变审批核心语义。

### M6-B — SDK and event ecosystem

- Java 和 TypeScript SDK；
- 版本化 API contracts、签名 Webhook、事件订阅和幂等；
- RuoYi-Vue-Plus 5.x/6.x 及其他宿主适配；
- 兼容性矩阵和弃用策略。

### M6-C — Template and component ecosystem

- 流程模板中心、模板版本和分类；
- 受控导入导出、依赖检查和租户本地重绑定；
- 第三方表单组件注册、白名单属性和安全 fallback；
- 模板或组件不能携带脚本、动态模块或可信权限。

### M6-D — AI foundation

- AI Provider SPI；
- 模型、Prompt、知识来源和策略版本；
- 数据最小化、脱敏、字段权限和 tenant isolation；
- 成本、延迟、超时、降级、禁用和 fallback；
- 输入、输出和人工决策的可追踪审计。

### M6-E — AI approval assistance

- 材料完整性检查；
- 审批内容摘要；
- 风险信号与相似案例检索；
- 审批意见和下一步建议；
- 输出必须标识来源、置信度、限制和需要人工核对的内容。

### M6-F — Controlled automation and AI governance

- 默认只建议、不自动改变流程状态；
- 高风险动作必须人工确认、重新鉴权、幂等和审计；
- 禁止 AI 伪造操作者、权限、审批结论或事实；
- prompt injection、敏感数据泄露、越权检索和模型不可用测试；
- AI 指标保持低基数。

### M6-G — Formal acceptance

- 连接器、SDK、模板和 AI 能力分别建立测试矩阵与治理记录；
- 记录模型/Prompt/知识版本和已知限制；
- 完成永久 workflow 和正式验收后才允许合并。

## Permanent roadmap boundaries

- 阶段规划不覆盖已冻结的历史验收事实；
- M5 继续由 Issue #56 / PR #58 承载，不重新编号或迁移到其他 PR；
- M6 不得提前混入 M5 PR；
- detect-only assessment 不得描述为 migration execution；
- AI 建议不等于审批决定；
- 生产代码不查询或修改 Flowable `ACT_*` 表；
- 客户端不能制造 tenant、operator、authority、audit、worker、lease 或 engine identity；
- Issues #13 和 #14 保持 Open，除非维护者明确决定。
