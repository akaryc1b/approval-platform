# Product Readiness / 产品可用性

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

本目录回答“用户能否启动并完成工作”，而不只是“代码能否编译”。默认分支已经包含本地 Quick Start、完整采购到付款沙箱路径，以及 PC/H5 的浏览器与基础无障碍基线。容量与恢复仍在 PR #142 的独立候选分支推进；精确 commit、Workflow Run 和 Artifact 摘要保留在 PR/Issue 验收记录中。

## 当前产品结果

| 产品结果 | 状态 | 含义 |
| --- | --- | --- |
| Repository / workstation preflight | `IMPLEMENTED` | 只读检查工具和仓库约束 |
| One-command backend | `IMPLEMENTED_AND_RUNTIME_VERIFIED` | PostgreSQL、Redis、Spring Boot、Flowable 与确定性 Seed 生命周期 |
| 10-minute PC/H5 Quick Start | `MERGED_MEASURED_LOCAL_ALPHA_ACCEPTED` | `pnpm demo:quickstart` 已通过两次独立清洁运行 |
| Purchase-to-payment golden path | `MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED` | PC/H5 可见操作、实例完成、Outbox、本地签名付款沙箱 503/恢复和单次副作用已验证 |
| PC/H5 browser and accessibility baseline | `MERGED_BOUNDED_BASELINE_ACCEPTED` | Chromium、Firefox 与 Playwright WebKit 的受限关键页面和基础无障碍检查 |
| WeChat DevTools / physical device | `NOT_VERIFIED` | 构建成功和 H5 surrogate 不等于真实微信运行时 |
| Public online evaluation sandbox | `PLANNED_NOT_AVAILABLE` | 建设方案已记录；当前没有公共 URL |
| Capacity profile matrix | `THREE_LOCAL_REFERENCE_PROFILES_PRIOR_HEAD_PASSED_REVALIDATION_PENDING` | Small、Standard 和 Large 的配置点已在上一精确 Head 成功；本次代码变更后必须由新 Head 重新验证，仍不是生产容量 |
| Original-volume Outbox drain | `PRIOR_HEAD_AUDITED_REVALIDATION_PENDING` | `ded87e5` 的原始 96 条积压与精确白名单已通过运行及制品核验，观测排空 9,672.929 ms；后续基线修复提交仍须重新验证 |
| Upgrade, backup/restore and local RPO/RTO | `PRIOR_HEAD_AUDITED_REVALIDATION_PENDING` | `ded87e5` 的不同版本在途恢复、业务摘要一致和继续付款已核验；停机后至首个业务读取 16,721 ms，仅为本地静默单节点证据；主线推送基线修复待新 Head 验证 |
| Release and production deployment | `NOT_CREATED` | 默认分支不是 Release，Production Support 未声明 |


