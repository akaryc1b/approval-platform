# Current Capability Status

> 此文件由 `scripts/generate-capability-status.mjs` 根据 `config/capabilities.json` 和仓库迁移树生成。不要手工编辑。

## 总体状态

| 项目维度 | 当前结论 |
| --- | --- |
| Release | `UNRELEASED` |
| Production Readiness | `BLOCKED` |
| Production Support | `NOT_DECLARED` |
| Effective Flyway | `V50`（50 个连续版本） |

当前代码、测试和验收事实不能自动推导出 Release 或 Production Support。所有发布和生产支持声明都必须经过独立、显式、可审计的决策。

## 状态语义

- **implemented**：实现存在于所述范围内，并能够参与仓库构建。
- **tested**：仓库测试在已提交的代码头上覆盖所述范围。
- **accepted**：不可变验收记录明确接受所述范围。
- **merged**：已接受的实现存在于默认分支。
- **released**：能力已进入真实 Git tag、GitHub Release 和发布清单。
- **productionSupported**：项目明确承诺对所述范围提供可运维的生产支持。

## 能力矩阵

| 能力 | 范围 | Implemented | Tested | Accepted | Merged | Released | Production Supported | Evidence | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 审批平台核心 | DSL、表单、任务协作、审计、SLA、发布生命周期与多端交互 | 是 | 是 | 是 | 是 | 否 | 否 | [M4_FINAL_ACCEPTANCE](../M4_FINAL_ACCEPTANCE.md)<br>[M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE](../M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md) | 核心能力已进入主线；仓库尚无正式 Release，也没有生产支持承诺。 |
| PostgreSQL 16 | 平台持久化、Flyway、Flowable、锁、CAS、并发与恢复语义 | 是 | 是 | 是 | 是 | 否 | 否 | [M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE](../M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md)<br>[M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE](../m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md) | 当前主线的已验收参考数据库；未形成发布级生产支持承诺。 |
| MySQL 8.4 | 双数据库迁移、JDBC、Flowable、并发、故障与运维兼容 | 部分 | 部分 | 否 | 否 | 否 | 否 | [M6_OVERALL_FORMAL_ACCEPTANCE](../m6/M6_OVERALL_FORMAL_ACCEPTANCE.md) | 兼容工作在独立未合并工作流推进；main 仍按 PostgreSQL-only 已验收范围解释。 |
| 运行实例迁移 | 计划、授权、执行证据、精确验证、UNKNOWN、reconciliation 与聚合 | 是 | 是 | 是 | 是 | 否 | 否 | [M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE](../M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md) | 按已记录限制完成验收并进入主线；真实生产迁移执行仍为 NOT_AUTHORIZED。 |
| DingTalk Connector | 凭据、Token、租户路由、只读调用、传输与诊断 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_A_FINAL_ACCEPTANCE](../m6/M6_A_FINAL_ACCEPTANCE.md) | 限定范围已验收；生产集成、运行所有权与推广门禁仍未完成。 |
| Java / TypeScript SDK 与事件契约 | 版本化契约、签名、replay、幂等、兼容与弃用边界 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_B_FINAL_ACCEPTANCE](../m6/M6_B_FINAL_ACCEPTANCE.md) | 契约已验收；持久订阅、投递 Worker、Broker 与运行恢复尚不属于已支持生产范围。 |
| 模板与组件生态 | 确定性模板包、受控导入预览、租户 DRAFT 与数据型组件描述 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_C_FORMAL_ACCEPTANCE](../m6/M6_C_FORMAL_ACCEPTANCE.md) | 限定的数据型能力已验收；不存在脚本、远程模块或直接发布执行权限。 |
| AI Provider Foundation | Provider SPI、版本追踪、最小化、脱敏、超时、熔断、限流与审计 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_D_FORMAL_ACCEPTANCE](../m6/M6_D_FORMAL_ACCEPTANCE.md) | 基础边界已验收；AI 不能制造租户、权限、操作者或命令权限。 |
| OpenAI Responses Provider | 安全 Secret 来源、编码解码、Sender、单次调用与 UNKNOWN 分类 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_E_P7_FINAL_ACCEPTANCE](../m6/M6_E_P7_FINAL_ACCEPTANCE.md) | 真实适配器已按默认关闭和限定调用范围验收；客户生产授权、出口、值守和发布演练未完成。 |
| AI 审批辅助 | 显式生成、摘要与风险建议、精确来源、持久证据和多端展示 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_E_P7_FINAL_ACCEPTANCE](../m6/M6_E_P7_FINAL_ACCEPTANCE.md) | 输出始终为 ADVISORY / UNVERIFIED_ADVISORY，并要求人工复核。 |
| 受控自动化治理 | 非可执行提案、重新校验、显式确认、CAS lineage 与只读治理视图 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_F_FORMAL_ACCEPTANCE](../m6/M6_F_FORMAL_ACCEPTANCE.md) | 当前只接受 NON_EXECUTABLE 能力；Action whitelist 为空，生产重新认证不可用。 |
| PC / H5 / 微信小程序语义一致性 | 审批核心、迁移只读诊断、AI advisory 与显式操作边界 | 是 | 是 | 是 | 是 | 否 | 否 | [M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE](../m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md) | 已验收范围内语义一致；这不等于浏览器兼容性、无障碍和真实用户体验已完成。 |
| 安全与供应链证据 | 依赖图、SBOM、Semgrep、OSV、Workflow 供应链与整改证据 | 是 | 是 | 部分 | 是 | 否 | 否 | [M6_PR_E_E3_R2B_FINAL_ACCEPTANCE](../m6/M6_PR_E_E3_R2B_FINAL_ACCEPTANCE.md)<br>[M6_OVERALL_FORMAL_ACCEPTANCE](../m6/M6_OVERALL_FORMAL_ACCEPTANCE.md) | 仓库内安全边界与整改证据已合并；专用 GitHub 安全告警清单和完整适用性证明仍是生产阻塞项。 |

## Flyway 组合拓扑

生成器递归读取所有 SQL 与 Java migration，而不是只扫描单一目录。当前有效位置：

- `server-modules/approval-persistence-jdbc/src/main/resources/db/migration`
- `server-modules/approval-persistence-jdbc/src/main/java/db/migration`
- `server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration`

发现的版本必须从 V1 连续至 V50，每个版本只能有一个权威实现。

## 维护规则

1. 修改能力事实旽，更新 `config/capabilities.json` 并重新运行生成器。
2. 历史 SHA、Run、PR 和 Artifact 身份只进入不可变 Acceptance 或 Release 文档。
3. `Released` 只能来自真实 tag、GitHub Release、manifest 和制品摘要。
4. `Production Supported` 不能由测试通过、验收通过或合并自动推导。
