# Product Readiness / 产品可用性

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

本目录回答的是“用户能否启动并完成工作”，而不只是“代码能否编译”。当前默认分支已经包含可测量的本地 Quick Start、完整采购到付款沙箱路径，以及 PC/H5 的浏览器与基础无障碍基线。容量与恢复测量仍在独立候选分支推进，只有精确 Head 的运行证据通过后才能发布对应结论。

精确 commit、Workflow Run 和 Artifact 摘要保留在不可变 PR/Issue 验收记录中；这个 living 索引只维护当前结论、候选状态和入口。

## 当前产品结果

| 产品结果 | 状态 | 含义 |
| --- | --- | --- |
| Repository / workstation preflight | `IMPLEMENTED` | 只读检查工具和仓库约束 |
| One-command backend | `IMPLEMENTED_AND_RUNTIME_VERIFIED` | PostgreSQL、Redis、Spring Boot、Flowable 与确定性 Seed 生命周期 |
| 10-minute PC/H5 Quick Start | `MERGED_MEASURED_LOCAL_ALPHA_ACCEPTED` | `pnpm demo:quickstart` 已通过同一源码树上的两次独立清洁运行 |
| Purchase-to-payment golden path | `MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED` | PC/H5 可见操作、实例完成、Outbox、本地签名付款沙箱 503/恢复和单次副作用已验证 |
| PC/H5 browser and accessibility baseline | `MERGED_BOUNDED_BASELINE_ACCEPTED` | Chromium、Firefox 与 Playwright WebKit 的受限关键页面和键盘/程序化名称检查 |
| WeChat DevTools / physical device | `NOT_VERIFIED` | 构建成功和 H5 mobile surrogate 不等于真实微信运行时 |
| Public online evaluation sandbox | `PLANNED_NOT_AVAILABLE` | 建设方案已记录；当前没有公共 URL |
| Capacity and recovery envelope | `THREE_LOCAL_REFERENCE_PROFILES_IMPLEMENTED_EVIDENCE_GATED` | Small Demo 的既有结论只绑定其历史证据；Standard Deployment 与 Large Tenant 已实现为本地参考候选，当前精确 Head 证据仍待通过，不属于默认分支 Current |
| Upgrade, backup/restore and RPO/RTO | `NOT_REHEARSED` | 运维文档、迁移历史或单次 Outbox 恢复不能替代真实演练 |
| Release and production deployment | `NOT_CREATED` | 默认分支不是 Release，Production Support 未声明 |

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

该命令拥有完整的启动、可见性验证、证据和清理生命周期。它启动真实 PostgreSQL/Redis、Spring Boot/Flowable、确定性采购付款 Seed、PC 与 H5，并验证同一任务在两个客户端可见。

已接受声明：

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

已接受声明：

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

已接受声明：

```text
PC_H5_CHROMIUM_COMPATIBILITY_BASELINE_PASSED
PC_H5_FIREFOX_COMPATIBILITY_SMOKE_PASSED
PC_H5_WEBKIT_ENGINE_COMPATIBILITY_SMOKE_PASSED
PC_AUTHENTICATED_KEYBOARD_TASK_FLOW_PASSED
BASELINE_AUTOMATED_ACCESSIBILITY_PASSED
PC_H5_CJK_RENDERING_MATRIX_PASSED
BROWSER_ACCESSIBILITY_MATRIX_PUBLISHED
```

## 容量与恢复候选路径

候选分支提供同一条采购付款真实路径的三个本地参考 Profile：

```text
Small Demo
Standard Deployment
Large Tenant
```

执行入口为：

```bash
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
```

三个 Profile 的实现存在，不等于三个结果已经被接受。Standard Deployment 与 Large Tenant 必须在当前精确 Head 上完成自然 Workflow、阈值检查、证据留存和完整清理，才能发布候选声明。任何成功 Profile 都只能标记为：

```text
PASSED_AT_CONFIGURED_POINT_ONLY
```

这表示配置点通过，不代表最大稳定边界、生产容量或生产规格。当前仍未完成高容量 Outbox/Connector backlog drain、在途升级、备份恢复和实测 RPO/RTO。

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

## 为什么文档中仍会出现 `_NOT_EXECUTED`

仓库保留更窄组件命令的声明词汇。例如，只读场景校验器和 Seed 集成测试不会启动完整 PC/H5 产品路径，因此它们会正确输出：

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

这些是**命令作用域声明**，不是全局产品状态。静态校验器输出 `_NOT_EXECUTED`，不会覆盖另一个已授权运行时已经接受的 Quick Start 或采购到付款结果，也不会自动释放容量候选声明。

## 证据位置

每个运行将证据写入 `.runtime/` 下的独立目录，并保持未跟踪：

```text
.runtime/quick-start/<run-id>/
.runtime/purchase-payment-e2e/<run-id>/
.runtime/browser-accessibility/<run-id>/
.runtime/capacity-recovery/<run-id>/
```

证据包含源码树身份、环境、运行时间、业务标识、截图、Playwright trace、后端与客户端日志、数据库/进程观察、Outbox/沙箱状态和清理结果。living 文档不复制易过期的 SHA、Run ID 或 Artifact digest。

## 在线评估环境

当前没有公共在线地址。[Online Evaluation Sandbox](ONLINE_DEMO.md) 定义了邀请制起步、会话/租户隔离、自动重置、限流、附件限制、外部出口禁用、监控和容量门槛。只有这些门槛通过后，README 才会发布真实 URL。

## 安全边界

Product Readiness 工作不得：

- 创建第二套业务后端、数据库模型、Seed、Outbox、付款沙箱或客户端启动器；
- 写平台业务表或 Flowable `ACT_*` 表来推进流程；
- 使用浏览器提供的可信权限绕过服务端身份；
- 使用 mock readiness、固定等待、吞掉异常或无界重试；
- 发布未经测量的容量、TPS、RPO 或 RTO 数字；
- 把 H5 surrogate、构建、本地沙箱、本地 Profile 或默认分支提交描述成真实微信、真实支付、Release 或 Production Support；
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
OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
ONLINE_DEMO_NOT_AVAILABLE
RELEASE_NOT_CREATED
```
