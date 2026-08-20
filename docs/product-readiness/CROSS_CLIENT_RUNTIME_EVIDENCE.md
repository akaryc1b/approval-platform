# PC、H5 与微信小程序交互式运行证据

```text
CROSS_CLIENT_RUNTIME_EVIDENCE_STATUS=OBSERVER_IMPLEMENTED_NOT_YET_EXECUTED
AUTOMATED_BROWSER_E2E_STATUS=NOT_EXECUTED
WECHAT_PHYSICAL_DEVICE_STATUS=NOT_VERIFIED
```

本指南在已经合并的确定性 Backend Seed、采购付款审批链、支付 Sandbox 恢复和本地客户端身份桥接之上，增加一个**只读交互式证据观察器**。

观察器不会替用户点击，不会调用审批写接口，也不会制造另一套 Demo 流程。它只观察真实客户端操作之后的服务端状态迁移，并把同一租户、业务单号、流程实例、任务和审计事件写入一个本地 JSON 证据文件。

这条路径用于回答：

```text
PC、H5 与微信小程序是否针对同一个采购付款实例完成了规定角色交接？
```

它不能单独证明：

- 自动化浏览器 E2E 已通过；
- 微信小程序真机或正式 AppID 已验证；
- 浏览器兼容性或无障碍已经验收；
- 有截图或未剪辑录屏；
- 10 分钟 Quick Start 已通过；
- 生产付款集成已验证。

## 1. 查看只读计划

```bash
pnpm demo:runtime:plan
```

机器可读计划必须包含：

```text
CROSS_CLIENT_INTERACTIVE_RUNTIME_OBSERVER_V1
backendCommand=pnpm demo:backend:start
tenantId=demo-purchase-payment
businessKey=DEMO-PP-0001
```

以及以下固定交接：

| 顺序 | 客户端 | Demo 身份 | 任务 |
| ---: | --- | --- | --- |
| 1 | PC | `demo-manager` | `managerApproval` |
| 2 | H5 | `demo-finance-reviewer` | `financeReview` |
| 3 | 微信小程序 | `demo-finance-approver-a` | `financeCountersign` |
| 4 | 微信小程序 | `demo-finance-approver-b` | `financeCountersign` |

计划命令不连接 Backend，也不会创建证据通过结论。

## 2. 从干净的本地 Demo 数据开始

为避免上一次交互已经推进或完成固定业务单号，应先停止 Backend，并显式重置一次可丢弃的本地 Demo 数据：

```bash
pnpm demo:backend:stop
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

`reset` 只针对 Compose project `approval-platform-demo` 的本地可丢弃卷。未提供确认参数时不得删除数据。

然后启动真实 Backend 和确定性 Seed：

```bash
pnpm demo:backend:start
```

等待输出：

```text
DEMO_BACKEND_ONE_COMMAND_STARTED
BACKEND_LOCAL_START_VERIFIED
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

保持该终端运行。

## 3. 启动交互式观察器

在第二个终端执行：

```bash
pnpm demo:runtime:observe -- --confirm-interactive-run
```

默认行为：

```text
Backend: http://127.0.0.1:8080
单阶段等待: 600 秒
轮询间隔: 1000 毫秒
证据文件: build/product-readiness/cross-client-runtime-evidence.json
```

可显式配置：

```bash
pnpm demo:runtime:observe -- \
  --confirm-interactive-run \
  --backend-origin http://127.0.0.1:8080 \
  --timeout-seconds 600 \
  --poll-interval-ms 1000 \
  --output build/product-readiness/cross-client-runtime-evidence.json
```

安全边界：

- Backend 只允许 loopback 或 RFC1918 的明文本地开发地址；
- 不接受 URL 用户名、密码、路径、Query 或 Fragment；
- 证据只能写入 `build/product-readiness/`；
- 输出使用临时文件后原子替换，并设置为当前用户读写；
- 观察器只发送健康、待办、实例和时间线读取请求；
- 观察器不发送 `POST /tasks/{taskId}/approve`；
- 不发送可信权限或 Worker 身份 Header。

