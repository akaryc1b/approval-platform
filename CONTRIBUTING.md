# Contributing

## 开发原则

1. 先定义领域语义，再实现 Flowable、HTTP 或 UI 适配。
2. 不允许业务模块直接访问 Flowable `ACT_*` 表。
3. 不允许 `approval-domain` 依赖 Spring、Flowable、RuoYi 或具体数据库。
4. 新增审批操作必须同时补充：权限、审计事件、幂等策略、并发策略和组合测试。
5. 修改 Approval DSL 或 Form Schema 时必须提供版本迁移方案与兼容性测试。
6. 通用功能进入公开核心；公司、客户和行业专用逻辑进入外部扩展仓库。

## 开发环境

- Java 21
- Maven 3.9.6 或更高版本
- Node.js 22.18 或 24
- pnpm 10
- Docker（运行 PostgreSQL、Redis 和容器集成测试时需要）

本地依赖可以通过以下命令启动：

```bash
docker compose -f deploy/compose/docker-compose.yml up -d
```

## 分支与 Pull Request

- 分支使用 `feat/`、`fix/`、`docs/`、`refactor/` 或 `agent/` 等清晰前缀。
- 一个 Pull Request 尽量形成完整、可独立合并的纵向闭环。
- Gate 是检查项，不是自动创建新分支、新 Pull Request 或新验收文档的理由。
- 禁止仅为占位或 bootstrap 文档长期创建 Draft Pull Request。
- 被后续方案替代的 Pull Request 应及时关闭并标记 `superseded-by: #<number>`。
- 同一里程碑最多保留两个活跃功能 Pull Request，另加一个维护或紧急修复 Pull Request；Draft 也计入限制。
- 禁止提交密钥、真实用户数据和客户专有信息。

## 变更风险分级

提交 Pull Request 时必须选择风险等级，并遵循 [Change Governance](docs/current/CHANGE_GOVERNANCE.md)：

- **L1 普通：** 文档、文案、测试整理、无行为变化重构、小型依赖或工具升级。要求 Pull Request、受影响检查和正常 Review。
- **L2 重要：** 数据库迁移、公开契约、权限、Connector、持久化语义或 CI 行为。要求影响/兼容性说明、针对性单元与集成测试、回滚说明；只有长期架构选择才需要 ADR。
- **L3 高风险：** 生产实例迁移、数据库切换、租户/认证边界、AI 自动业务动作、审计重放、不可逆数据或供应链信任边界。要求 Formal Gate、Exact Head、不可变证据、恢复演练和合并后 `main` 验证。

模块名称不能替代风险判断。作者提出等级，Reviewer 可以升级；L3 降级必须在 Pull Request 中说明理由。

## 质量门禁

验证范围应与风险相匹配：

- **L1：** 执行格式检查、编译检查和受影响模块的单元测试。
- **L2：** 在 L1 基础上执行相关集成、兼容、迁移或契约测试，并保留一次最终 Required CI 结果。
- **L3：** 执行完整 Required CI、恢复/回滚验证及正式验收要求。

完整本地验证命令如下，适用于 L3、发布候选或 Reviewer 明确要求的场景：

```bash
mvn verify
pnpm install
pnpm check
pnpm build:packages
```

`mvn verify` 会执行：

- Java 和 Maven 工具链检查；
- Checkstyle 静态检查；
- 单元测试；
- ArchUnit 模块边界测试；
- JaCoCo 覆盖率报告；
- Docker 可用时的 PostgreSQL Testcontainers 测试。

本机没有 Docker 时，PostgreSQL 容器测试会自动跳过，其他质量门禁仍然执行。涉及流程状态变化的修改必须包含集成测试；涉及并发或自动审批的修改必须包含幂等和竞争条件测试。
