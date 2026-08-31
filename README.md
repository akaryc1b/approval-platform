# Approval Platform

[中文](#项目定位) | [English](#english-summary)

面向独立部署的通用审批与流程协作平台。平台以 Approval DSL、Form Schema 和平台自有治理证据为产品模型，通过正式 Engine SPI 使用 Flowable 作为流程执行内核，并提供 Web、H5、微信小程序、SDK、Connector 和受治理 AI 扩展边界。

> **状态说明**：README 不再维护里程碑、数据库版本或逐项能力状态。请以机器生成的 [`Current Capability Status`](docs/current/capability-status.md) 为当前事实入口。代码已存在、测试通过、正式验收、合并、发布和生产支持是六个不同状态，不能相互自动推导。

## 项目定位

Approval Platform 不是业务系统中的简单审批组件，也不是 Flowable API 或内部表的直接封装。它提供独立的审批产品层、运行时治理、审计证据和多端交互，并通过 REST、Webhook、SDK 与连接器接入宿主系统。

核心原则：

- 业务系统不直接依赖 Flowable API；
- 生产代码不查询或修改 Flowable `ACT_*` 内部表；
- Approval DSL 是产品流程模型，BPMN/DMN 是确定性编译产物；
- Form Schema 与 UI Schema 独立于具体前端组件；
- 身份、租户、权限、运行时绑定和审计证据由服务端权威产生；
- 浏览器、Mobile、SDK、Connector 和 AI 输入不能制造可信执行上下文；
- 外部连接器、引擎和 AI Provider 调用不被伪装成平台数据库原子事务；
- 历史版本、执行尝试、UNKNOWN、reconciliation、审计链和验收记录保留可追踪证据；
- AI 建议不等于审批决定，Provider 不得直接调用审批命令。

## 技术基线

具体版本和兼容结论由 [`Current Compatibility`](docs/current/compatibility.md) 生成并维护。主要技术栈包括：

- Java、Spring Boot、Flowable 和 Maven 多模块；
- PostgreSQL、Flyway 和平台自有 JDBC 持久化；
- Vue 3、Vben Admin、Element Plus；
- UniApp Vue 3、Unibest、Wot UI；
- pnpm workspace、Java/TypeScript SDK 与连接器模块。

## 本地 Product Alpha

从受支持的本地环境启动可操作演示：

```bash
pnpm demo:quickstart
```

命令、前置条件、证据和明确非声明见 [`10-Minute Quick Start`](docs/product-readiness/QUICK_START.md)。该入口不是 Release 或生产部署说明。

## 文档入口

| 需要了解的内容 | 权威入口 |
| --- | --- |
| 本地 10 分钟 Quick Start | [`docs/product-readiness/QUICK_START.md`](docs/product-readiness/QUICK_START.md) |
| 产品可用性证据 | [`docs/product-readiness/README.md`](docs/product-readiness/README.md) |
| 当前能力状态 | [`docs/current/capability-status.md`](docs/current/capability-status.md) |
| 当前架构 | [`docs/current/architecture.md`](docs/current/architecture.md) |
| 当前运维边界 | [`docs/current/operations.md`](docs/current/operations.md) |
| 当前兼容性 | [`docs/current/compatibility.md`](docs/current/compatibility.md) |
| 历史验收证据 | [`docs/acceptance/README.md`](docs/acceptance/README.md) |
| 发布快照 | [`docs/releases/README.md`](docs/releases/README.md) |
| 稳定协议与参考 | [`docs/reference/README.md`](docs/reference/README.md) |
| 未来路线 | [`docs/ROADMAP.md`](docs/ROADMAP.md) |
| 完整索引 | [`docs/README.md`](docs/README.md) |

## 使用与部署边界

默认分支不是 Release。任何部署前都应先确认：

1. 所需能力在 Capability Status 中的精确状态；
2. 目标数据库、运行时和客户端组合在 Compatibility 中明确列出；
3. 对应 Release 存在真实 tag、GitHub Release、manifest 和制品摘要；
4. Operations 中的备份、恢复、身份、权限、默认关闭项和 incident 边界已经满足；
5. Production Supported 已被显式声明，而不是从测试或验收结果推断。

## English Summary

Approval Platform is an independently deployable approval and workflow collaboration platform built around a product-owned Approval DSL, Form Schema, immutable governance evidence, and a formal Flowable Engine SPI.

For the bounded local Product Alpha entry path, see the [10-Minute Quick Start](docs/product-readiness/QUICK_START.md). It is not a Release or production-deployment guide.

README is not the authority for milestone or capability status. Use the generated [Current Capability Status](docs/current/capability-status.md), [Current Compatibility](docs/current/compatibility.md), release snapshots, and immutable acceptance records. Implemented, tested, accepted, merged, released, and production-supported are intentionally separate states.

## License

Apache License 2.0
