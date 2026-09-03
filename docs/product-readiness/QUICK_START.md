# Approval Platform — 10-Minute Quick Start

```text
QUICK_START_COMMAND_STATUS=IMPLEMENTED
QUICK_START_ACCEPTANCE_SOURCE=RETAINED_EXACT_HEAD_AND_POST_MERGE_EVIDENCE
QUICK_START_ACCEPTANCE_STATUS=MERGED_LOCAL_ALPHA_ACCEPTED
```

这是当前受支持的本地 Product Alpha 入口。它已经在同一源码树上通过两次独立、清洁、10 分钟内的运行，并在合并后的默认分支重新验证。

本指南说明如何复现该路径。一次新的本地成功运行不会自动创建 Release、Production Support 或新的全局验收声明。

## 你会得到什么

在仓库根目录执行一个命令：

```bash
pnpm demo:quickstart
```

成功进入 ready 状态时，同一源码树上的以下结果同时成立：

```text
repository and workstation preflight passed
PostgreSQL 16 and Redis 7.4 are ready
Spring Boot + Flowable reached Actuator UP
the deterministic purchase-payment Seed was applied
PC is reachable on port 5777 as demo-manager
H5 is reachable on port 9000 as demo-manager
DEMO-PP-0001 is visible in both real client pages
startup timing and environment evidence were written
```

命令随后保持附着。按一次 `Ctrl-C` 会停止客户端和后端，并删除一次性本地容器、网络、PostgreSQL volume 和占用端口。

## 前置条件

使用干净的 macOS、Linux 或 Windows 工作站，并准备：

- Java 21；
- Maven 3.9.6 或更高版本；
- Node 22.18+（22.x）或 Node 24.x；
- pnpm 10；仓库当前声明 pnpm 10.33.4；
- Docker Engine 或 Docker Desktop，以及 Docker Compose v2；
- Google Chrome、Chromium 或 Microsoft Edge。只有自动发现失败时才设置 `APPROVAL_DEMO_CHROME_PATH`；
- 足够的内存和磁盘空间，用于 Maven reactor、前端工作区、PostgreSQL 和 Redis。

命令会先执行现有 `demo-preflight.mjs`。缺少工具或版本不受支持时会 fail closed，并给出修复提示。

## 先查看计划

下面的命令只读，不启动服务：

```bash
pnpm demo:quickstart:plan
```

它会输出租户、业务键、角色 URL、600 秒上限、生命周期阶段、证据目录、可声明结果和明确非声明。

组件诊断入口仍然可用：

```bash
pnpm demo:backend:plan
pnpm demo:backend:start
pnpm demo:backend:stop
```

静态边界可单独检查：

```bash
pnpm demo:preflight -- --repository-only
pnpm demo:scenario:check
pnpm demo:clients:check
pnpm demo:quickstart:check
```

这些组件命令不会单独证明 10 分钟完整启动。

## 组件命令的声明词汇

以下标记为较窄的只读校验、后端和 Seed 命令保留：

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

这些是命令作用域标记，不是全局状态。例如，只读场景校验器不会启动运行时，所以它输出的 `_NOT_EXECUTED` 是正确的；它不会撤销已经由独立运行时接受的 Quick Start 或采购到付款证据。

## 运行 Quick Start

```bash
pnpm demo:quickstart
```

不要同时运行另一套后端或客户端命令。Quick Start 独占本地端口和生命周期：

| 组件 | 地址 | 受治理身份 |
| --- | --- | --- |
| Backend health | `http://127.0.0.1:8080/actuator/health` | N/A |
| PC workbench | 终端输出的 `QUICK_START_PC_URL` | `demo-manager` |
| H5 task list | 终端输出的 `QUICK_START_H5_URL` | `demo-manager` |

PC 开发登录只用于本地演示：

```text
username: vben
password: 123456
```

浏览器自动化会完成本地滑块登录并验证任务卡片。Quick Start 本身不会审批任务或推进流程。

ready 后，终端输出：

```text
QUICK_START_RUN_ID=<run-id>
QUICK_START_READY_SECONDS=<measured-seconds>
QUICK_START_PC_URL=<url>
QUICK_START_H5_URL=<url>
QUICK_START_TENANT=demo-purchase-payment
QUICK_START_BUSINESS_KEY=DEMO-PP-0001
QUICK_START_PC_ACTOR=demo-manager
QUICK_START_H5_ACTOR=demo-manager
QUICK_START_EVIDENCE=.runtime/quick-start/<run-id>
```

## 证据

每次运行写入未跟踪目录：

```text
.runtime/quick-start/<run-id>/
```

主要证据包括：

```text
source-identity.json
environment.json
contract.json
backend-health.json
quick-start-browser-evidence.json
quick-start-pc.png
quick-start-h5.png
startup-summary.json
cleanup-evidence.json
runtime-summary.json
playwright/trace.zip
backend.log
pc.log
h5.log
```

`source-identity.json` 绑定实际源码树；`environment.json` 记录系统、架构、CPU、内存和工具版本；`startup-summary.json` 记录 UTC 开始、ready 时间和实测时长。A run over 600 seconds fails，并重置连续运行 ledger。

`.runtime` 内容不会提交到 Git。

## 验收与后续变更规则

首次 Product Alpha 验收要求同一 commit/tree 上两个不同 run ID 的清洁运行，并要求每次完整清理。默认分支中的当前入口已经满足该规则。

未来只要 Quick Start 的受控路径发生变化，新的候选源码树仍必须重新满足相同规则，才可以在其验收记录中发布：

```text
QUICK_START_10_MINUTES_PASSED
DEMO_BACKEND_READY_PASSED
PC_DEMO_READY_PASSED
H5_DEMO_READY_PASSED
TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED
```

精确 Head、Run ID、Artifact digest 和观测结果属于不可变 PR/Issue 证据，不复制到 living 指南。

## 停止与清理

在 Quick Start 终端按一次 `Ctrl-C`。清理是强制且 fail closed 的：

```text
stop H5
stop PC
stop backend
remove approval-platform-demo containers
remove the disposable PostgreSQL volume
remove the Compose network
release ports 5432, 5777, 6379, 8080 and 9000
write cleanup-evidence.json
```

即使页面已经 ready，只要清理失败，命令仍会失败。

外部中断后可使用显式 reset：

```bash
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

该命令只删除一次性 `approval-platform-demo` 资源，不会更新平台业务表或 Flowable `ACT_*` 表。

## 下一步：完整采购到付款

Quick Start 停在一个可见、可操作的确定性任务。完整路径使用：

```bash
pnpm demo:runtime:purchase-payment:e2e
```

继续阅读：

- [User Guide](USER_GUIDE.md)
- [Administrator Guide](ADMIN_GUIDE.md)
- [Operator Guide](OPERATOR_GUIDE.md)
- [Purchase-Payment Golden Path](PURCHASE_PAYMENT_GOLDEN_PATH.md)
- [Online Evaluation Sandbox](ONLINE_DEMO.md)

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
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
ONLINE_DEMO_NOT_AVAILABLE
RELEASE_NOT_CREATED
```

Quick Start 是本地 Product Alpha 路径，不是在线服务、Release 或生产部署过程。
