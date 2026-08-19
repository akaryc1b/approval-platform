# PC、H5 与微信小程序本地 Demo 连接指南

本指南把已经合并的确定性采购付款 Seed 接到三个现有客户端。它只适用于显式开发模式，不会改变生产身份认证，也不会把构建成功写成跨端 E2E 通过。

## 1. 启动后端

在第一个终端执行：

```bash
pnpm demo:backend:start
```

看到以下标记后再启动客户端：

```text
DEMO_BACKEND_ONE_COMMAND_STARTED
BACKEND_LOCAL_START_VERIFIED
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

后端使用 `local` Profile，并要求 `X-Tenant-Id` 与 `X-Operator-Id`。客户端只在 `import.meta.env.DEV` 且 `VITE_APPROVAL_LOCAL_DEMO=true` 时发送这两个头。它们永远不会发送 `X-Approval-Trusted-Permissions`。

## 2. PC

首次准备：

```bash
pnpm web:install
```

启动：

```bash
pnpm web:dev
```

PC 的普通 `/api` 仍连接 Vben 本地 Mock，用于外壳登录；只有 `/approval-api` 被代理到 `http://127.0.0.1:8080`。打开 Vben 输出的地址并进入 `/approval/workbench`。

通过查询参数选择确定性角色：

```text
/approval/workbench?demoOperator=demo-manager
/approval/workbench?demoOperator=demo-finance-reviewer
/approval/workbench?demoOperator=demo-finance-approver-a
/approval/workbench?demoOperator=demo-finance-approver-b
/approval/workbench?demoOperator=demo-employee
/approval/workbench?demoOperator=demo-admin
```

未知角色会被客户端拒绝，不会退化为任意请求头身份。

## 3. H5

首次准备：

```bash
pnpm mobile:install
```

启动：

```bash
pnpm mobile:dev:h5
```

开发模式将 H5 的 `/approval-api` 代理到本地后端。打开 UniApp 输出的地址，进入审批中心，并在 H5 路由查询中加入角色，例如：

```text
#/pages/task/list?demoOperator=demo-manager
```

## 4. 微信小程序

启动开发构建：

```bash
pnpm mobile:dev:weixin
```

在微信开发者工具中导入生成的 `dist/dev/mp-weixin` 目录，并创建带查询参数的编译条件：

```text
demoOperator=demo-manager
```

默认开发地址是：

```text
http://127.0.0.1:8080/api
```

真机调试不能使用电脑自身的 `127.0.0.1`。应在未提交的本地环境文件中把 `VITE_APPROVAL_WEIXIN_API_URL` 改为开发机可访问的 LAN 或 HTTPS 地址，并按微信开发者工具要求配置开发域名。不要把个人 IP、Token 或 AppSecret 提交到仓库。

## 5. 采购付款 Golden Path

三个客户端都必须使用：

```text
tenantId: demo-purchase-payment
businessKey: DEMO-PP-0001
amount: 12500.00
```

角色顺序：

```text
demo-manager
→ demo-finance-reviewer
→ demo-finance-approver-a
→ demo-finance-approver-b
→ COMPLETED
```

每一步应记录同一组业务证据：

```text
tenantId
businessKey
instanceId
taskIds
auditEventIds
finalStatus
```

PC、H5 与微信小程序必须读取到相同的最终实例状态，才能形成真正的跨端运行态证据。

## 当前边界

本次实现只证明：

```text
CROSS_CLIENT_LOCAL_DEMO_BINDING_IMPLEMENTED
LOCAL_DEMO_IDENTITY_ALLOWLIST_ENFORCED
PC_APPROVAL_BACKEND_PROXY_IMPLEMENTED
H5_APPROVAL_BACKEND_PROXY_IMPLEMENTED
WECHAT_DEVELOPMENT_BACKEND_URL_SUPPORTED
```

仍然是：

```text
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
BROWSER_COMPATIBILITY_NOT_VERIFIED
ACCESSIBILITY_NOT_VERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

构建、类型检查、静态边界和本指南都不能替代真实点击、截图、同一业务键核验或微信开发者工具运行记录。
