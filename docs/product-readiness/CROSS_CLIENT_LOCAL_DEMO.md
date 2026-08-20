# PC、H5 与微信小程序本地 Demo 运行指南

本指南把已经合并的确定性采购付款 Seed 接到 PC、H5 和微信小程序开发客户端，并提供固定的角色启动命令。它只适用于显式开发模式，不改变生产身份认证，也不把构建或启动器成功写成跨端 E2E 通过。

## 当前证据边界

本切片实现：

```text
LOCAL_CROSS_CLIENT_LAUNCHERS_IMPLEMENTED
LOCAL_DEMO_IDENTITY_HEADERS_GUARDED
PC_APPROVAL_PROXY_BOUNDED
H5_APPROVAL_PROXY_BOUNDED
WECHAT_PRIVATE_BACKEND_ORIGIN_REQUIRED
```

仍未执行：

```text
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
```

成功打印计划、打开页面或完成客户端构建，都不是跨端产品验收。

## 确定性场景

三个客户端都绑定同一仓库契约：

```text
tenantId:     demo-purchase-payment
businessKey:  DEMO-PP-0001
supplier:     Demo Industrial Supplies Ltd.
amount:       12500.00
purchase PO:  PO-DEMO-2026-0001
```

固定交接顺序：

| 顺序 | 客户端 | 角色 | 节点 |
| ---: | --- | --- | --- |
| 1 | PC | `demo-manager` | `managerApproval` |
| 2 | H5 | `demo-finance-reviewer` | `financeReview` |
| 3 | 微信小程序 | `demo-finance-approver-a` | `financeCountersign` |
| 4 | 微信小程序 | `demo-finance-approver-b` | `financeCountersign` |
| 5 | PC | `demo-employee` | 查看最终 `COMPLETED` |

机器可读计划位于 `config/demo/cross-client-local-demo.json`，每次客户端启动前都会重新核对 `config/demo/purchase-payment-golden-path.json`。

## 身份与网络边界

后端本地 Profile 使用 `local-headers` 身份模式。客户端只有在 Vite development 模式且 `VITE_APPROVAL_LOCAL_DEMO=true` 时才发送：

```text
X-Tenant-Id
X-Operator-Id
```

角色必须来自确定性 Manifest，租户必须是 `demo-purchase-payment`。客户端永远不会发送：

```text
X-Approval-Trusted-Permissions
X-Approval-Worker-Id
```

启动器仅接受回环地址或 RFC1918 私网 HTTP Origin；公网、HTTPS 互联网地址、嵌入凭据、路径、查询参数和非法端口都会在启动客户端前被拒绝。

## 1. 查看只读计划

```bash
pnpm demo:client:plan
```

该命令只输出固定租户、业务键、四个任务交接、证据字段和非声明，不启动进程或修改数据。

## 2. 启动真实后端

终端 A：

```bash
pnpm demo:backend:start
```

等待：

```text
DEMO_BACKEND_ONE_COMMAND_STARTED
BACKEND_LOCAL_START_VERIFIED
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

默认后端 Origin：

```text
http://127.0.0.1:8080
```

局域网运行可显式指定：

```bash
--backend-origin http://192.168.1.20:8080
```

## 3. PC：经理审批

终端 B：

```bash
pnpm demo:client:pc -- --actor demo-manager
```

默认打开地址：

```text
http://127.0.0.1:5777/approval/workbench?demoOperator=demo-manager
```

在“待我处理”中打开 `DEMO-PP-0001`，完成 `managerApproval`。

PC 普通 `/api` 仍连接 Vben Mock；只有 `/approval-api` 连接真实审批后端。可以指定其他本地端口：

```bash
pnpm demo:client:pc -- --actor demo-manager --port 5780
```

## 4. H5：财务复核

终端 C：

```bash
pnpm demo:client:h5 -- --actor demo-finance-reviewer
```

打开启动器打印的 H5 地址，找到 `DEMO-PP-0001` 并完成 `financeReview`。

可指定其他端口：

```bash
pnpm demo:client:h5 -- --actor demo-finance-reviewer --port 9001
```

## 5. 微信小程序：双人会签

第一位财务会签人：

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-a
```

在微信开发者工具中导入生成目录，打开：

```text
pages/task/list?demoOperator=demo-finance-approver-a
```

完成第一条 `financeCountersign` 后，切换第二位会签人：

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-b
```

真机或远程开发者工具不能使用开发机自己的 `127.0.0.1`。应使用未提交的局域网地址：

```bash
pnpm demo:client:wechat -- \
  --actor demo-finance-approver-a \
  --backend-origin http://192.168.1.20:8080
```

本仓库不声明微信真机、域名白名单或生产发布已通过。

## 6. PC：核对最终状态

另开 PC 端口：

```bash
pnpm demo:client:pc -- --actor demo-employee --port 5778
```

在“我发起的”中验证 `DEMO-PP-0001` 为 `COMPLETED`、无当前任务，并与 H5 和微信步骤记录的实例及任务身份一致。

## 必须保留的证据

未来正式运行至少保留：

```text
tenantId
businessKey
instanceId
taskIds
auditEventIds
finalStatus
```

每一步建议同时记录：

- 精确 Commit、客户端和运行时版本；
- 角色与节点；
- 操作前后截图或连续录像；
- 后端返回的 requestId；
- instanceId、taskId 和 auditEventId；
- 三端最终状态对比；
- 微信开发者工具或真机信息；
- 浏览器、控制台和网络错误。

只有同一实例通过受支持客户端完成，并且三个客户端显示一致最终状态，才能考虑声明 `PURCHASE_APPROVAL_E2E_PASSED`。

## 复用已安装工作区

已生成并安装工作区后，可跳过依赖安装：

```bash
pnpm demo:client:pc -- --actor demo-manager --skip-install
pnpm demo:client:h5 -- --actor demo-finance-reviewer --skip-install
pnpm demo:client:wechat -- --actor demo-finance-approver-a --skip-install
```

此选项只跳过安装，不跳过场景契约、角色、租户和网络边界检查。

## 停止但保留数据

客户端使用 `Ctrl-C` 停止。后端停止后，可保留 PostgreSQL Volume：

```bash
pnpm demo:backend:stop
```

保留 Volume 是预期行为；后端 Seed 已有永久集成测试证明审批推进或完成后重启不会创建第二个实例。

## 禁止推导的结论

不得从启动器、计划或构建成功推导：

```text
QUICK_START_10_MINUTES_PASSED
PURCHASE_APPROVAL_E2E_PASSED
PC_H5_WECHAT_RUNTIME_PASSED
BROWSER_COMPATIBILITY_PASSED
ACCESSIBILITY_BASELINE_PASSED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
PRODUCTION_PAYMENT_INTEGRATION_VERIFIED
```

下一步必须是真实、计时、可留存的客户端运行，而不是再增加一份静态状态文档。
