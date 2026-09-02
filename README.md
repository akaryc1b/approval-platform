# Approval Platform

**面向企业和团队的通用审批与流程协作平台。**

Approval Platform 把表单、审批流程、待办协作、审计证据和外部系统回调放在一套可独立部署的平台中。业务系统通过 API、SDK 或 Connector 接入，不需要直接依赖 Flowable 内部 API 或表结构。

[10 分钟本地体验](docs/product-readiness/QUICK_START.md) ·
[完整采购付款演示](docs/product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md) ·
[用户指南](docs/product-readiness/USER_GUIDE.md) ·
[在线试用计划](docs/product-readiness/ONLINE_DEMO.md) ·
[当前能力状态](docs/current/capability-status.md)

> **当前阶段：Product Alpha。** 已经可以在本地一键启动，并完成一条真实的采购到付款沙箱流程；当前没有公共在线试用地址。仓库尚未发布正式 Release，也没有声明 Production Support。

## 产品解决什么问题

很多业务系统都需要审批，但通常会重复建设流程设计、表单、待办、会签、撤回、转交、审计和外部回调。Approval Platform 将这些共性能力收敛为独立产品层：

| 场景 | 产品能力 |
| --- | --- |
| 设计申请 | 版本化 Form Schema、UI Schema、表单设计与不可变发布包 |
| 设计流程 | Approval DSL、条件路由、并行分支、会签、驳回与修订 |
| 处理工作 | 待办、已办、发起、审批、撤回、转交和安全取回 |
| 多端协作 | PC、H5 与微信小程序共享服务端权威语义 |
| 系统集成 | SDK、事件契约、Outbox、签名回调和受治理 Connector |
| 治理与审计 | 租户隔离、幂等、审计、SLA、发布生命周期和迁移证据 |
| AI 辅助 | 可追溯的摘要与风险建议；始终需要人工复核，AI 不执行审批命令 |

当前默认分支中的具体实现、测试和验收状态，以机器生成的 [Current Capability Status](docs/current/capability-status.md) 为准。

## 一条可以直接体验的业务流程

仓库内置确定性的高金额采购付款场景：

```text
确定性 Seed 准备由员工发起的采购付款申请
→ PC 经理审批
→ H5 财务复核
→ H5 两人财务会签
→ H5 代替目标微信端完成付款确认
→ 审批实例 COMPLETED
→ 事务 Outbox 产生付款事件
→ 本地签名付款沙箱先返回 HTTP 503
→ 消息保持 PENDING
→ 下游恢复后重试并变为 DELIVERED
→ 仅产生一次已接受的付款副作用
```

这条路径使用真实应用服务、PostgreSQL 16、Redis、Spring Boot、Flowable、PC/H5 页面、Outbox 和 Connector 沙箱。它不代表真实银行或生产支付系统已经接入；目标微信端目前仍由 H5 运行时替代验收。

## 开始体验

### 1. 10 分钟启动 PC 与 H5

准备 Java 21、Maven、Node.js、pnpm、Docker 和 Chrome/Chromium/Edge，然后在仓库根目录执行：

```bash
pnpm demo:quickstart
```

该命令会启动 PostgreSQL、Redis、后端、确定性演示数据、PC 和 H5，并验证同一采购任务在两个客户端可见。按一次 `Ctrl-C` 会执行受控清理。

完整前置条件和操作步骤见 [10-Minute Quick Start](docs/product-readiness/QUICK_START.md)。

### 2. 运行完整采购到付款沙箱流程

```bash
pnpm demo:runtime:purchase-payment:e2e
```

该命令通过可见的 PC/H5 控件完成审批、付款确认、沙箱故障恢复、幂等校验和清理。详情见 [Purchase-Payment Golden Path](docs/product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md)。

### 3. 在线试用

公共在线试用环境尚未上线。计划先交付一个**邀请制、数据可重置、会话隔离、限制外部出口的非生产评估沙箱**，通过验证后再决定是否开放公共访问。