已审计的运行：`ded87e5` / [Run 33943489013](https://github.com/akaryc1b/approval-platform/actions/runs/33943489013)。其 8 个证据包、99 个文件条目的长度和摘要一致；原始 96 条事件、精确授权账本以及恢复前后业务摘要已交叉比较。具体配置点、测量口径和限制见 [Capacity and Recovery Operating Envelope](CAPACITY_RECOVERY_ENVELOPE.md#candidate-status)。后续修复使主线推送使用事件 `before/after`，避免把同一提交的备份恢复误称为升级；旧运行不替代新提交验收。

## 从这里开始

- [10-Minute Quick Start](QUICK_START.md)
- [Purchase-Payment Golden Path](PURCHASE_PAYMENT_GOLDEN_PATH.md)
- [Local Demo User Guide](USER_GUIDE.md)
- [Local Demo Administrator Guide](ADMIN_GUIDE.md)
- [Local Demo Operator Guide](OPERATOR_GUIDE.md)
- [PC/H5 Browser and Accessibility Matrix](BROWSER_ACCESSIBILITY_MATRIX.md)
- [Capacity and Recovery Operating Envelope](CAPACITY_RECOVERY_ENVELOPE.md)
- [Online Evaluation Sandbox](ONLINE_DEMO.md)
- [Cross-Client Local Demo](CROSS_CLIENT_LOCAL_DEMO.md)
- [PC/H5 Runtime Smoke](PC_H5_RUNTIME_SMOKE.md)

## 已接受的本地 Product Alpha

### 10 分钟 Quick Start

```bash
pnpm demo:quickstart
```

该命令启动真实 PostgreSQL/Redis、Spring Boot/Flowable、确定性采购付款 Seed、PC 与 H5，并验证同一任务在两个客户端可见。

```text
QUICK_START_10_MINUTES_PASSED
DEMO_BACKEND_READY_PASSED
PC_DEMO_READY_PASSED
H5_DEMO_READY_PASSED
TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED
```

### 采购到付款沙箱路径

```bash
pnpm demo:runtime:purchase-payment:e2e
```

该命令通过真实 PC/H5 页面完成经理审批、财务复核、两人会签和付款确认，然后验证事务 Outbox、签名本地付款沙箱、HTTP 503 后恢复、消息投递和幂等副作用。

```text
PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_PASSED
H5_PAYMENT_CONFIRMATION_PASSED
PURCHASE_APPROVAL_E2E_PASSED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
OUTBOX_RETRY_AND_IDEMPOTENCY_PASSED
TWO_CONSECUTIVE_CLEAN_RUNS_PASSED
```

### 浏览器与基础无障碍基线

```bash
pnpm demo:runtime:browser-accessibility
```

当前受限矩阵覆盖 system Chromium、Playwright Firefox 和 Playwright WebKit，验证 PC/H5 关键页面、中文字符、程序化名称、目标对比度和一条认证后的 PC 键盘任务路径。

```text
PC_H5_CHROMIUM_COMPATIBILITY_BASELINE_PASSED
PC_H5_FIREFOX_COMPATIBILITY_SMOKE_PASSED
PC_H5_WEBKIT_ENGINE_COMPATIBILITY_SMOKE_PASSED
PC_AUTHENTICATED_KEYBOARD_TASK_FLOW_PASSED
BASELINE_AUTOMATED_ACCESSIBILITY_PASSED
PC_H5_CJK_RENDERING_MATRIX_PASSED
BROWSER_ACCESSIBILITY_MATRIX_PUBLISHED
```

## 组件命令作用域标记

仓库保留更窄组件命令的声明词汇。只读场景校验、Seed 集成和单独后端命令不会启动完整 PC/H5 产品路径，因此它们会正确输出以下作用域标记：

```text
DEMO_BACKEND_ONE_COMMAND_IMPLEMENTED
PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

这些是**命令作用域声明**，不是全局产品状态。静态校验器或较窄命令输出 `_NOT_EXECUTED`，不会覆盖另一个已授权运行时已经接受的 Quick Start、采购到付款或浏览器基线，也不会自动释放容量候选声明。

## 容量与恢复候选

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
```

同一命令依次执行：

```text
Small Demo
→ Standard Deployment
→ Large Tenant
→ 原始 96 条 Outbox / Connector backlog drain
→ 容量矩阵完整清理
→ 精确 base 到 candidate 的在途 PostgreSQL 备份恢复演练
```

三个 Profile 都只表示：

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

批量排空候选只表示：

```text
OUTBOX_CONNECTOR_BACKLOG_DRAIN_LOCAL_CONFIGURED_VOLUME_PASSED
LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO
```

批量排空复用现有 Generic REST Connector、Outbox Dispatcher 和签名付款沙箱，直接接收 Standard/Large 创建的原始 96 个已完成实例和积压，不再重置数据库或创建另一批流程。在沙箱不可用时验证全部目标事件收到 HTTP 503 并保持 PENDING，然后发布逐事件精确白名单并使用现有 control file 恢复。恢复后核对全部 DELIVERED、原始身份映射、96 个精确已接受付款结果，以及五次稳定观察。

白名单不使用业务键前缀或通配符。排空子阶段只清理自己的后端，数据卷由外层容量矩阵在 `finally` 中统一清理；子阶段成功不能代替最终清理成功。首条延迟、完整排空耗时和 P50/P95/P99 是单调时钟下的轮询观测，不是生产 RTO。

这些候选结论必须绑定新的 Exact-Head Workflow 与保留 Artifact。实现存在或控制台出现 Marker，不等于已经接受。

## 已实现、等待本次 Head 验证的恢复演练

PR #142 中的演练已经接入同一命令：

```text
精确 PR base 启动真实在途审批
→ PostgreSQL quiesced backup
→ 一次性环境重建
→ candidate 启动恢复的数据
→ 恢复前后业务摘要一致
→ 继续审批并通过精确单事件白名单完成付款
→ 实测本地 RPO/RTO
→ 清理备份、worktree、进程和数据卷
```

当前候选仍需要自然 CI 和制品审计，不得把前一提交的成功转移到本次代码。该结果仍不能扩展为崩溃一致、多节点或生产 RPO/RTO。

## 可执行命令

```bash
pnpm demo:preflight
pnpm demo:quickstart:plan
pnpm demo:quickstart
pnpm demo:quickstart:check
pnpm demo:runtime:purchase-payment:e2e
pnpm demo:runtime:browser-accessibility
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
```

这些命令是本地 Product Alpha 或受控候选入口，不是生产部署命令。

## 证据位置

```text
.runtime/quick-start/<run-id>/
.runtime/purchase-payment-e2e/<run-id>/
.runtime/browser-accessibility/<run-id>/
.runtime/capacity-recovery/<run-id>/
```

证据包含源码树身份、环境、业务标识、截图、Playwright trace、数据库/进程观察、Outbox/沙箱状态和清理结果。GitHub Actions 中的受控 JSON 通过现有 Artifact envelope 留存，并带文件大小与 SHA-256；`.runtime/` 始终保持未跟踪。原始积压交接、精确白名单内容和摘要、逐事件观测也通过这一制品机制留存。

## 在线评估环境

当前没有公共在线地址。[Online Evaluation Sandbox](ONLINE_DEMO.md) 定义了邀请制起步、会话/租户隔离、自动重置、限流、附件限制、外部出口禁用、监控和容量门槛。只有这些门槛通过后，README 才会发布真实 URL。

## 安全边界

Product Readiness 工作不得：

- 创建第二套业务后端、数据库模型、Seed、Outbox、Connector 或付款沙箱；
- 写平台业务表或 Flowable `ACT_*` 表来推进流程；
- 使用浏览器提供的可信权限绕过服务端身份；
- 使用固定等待、吞掉异常或无界重试；
- 发布未经测量的容量、TPS、RPO 或 RTO；
- 把 H5 surrogate、本地沙箱、本地 Profile 或默认分支描述成真实微信、真实支付、Release 或 Production Support；
- 修改或依赖独立的 MySQL 8.4 候选分支。

## 当前非声明

```text
WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
FULL_BROWSER_COMPATIBILITY_NOT_VERIFIED
SAFARI_BROWSER_NOT_VERIFIED
IOS_SAFARI_NOT_VERIFIED
ANDROID_CHROME_NOT_VERIFIED
WECHAT_WEBVIEW_NOT_VERIFIED
FULL_WCAG_CONFORMANCE_NOT_VERIFIED
SCREEN_READER_MANUAL_TEST_NOT_VERIFIED
PRODUCTION_CAPACITY_NOT_VERIFIED
MAXIMUM_STABLE_ENVELOPE_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
OUTBOX_CONNECTOR_BACKLOG_DRAIN_EXACT_HEAD_EVIDENCE_PENDING
PRODUCTION_OUTBOX_DRAIN_RATE_NOT_VERIFIED
MULTI_NODE_OUTBOX_DRAIN_NOT_VERIFIED
UPGRADE_RESTORE_EXACT_HEAD_EVIDENCE_PENDING
PRODUCTION_RPO_NOT_VERIFIED
PRODUCTION_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
ONLINE_DEMO_NOT_AVAILABLE
RELEASE_NOT_CREATED
```
