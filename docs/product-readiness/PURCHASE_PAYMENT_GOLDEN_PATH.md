# Purchase-to-Payment Product Alpha Golden Path

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_STATUS=AVAILABLE
DETERMINISTIC_DEMO_SEED_STATUS=IMPLEMENTED_LOCAL_OPT_IN
BACKEND_PURCHASE_APPROVAL_CHAIN_STATUS=VERIFIED
PURCHASE_APPROVAL_E2E_STATUS=MERGED_LOCAL_ALPHA_ACCEPTED
CROSS_CLIENT_RUNTIME_STATUS=PC_H5_ACCEPTED_WECHAT_TARGET_UNVERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_STATUS=MERGED_LOCAL_ALPHA_ACCEPTED
PRODUCTION_PAYMENT_INTEGRATION_STATUS=NOT_VERIFIED
ONLINE_DEMO_STATUS=NOT_AVAILABLE
```

这是一条可重复执行的本地 Product Alpha 业务路径。它从真实客户端操作开始，完成审批、付款确认、Outbox 事件、签名本地付款沙箱故障恢复和幂等校验。

## 一键运行

从仓库根目录执行：

```bash
pnpm demo:runtime:purchase-payment:e2e
```

运行计划可只读查看：

```bash
pnpm demo:runtime:purchase-payment:e2e:plan
```

静态边界检查：

```bash
pnpm demo:runtime:purchase-payment:e2e:check
```

## 用户看到的流程

```text
确定性 Seed 准备 demo-employee 的高金额采购付款申请
→ PC / demo-manager / managerApproval
→ H5 / demo-finance-reviewer / financeReview
→ H5 / demo-finance-approver-a / financeCountersign
→ H5 / demo-finance-approver-b / financeCountersign
→ H5 surrogate / demo-employee / paymentConfirmation
→ 审批实例 COMPLETED
→ transactional Outbox 产生完成事件
→ 签名本地付款沙箱返回 HTTP 503
→ Outbox 保持 PENDING
→ 下游恢复
→ bounded retry 变为 DELIVERED
→ 重复 dispatch 不再产生新消息
→ 沙箱只接受一次付款副作用
→ 自动清理
```

审批动作通过可见 PC/H5 控件完成；运行器不会直接调用审批 HTTP 接口，也不会写平台表或 Flowable `ACT_*` 表来推进状态。

## 已接受的结果

同一精确源码树上的两次独立清洁运行可以发布：

```text
PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_PASSED
H5_PAYMENT_CONFIRMATION_PASSED
PURCHASE_APPROVAL_E2E_PASSED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
OUTBOX_RETRY_AND_IDEMPOTENCY_PASSED
TWO_CONSECUTIVE_CLEAN_RUNS_PASSED
```

当前默认分支已经包含满足上述规则的 Product Alpha 路径。精确 commit、Run ID、Artifact digest 和证据摘要保留在不可变 PR/Issue 验收记录中，而不是复制到这个 living 文档。

## 移动端验收边界

产品目标中的付款确认客户端仍是微信小程序，但当前接受范围明确是：

```text
targetClient = wechat
acceptanceClient = h5
acceptanceMode = H5_MOBILE_SURROGATE
actor = demo-employee
```

因此：

- H5 付款确认已经验证；
- 微信小程序构建已经存在；
- 微信开发者工具运行、微信 WebView 和物理设备操作仍未验证；
- 文档不能把 H5 surrogate 描述为真实微信验收。

## 确定性业务数据

场景契约：

```text
config/demo/purchase-payment-golden-path.json
```

Seed fixture：

```text
config/demo/purchase-payment-demo-seed.json
```

| 字段 | 值 |
| --- | --- |
| Tenant | `demo-purchase-payment` |
| Business key | `DEMO-PP-0001` |
| Amount | `12500.00` |
| Supplier | `Demo Industrial Supplies Ltd.` |
| Purchase order | `PO-DEMO-2026-0001` |
| Attachments | 两个固定、非敏感演示附件 |

`12500.00` 会触发高于 `10000.00` 的财务复核与两人会签路径。

## 演示角色

| Identity | 作用 |
| --- | --- |
| `demo-admin` | 配置、发布、部署、激活并检查场景 |
| `demo-employee` | 发起申请、完成付款确认并读取参与者时间线 |
| `demo-manager` | 完成经理审批 |
| `demo-finance-reviewer` | 完成高金额财务复核 |
| `demo-finance-approver-a` | 第一位财务会签人 |
| `demo-finance-approver-b` | 第二位财务会签人 |

这些是固定演示标识，不是生产凭据。

## 运行时组成

该命令复用现有 Product Alpha 生命周期：

- PostgreSQL 16 和 Redis；
- Spring Boot、Flowable 与 Flyway；
- 确定性 Form/Approval Release 发布、部署与激活；
- PC 与 H5 客户端；
- `PurchasePaymentApplicationService`；
- `ApprovalAttachmentService`；
- 事务 Outbox 和 `OutboxDispatcher`；
- `GenericRestBusinessCallbackConnector`；
- HMAC 签名本地付款沙箱；
- Playwright 截图、trace、机器证据和 fail-closed cleanup。

它不会创建第二套后端、Seed、数据库模型、Outbox、Connector 或证据体系。

## 证据

每次运行写入：

```text
.runtime/purchase-payment-e2e/<run-id>/
```

主要证据覆盖：

- source/tree identity；
- 受治理 tenant、business key、process instance 和 task IDs；
- PC/H5 可见审批步骤；
- 最终实例状态和任务历史；
- Outbox 初始、PENDING、DELIVERED 与重复 dispatch；
- 沙箱请求、签名、幂等键和仅一次副作用；
- 截图、Playwright trace、服务日志；
- 容器、进程、volume 和端口清理。

`.runtime/` 不提交到仓库。

## 组件命令与整体产品状态的区别

只读场景契约、Seed 和后端集成测试的范围更窄，它们仍会正确输出：

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

这些 `_NOT_EXECUTED` 标记只说明该**组件命令本身**没有执行完整产品路径，不会覆盖已接受的一键 E2E 结果。

## 当前非声明

```text
WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
PRODUCTION_CAPACITY_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
ONLINE_DEMO_NOT_AVAILABLE
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
RELEASE_NOT_CREATED
```

付款结果来自明确标识的本地签名沙箱，不是银行、ERP、支付机构或其他生产 Provider。该命令也不是生产部署流程。
