# PC 与 H5 真实审批交接 Smoke

该 Smoke 在真实 PostgreSQL、Redis、Spring Boot、Flowable、Vben PC、UniApp H5 和系统 Chromium 上执行采购付款前两个审批节点：

```text
PC / demo-manager / managerApproval
→ H5 / demo-finance-reviewer / financeReview
→ 同一实例产生两条 financeCountersign 待办
```

它用于证明 PC 和 H5 的开发客户端不只是能够构建，而是能够通过已经合并的本地身份桥和代理，真实读取并修改同一条确定性业务实例。

## 命令

只读计划：

```bash
pnpm demo:runtime:pc-h5:plan
```

显式运行：

```bash
pnpm demo:runtime:pc-h5
```

静态边界：

```bash
pnpm demo:runtime:pc-h5:check
```

GitHub Actions 中，现有 `web:test:client-boundary` 会先执行廉价边界检查，再由 `ci` 模式读取 Pull Request 或 Push 的精确变更集。只有以下运行相关路径变化时才启动高成本 Smoke：

```text
确定性 Demo 配置
本地 Demo 后端或客户端启动器
PC 审批 API、运行配置或审批工作台
H5 审批 API、运行配置或任务页面
PC/H5 Playwright Smoke 自身
```

普通文档、无关后端或其他模块修改不会重复安装并启动完整 Demo。

## 执行内容

1. 安装固定版本的 Vben 和 UniApp 生成工作区；
2. 启动隔离的 PostgreSQL、Redis、Spring Boot、Flowable 和采购付款 Seed；
3. 以 `demo-manager` 启动 PC；
4. 以 `demo-finance-reviewer` 启动 H5；
5. 使用 GitHub Runner 已安装的系统 Chrome/Chromium 打开真实页面；
6. 在 PC 页面点击“处理”与“同意”，完成 `managerApproval`；
7. 在 H5 页面点击同一业务单并完成 `financeReview`；
8. 验证两个客户端请求均携带准确的本地 Demo 租户与操作人；
9. 验证两个步骤属于同一 `instanceId`；
10. 验证下一阶段存在两条不同的 `financeCountersign` 待办；
11. 保存请求 ID、任务 ID、审计事件 ID、截图和 SHA-256。

浏览器测试不会直接调用审批写 API。审批写入必须由页面上的真实操作按钮触发；测试进程只使用后端读接口核对待办、时间线和实例状态。

## 成功声明

所有断言通过后，只允许输出：

```text
PC_H5_APPROVAL_HANDOFF_PASSED
```

该声明的精确定义是：

- PC 经理页面完成了 `managerApproval`；
- H5 财务页面完成了同一实例的 `financeReview`；
- 两个客户端使用同一租户、业务键和实例；
- 后端进入双人 `financeCountersign` 阶段；
- 截图与机器可读证据已经保留。

## 永久非声明

该 Smoke 没有运行微信小程序，也没有完成最终审批或支付，因此必须同时保留：

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

一次系统 Chromium 运行不能代表 Chrome、Edge、Firefox、Safari、iOS、Android 或微信运行时兼容性。Headless 浏览器截图也不能替代真实用户操作录像或无障碍人工检查。

## 证据位置

运行证据写入未跟踪目录：

```text
build/product-readiness/pc-h5-runtime/
```

其中至少包括：

```text
pc-h5-runtime-evidence.json
pc-manager-before.png
pc-manager-after.png
h5-finance-before.png
h5-finance-after.png
backend.log
pc.log
h5.log
```

机器可读证据包含：

```text
tenantId
businessKey
instanceId
manager taskId
finance review taskId
finance countersign taskIds
requestIds
auditEventIds
screenshot SHA-256
exact GitHub SHA and Run ID
```

证据目录不得提交到仓库。GitHub Actions 只把它作为永久验证制品保存。