方案、上线门槛和安全边界见 [Online Evaluation Sandbox](docs/product-readiness/ONLINE_DEMO.md)。

## 当前可用程度

| 产品结果 | 当前状态 |
| --- | --- |
| 一键本地 Quick Start | 已合并并通过两次独立的 10 分钟内运行证据 |
| 采购到付款黄金路径 | 已合并；PC/H5 可见操作、Outbox、签名本地付款沙箱和故障恢复已验证 |
| PC/H5 浏览器与基础无障碍 | Chromium、Firefox 和 Playwright WebKit 的受限基线已合并 |
| 微信运行时与真机 | 尚未验证；当前黄金路径使用 H5 mobile surrogate |
| 容量、升级和恢复 | 正在补齐测量与演练，尚不能声明生产容量或 RPO/RTO |
| 在线评估环境 | 已规划，尚无公共 URL |
| Release / Production Support | `UNRELEASED` / `NOT_DECLARED` |

## 文档入口

| 读者需求 | 文档 |
| --- | --- |
| 快速体验 | [Quick Start](docs/product-readiness/QUICK_START.md) |
| 完成采购付款场景 | [Golden Path](docs/product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md) |
| 普通用户操作 | [User Guide](docs/product-readiness/USER_GUIDE.md) |
| 管理员配置 | [Administrator Guide](docs/product-readiness/ADMIN_GUIDE.md) |
| 运行和清理 | [Operator Guide](docs/product-readiness/OPERATOR_GUIDE.md) |
| 在线试用建设 | [Online Demo](docs/product-readiness/ONLINE_DEMO.md) |
| 当前产品能力 | [Capability Status](docs/current/capability-status.md) |
| 当前兼容性 | [Compatibility](docs/current/compatibility.md) |
| 架构与设计 | [Architecture](docs/current/architecture.md) |
| API、Schema 与协议 | [Reference Index](docs/reference/README.md) |
| 完整文档目录 | [Documentation Index](docs/README.md) |

## 技术概览

- Java 21、Spring Boot、Flowable、Maven 多模块；
- PostgreSQL 16、Redis、Flyway、平台自有 JDBC 持久化；
- Vue 3、Vben Admin、Element Plus；
- UniApp Vue 3、Unibest、Wot UI；
- Java / TypeScript SDK、Connector、Outbox 与签名回调；
- 受治理、默认关闭的 AI Provider 与人工复核型审批辅助。

Approval DSL 是产品流程模型，BPMN/DMN 是确定性编译产物。生产代码不直接查询或修改 Flowable `ACT_*` 内部表。

## 当前边界

- PostgreSQL 16 是当前已验收参考数据库；MySQL 8.4 仍是未合并兼容目标。
- 已验证的是本地 Product Alpha 和本地付款沙箱，不是真实生产支付。
- 浏览器与无障碍结果是受限基线，不是完整 WCAG、屏幕阅读器或真实移动设备认证。
- 默认分支不是正式 Release；测试、验收和合并不能自动推导 Production Support。
- 在线试用上线前必须完成数据隔离、重置、限流、外部出口和容量门槛验证。

开发、架构、运维和历史验收细节保留在 `docs/` 中，不再占用 README 的产品入口位置。

## English Summary

Approval Platform is an independently deployable approval and workflow collaboration platform for versioned forms, approval design, task collaboration, audit evidence, SDKs, connectors, and human-reviewed AI assistance.

The current Product Alpha includes a measured local Quick Start and a complete PC/H5 purchase-to-payment sandbox path with Outbox retry and idempotency evidence. A public hosted demo is planned but is not available yet. The repository is unreleased and does not declare production support.

Start with:

```bash
pnpm demo:quickstart
```

Then run the complete local sandbox flow with:

```bash
pnpm demo:runtime:purchase-payment:e2e
```

## License

Apache License 2.0
