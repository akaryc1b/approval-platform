# Current Compatibility

> 此文件由 `scripts/generate-capability-status.mjs` 生成。不要手工编辑。

本文件描述默认分支当前已合并代码的兼容边界。它不是 Release 快照，也不构成生产支持承诺。

## Runtime baseline

| Area | Value | Status |
| --- | --- | --- |
| Java | 21 | 必需的构建与运行时基线 |
| Spring Boot | 4.0.2 | 当前服务端框架基线 |
| Flowable | 8.0.0 | 仅通过平台 Engine SPI 和公开 API 使用 |
| Node.js | ^22.18.0 \|\| ^24.0.0 | 仓库客户端与工具基线 |
| pnpm | 10.33.4 | 工作区包管理器基线 |
| PostgreSQL | 16 | main 上的已验收参考数据库 |
| MySQL | 8.4 | 兼容目标；main 尚未合并，也不支持生产 |
| PC client | Vue 3 + Vben + Element Plus | 已验证类型检查和生产构建 |
| Mobile client | UniApp Vue 3 + Unibest + Wot UI | 已验证类型检查、H5 和微信小程序构建 |

## Database support

| Database | Tested | Accepted | Merged | Production Supported | Boundary |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL 16 | 是 | 是 | 是 | 否 | 当前主线的已验收参考数据库；未形成发布级生产支持承诺。 |
| MySQL 8.4 | 部分 | 否 | 否 | 否 | 兼容工作在独立未合并工作流推进；main 仍按 PostgreSQL-only 已验收范围解释。 |

数据库目标、局部测试通过和独立 Draft 工作流都不等于默认分支已支持。生产支持必须同时满足合并、Release、运维和支持政策。

## Flyway compatibility

组合迁移路径连续至 `V50`。生成器同时识别 SQL、Java migration 和附加资源位置。关键跨位置版本如下：

| Version | Type | Governed path |
| --- | --- | --- |
| V38 | JAVA | `server-modules/approval-persistence-jdbc/src/main/java/db/migration/V38__Create_immutable_process_migration_plans.java` |
| V49 | SQL | `server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V49__create_ai_approval_assistance_durable_evidence.sql` |
| V50 | SQL | `server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration/V50__create_ai_controlled_automation_lineage.sql` |

Flyway migration 一经合并或应用不得重写。备份与恢复必须保持平台表和 Flowable 表处于同一恢复点。

## Protocol compatibility

| Protocol | Version | Rule |
| --- | --- | --- |
| Approval DSL | 1.0 | 已发布定义保留精确且不可变的 DSL 与编译器证据。 |
| Form Schema | 1.0 | 已发布表单版本保留精确 Schema 语义和哈希。 |
| UI Schema | 1.0 | 只有具备确定性哈希和安全 fallback 的增量变更才可兼容。 |
| Artifact transfer | V1 envelopes | 未知版本或未知字段必须 fail closed。 |

## Permanent boundaries

- 生产代码不得查询或修改 Flowable `ACT_*` 内部表。
- 浏览器、Mobile、SDK、Connector 或 AI payload 不能制造可信 tenant、operator、permission、audit、worker、lease、credential 或 command authority。
- AI 建议不等于审批决定；Provider 不能直接调用命令。
- 未列为 Production Supported 的组合必须 fail closed，不得从“已有代码”推断为可生产部署。