## 4. 按提示使用真实客户端完成角色交接

观察器先等待固定业务单号的当前待办，然后打印对应客户端命令和访问位置。

### PC：经理审批

```bash
pnpm demo:client:pc -- --actor demo-manager --skip-install
```

打开打印的 PC 工作台地址，找到 `DEMO-PP-0001`，以 `demo-manager` 完成 `managerApproval`。

### H5：财务复核

```bash
pnpm demo:client:h5 -- --actor demo-finance-reviewer --skip-install
```

打开打印的 H5 地址，找到同一业务单号和同一实例，以 `demo-finance-reviewer` 完成 `financeReview`。

### 微信小程序：会签 A

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-a --skip-install
```

在微信开发者工具中导入生成结果，使用启动参数中的 `demoOperator=demo-finance-approver-a`，处理第一条 `financeCountersign`。

### 微信小程序：会签 B

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-b --skip-install
```

使用 `demoOperator=demo-finance-approver-b` 完成第二条 `financeCountersign`。

每一步结束后，观察器必须同时看到：

1. 当前 Task ID 从对应身份的待办中消失；
2. 同一 Instance ID 的 Timeline 新增且只新增一条由该身份产生的 `TASK_APPROVED`；
3. 下一阶段仍属于同一个 `tenantId`、`businessKey` 和 `instanceId`。

观察器本身不批准任务。若用户没有在真实客户端完成操作，它只会保持等待并最终失败关闭。

## 5. 完成后核验 JSON 证据

成功后输出：

```text
CROSS_CLIENT_SHARED_INSTANCE_OBSERVED
CROSS_CLIENT_ROLE_HANDOFFS_OBSERVED
PURCHASE_APPROVAL_RUNTIME_COMPLETED
```

证据至少包含：

```text
schemaVersion
startedAt / completedAt
Backend Origin
Scenario Manifest SHA-256
Cross-client Manifest SHA-256
tenantId
businessKey
instanceId
每一步的 client / actorId / taskDefinitionKey / taskId
每一步新增的 auditEventId
最终 status=COMPLETED
最终 activeTaskCount=0
```

失败时，文件保留 `status=FAILED` 和有界错误信息，不写成功 Claim。

## 6. 人工留存截图与录屏

观察器 JSON 是可执行状态证据，但不能替代用户可见证据。本次真实验收仍应另行保留：

- PC 经理待办和审批完成画面；
- H5 财务复核待办和审批完成画面；
- 微信会签 A、B 的开发者工具或真机画面；
- PC 管理员或发起人看到同一业务单号最终 `COMPLETED` 的画面；
- 未剪辑录屏中的时间、客户端、身份切换和业务单号；
- 浏览器、系统、微信开发者工具版本和测试机器信息。

在这些材料未留存前，必须继续写：

```text
CLIENT_SCREEN_RECORDING_NOT_INCLUDED
```

## 7. 当前可以和不可以声明的结论

只有观察器真实运行并写出 `status=PASSED` 后，才允许声明：

```text
CROSS_CLIENT_SHARED_INSTANCE_OBSERVED
CROSS_CLIENT_ROLE_HANDOFFS_OBSERVED
PURCHASE_APPROVAL_RUNTIME_COMPLETED
```

即使通过，仍必须保留：

```text
AUTOMATED_BROWSER_E2E_NOT_EXECUTED
CLIENT_SCREEN_RECORDING_NOT_INCLUDED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
BROWSER_COMPATIBILITY_NOT_VERIFIED
ACCESSIBILITY_NOT_VERIFIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

因此，这个观察器是跨端真实运行验收的可执行证据骨架，不是产品生产就绪结论。后续还需要浏览器矩阵、基础无障碍、真机或明确受支持的微信运行时、截图/录屏，以及干净环境 10 分钟启动证据。
