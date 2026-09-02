# Online Evaluation Sandbox / 在线试用平台

```text
ONLINE_DEMO_STATUS=PLANNED_NOT_AVAILABLE
ONLINE_DEMO_MODE=NON_PRODUCTION_EVALUATION_SANDBOX
PUBLIC_URL_STATUS=NOT_PUBLISHED
TRACKING_ISSUE=#144
```

可以搭建在线试用平台，但当前仓库还没有可公开访问的 URL，也没有完成上线所需的应用镜像、会话隔离、自动重置、滥用防护和托管容量验收。

Issue [#144](https://github.com/akaryc1b/approval-platform/issues/144) 跟踪这一交付。它是 Product Alpha 的非生产评估环境，不是正式 SaaS、生产部署或 Production Support。

## 目标体验

用户无需安装仓库即可：

1. 通过 HTTPS 打开演示首页；
2. 进入一个独立、可丢弃的演示会话；
3. 查看确定性的采购付款申请；
4. 分别体验员工、经理、财务复核和两人会签角色；
5. 通过可见 PC/H5 控件完成同一条审批流程；
6. 查看审计时间线、Outbox 和本地付款沙箱的故障恢复结果；
7. 结束后自动清除或重置数据。

## 推荐上线方式

先采用两阶段发布，而不是直接匿名公开：

### Stage 1 — 邀请制评估环境

- 访问控制或一次性邀请；
- 很小且明确的并发上限；
- 每个用户独立会话或已证明隔离的租户；
- 自动到期和定时 reset；
- 人工可观察、可快速关闭；
- 禁止搜索引擎索引；
- 仅使用非敏感演示数据。

### Stage 2 — 公共评估环境

只有 Stage 1 已证明数据隔离、reset、限流、附件边界、外部出口和稳定容量后，才开放匿名或公开访问。

## 参考架构

```text
Internet
  → HTTPS reverse proxy / rate limit
      → PC frontend
      → H5 frontend
      → Approval backend
          → PostgreSQL 16
          → Redis
          → signed local payment sandbox
```

在线环境必须新增专用 `online-demo` 配置边界，并与 `local` 和未来 production profile 分离。浏览器不能提供可信租户、权限或任意操作者身份。

当前 `deploy/compose/docker-compose.yml` 只提供 PostgreSQL 16 和 Redis，尚不是完整在线应用部署包。上线前至少还需要：

- 可重复制品化的 backend、PC 和 H5 image；
- HTTPS 入口与反向代理配置；
- online-demo profile；
- 数据隔离和 reset controller/worker；
- 监控、日志和禁用开关；
- 一条在线 smoke/E2E 验证路径。

## 必须复用的现有能力

在线试用不另起一套演示产品。它应复用：

- `pnpm demo:quickstart` 的服务、Seed、客户端和清理语义；
- `pnpm demo:runtime:purchase-payment:e2e` 的确定性业务路径；
- PostgreSQL 16、Redis、Spring Boot 和 Flowable；
- 平台自有 Form/Release/Deployment/Activation；
- 事务 Outbox、Connector 和签名本地付款沙箱；
- 现有业务标识、审计和机器证据。

不得创建第二套数据库模型、Seed、审批后端、身份模型、Outbox 或自动 PR/main Workflow。

## 会话与数据隔离

公开环境不能让所有访客共享一个可写租户。实现必须选择并验证一种隔离模型：

- 每个会话使用独立租户与业务键；或
- 每个会话使用独立临时数据库/schema；或
- 使用受控单用户 slot，并在下一位用户进入前完成 fail-closed reset。

至少要证明两个并行评估者无法读取、修改或重置对方的数据。会话必须有明确 TTL；过期后清理任务必须有界、可观测并可重试。

## 安全与滥用防护

上线前必须具备：

- HTTPS；
- rate limiting 和并发限制；
- 请求体、上传数量、文件类型和文件大小限制；
- 可选 CAPTCHA、邀请 token 或其他入口防滥用机制；
- 固定演示角色，不提供匿名平台管理员；
- 所有 Connector、Webhook、邮件、AI Provider 和支付出口默认关闭或 allowlist；
- 只允许仓库已有的本地签名付款沙箱；
- 无客户数据、生产用户、真实凭据或持久个人信息；
- 前端 bundle、日志、截图和证据中不包含 Secret；
- 统一关闭开关和 incident runbook。

## Reset 与生命周期

在线环境必须同时提供：

- 会话到期自动 reset；
- 运营人员手动 reset；
- reset 失败时阻止会话复用；
- 后端、数据库、缓存、附件、Outbox 和沙箱副作用的一致清理；
- 清理证据和告警；
- 定期从确定性基线重新创建环境。

不能通过直接修改平台业务表或 Flowable `ACT_*` 表来重置或推进业务状态。

## 容量门槛

公共开放前必须测量并发布一个保守的在线演示运行点：

- 同时在线会话数；
- 完整场景成功率；
- throughput、P50/P95/P99 和 error rate；
- JVM、PostgreSQL、Redis、连接数与存储增长；
- reset 持续时间和失败行为；
- Outbox/Connector backlog 与恢复；
- 超过声明上限时的拒绝或降级行为。

当前容量与恢复工作仍在独立产品分支进行。未合并的本地测量不能直接变成托管容量承诺。

## 推荐实施顺序

1. 构建 backend、PC、H5 的可重现 image；
2. 增加专用 online-demo 配置和外部出口策略；
3. 实现会话/租户隔离与 TTL；
4. 实现自动和人工 reset；
5. 增加 HTTPS 入口、限流、上传边界和关闭开关；
6. 增加在线 smoke、双会话隔离和完整业务 E2E；
7. 进行邀请制运行与容量测量；
8. 审计证据后再向 README 写入真实 URL。

PR #142 当前占用产品功能流。在线 Demo 的实现分支和产品 PR 应在该产品流结束或维护者明确重新分配后再创建，避免并行产品主线失控。

## 托管输入

仓库可以实现部署包与验证合同，但真正发布 URL 仍需要维护者提供：

- 一台目标主机或 k3s/Kubernetes 集群；
- 域名与 DNS 控制权；
- TLS 方案；
- image registry；
- 部署凭据和 Secret 管理方式。

这些信息必须通过托管环境注入，不能提交到 GitHub。

## 当前非声明

```text
ONLINE_DEMO_NOT_AVAILABLE
PUBLIC_URL_NOT_PUBLISHED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
PRODUCTION_CAPACITY_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
RELEASE_NOT_CREATED
```
