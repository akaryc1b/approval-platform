# PC 与 H5 真实审批运行 Smoke

该 Smoke 在真实 PostgreSQL、Redis、Spring Boot、Flowable、Vben PC、UniApp H5 和系统 Chromium 上执行同一条确定性采购付款实例的审批动作：

```text
PC / demo-manager / managerApproval
→ H5 / demo-finance-reviewer / financeReview
→ H5 / demo-finance-approver-a / financeCountersign
→ H5 / demo-finance-approver-b / financeCountersign
→ COMPLETED
```

实例由受治理的后端 Seed 创建；所有审批写入都由 PC 或 H5 页面上的真实按钮触发。测试进程只使用真实后端读接口核对待办、实例状态和时间线，不直接写审批 API 或数据库。

## 命令

只读计划：

```bash
pnpm demo:runtime:pc-h5:plan
```

边界检查：

```bash
pnpm demo:runtime:pc-h5:check
```

显式运行：

```bash
pnpm demo:runtime:pc-h5
```

现有 `web:test:client-boundary` 在 GitHub Actions 中先执行廉价边界检查，再根据 Pull Request 或 Push 的精确变更集决定是否启动高成本 Smoke。运行时 API、UI、Spec、诊断器、Playwright 配置、Demo 后端、客户端启动器和确定性 Seed 变化时会选择该 Smoke；无关路径不会重复启动完整环境。

## 运行约束

Smoke 必须使用仓库固定的 Corepack 与 pnpm 版本，并执行以下真实链路：

1. 安装生成后的 Vben 与 UniApp 工作区；
2. 启动隔离的 PostgreSQL、Redis、Spring Boot、Flowable 和采购付款 Seed；
3. 启动 PC 客户端与一套 H5 开发服务器；
4. 在 PC 页面以 `demo-manager` 完成 `managerApproval`；
5. 在 H5 页面以 `demo-finance-reviewer` 完成 `financeReview`；
6. 通过 URL 范围内的本地 Demo 身份分别以 `demo-finance-approver-a` 与 `demo-finance-approver-b` 完成两条不同的 `financeCountersign`；
7. 验证两个会签 `taskId` 不同，且每个任务只对权威 Seed 指定的操作人可见；
8. 验证所有请求均携带准确的 `tenantId`、`operatorId`、`requestId` 和 `traceId`；
9. 验证四个审批结果、四条审计事件、同一 `businessKey` 与同一 `instanceId`；
10. 验证最终实例为 `COMPLETED`，且四个权威操作人均无该业务键的待办。

等待逻辑必须在导航或 UI 操作前注册，并同时绑定 HTTP Method、精确 Path、Status、Tenant、Actor、businessKey、taskDefinitionKey 和 processInstanceId。状态推进使用有界真实 API 轮询，不使用固定 sleep，不延长超时掩盖失败，也不捕获错误后继续成功。

## 成功声明

所有断言通过后，只允许输出：

```text
PC_H5_APPROVAL_HANDOFF_PASSED
```

该声明只证明：同一后端 Seed 实例通过 PC 与 H5 的真实 UI 完成了经理审批、财务复核和两人财务会签，并到达 `COMPLETED`。

## 永久非声明

该 Smoke 没有通过产品 UI 发起实例，没有运行微信小程序，也没有执行支付、兼容性、无障碍、Quick Start、性能或恢复验收。因此必须同时保留：

```text
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED
PC_H5_WECHAT_RUNTIME_NOT_EXECUTED
BROWSER_COMPATIBILITY_NOT_VERIFIED
ACCESSIBILITY_NOT_VERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
QUICK_START_10_MINUTES_NOT_EXECUTED
```

一次 Headless 系统 Chromium 运行不能代表 Chrome、Edge、Firefox、Safari、iOS、Android 或微信运行时兼容性，也不能替代真实用户操作、无障碍人工检查、生产支付或完整采购到付款 E2E。

## 成功证据

运行证据写入未跟踪目录：

```text
build/product-readiness/pc-h5-runtime/
```

成功运行至少包括：

```text
pc-h5-runtime-evidence.json
pc-manager-before.png
pc-manager-after.png
h5-finance-before.png
h5-finance-after.png
h5-countersign-a-before.png
h5-countersign-a-after.png
h5-countersign-b-before.png
h5-countersign-b-after.png
playwright/**/trace.zip
backend.log
pc.log
h5.log
```

机器证据记录：

```text
exact pull-request Head SHA
GitHub Run ID
tenantId
businessKey
instanceId
权威 Seed 来源与操作人
四个 taskId、taskDefinitionKey、请求头和动作结果
四个 auditEventId 与 requestId
每个阶段的真实待办可见性
每一步前后的实例状态
最终 COMPLETED 状态
截图 SHA-256
明确 non-claims
```

## 失败诊断

失败时保持 fail-closed，并至少写出：

```text
runtime-diagnostics.json
runtime-diagnostics.md
pc-demo-manager-runtime-failure.png
h5-demo-finance-reviewer-runtime-failure.png
h5-demo-finance-approver-a-runtime-failure.png
h5-demo-finance-approver-b-runtime-failure.png
playwright/**/trace.zip
playwright/**/error-context.md
playwright/**/*test-failed*.png
```

诊断包含当前 URL、页面标题、DOM 关键文本、任务卡数量、最近请求与响应、失败请求、console errors、page errors、当前 Actor、租户、业务键、流程实例、四个权威操作人的真实待办、当前实例状态和时间线。

## 永久 Artifact

现有 Vben workflow 只永久上传固定日志文件。Smoke 因此把允许的 JSON、Markdown、PNG 和 ZIP 文件按相对路径、字节数、SHA-256 与 Base64 编入 `root-install.log` 的机器可读 envelope：

```text
APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_BEGIN
{"evidenceKind":"PC_H5_BROWSER_RUNTIME_CI_ARTIFACT_ENVELOPE_V1",...}
APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_END
```

该 envelope 位于 `approval-vben-<runId>` artifact。成功与失败都必须留存；通过时还会校验八张截图、机器证据和 `trace.zip`。任何留存失败都会让 Smoke 继续失败，不能把缺失 Trace、截图或机器证据解释为通过。
