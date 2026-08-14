# Current Architecture

## 文档状态

本文描述默认分支当前已合并代码的系统形态。它不绑定历史 commit，也不构成 Release 或 Production Support 声明。逐项能力状态见 [`capability-status.md`](capability-status.md)。

## 系统形态

Approval Platform 是独立审批产品，不作为 RuoYi 或其他宿主项目的内嵌 Flowable 封装。

```text
Host Systems / SSO / Directory / Business Services
                │
        REST · Webhook · SDK · Connector
                │
                ▼
Trusted Identity and Integration Boundary
  authenticated principal · tenant · bounded authority
                │
                ▼
Approval API and Client Surfaces
  participant · management · Web · H5 · WeChat · Mobile
                │
                ▼
Application Orchestration and Governance
  command · query · authorization · idempotency · audit
        │               │               │
        ▼               ▼               ▼
 Approval Domain   Platform Persistence  External Ports
 DSL · Form · SLA  Flyway · Outbox       Engine · Connector · AI
 release · task    immutable evidence    SDK/Event contracts
        │               │               │
        └───────────────┴───────────────┘
                        │
                        ▼
        Flowable public API and governed providers
```

宿主系统和客户端不直接调用 Flowable。Flowable 仅通过正式 Engine SPI 和公开 API 参与执行。Connector、Event 和 AI Provider 都是外部副作用边界，不能改变审批核心权限模型。

## 核心分层

### Domain

纯 Java 领域模型和确定性规则，不依赖 Spring、Flowable、数据库或宿主框架。主要包含：

- Approval DSL、定义版本、发布包和运行时绑定；
- Form Schema、UI Schema、字段权限和多端渲染语义；
- instance、task、collaboration、delegation、handover；
- audit、notification、attachment、Outbox；
- work calendar、SLA policy、intent、attempt 和 replay；
- process migration plan、verification、UNKNOWN 和 reconciliation；
- AI advisory、非可执行 proposal、governance evaluation 和 lineage。

### Application

Application 负责用例编排和平台事务边界：

- 服务端权威身份、租户、权限和资源范围校验；
- command/query、幂等、冲突和审计；
- release publication、activation、runtime binding 和 migration governance；
- 外部调用前后状态机、at-most-one、UNKNOWN 和恢复语义；
- AI 输入最小化、字段权限、任务重新校验和人工复核边界。

Application 不能把数据库、Flowable、Connector 或 Provider 伪装成一个跨系统原子事务。

### Persistence and Flyway

平台状态、投影和不可变证据由 JDBC 持久化管理。当前组合 Flyway 拓扑由生成器递归识别 SQL、Java migration 和附加资源位置，结果见 [`compatibility.md`](compatibility.md)。

永久规则：

- 已合并或已应用 migration 不得重写；
- 敏感记录必须 tenant scoped；
- attempt、event、audit、replay、binding 和 lineage 不得被普通 update 覆盖；
- 并发通过数据库原生约束、锁、lease、version 或 CAS 处理；
- 生产应用不得查询或修改 Flowable `ACT_*` 内部表。

PostgreSQL 是当前默认分支的已验收参考数据库。其他数据库只有在 Capability Status 同时明确 Tested、Accepted、Merged、Released 和 Production Supported 后，才可按相应范围部署。

### Engine SPI and process migration

Engine SPI 隔离产品语义与引擎实现。运行实例迁移使用受治理计划、授权、claim、单次公开引擎调用、精确 readback、runtime-binding CAS、UNKNOWN 和 reconciliation 证据。

进入主线和正式验收不等于生产执行授权。迁移执行、Worker、orchestration、aggregation 和 automatic reconciliation 必须保持安全默认值，并受 [`operations.md`](operations.md) 的生产边界约束。

### Connector, SDK and Event

Connector 负责组织、身份、消息、凭据、第三方待办、业务回调和宿主集成。凭据引用、Secret Material、tenant routing、Token 和 transport evidence 必须由服务端控制。

SDK/Event 层提供版本化契约、签名、timestamp、nonce、replay 和 idempotency 规则。契约存在不代表持久订阅、Broker、投递 Worker 或 operated recovery 已经可生产使用。

### Template and component ecosystem

模板和组件能力是确定性、数据型和受限的：

- 包、Schema、组件版本和哈希精确绑定；
- 导入先进行无副作用预览；
- tenant-local 内容从受控 DRAFT 开始；
- 未知或不支持的历史组件只能安全只读 fallback；
- 禁止脚本、任意 HTML、表达式执行、远程模块和动态加载；
- 模板或组件不能携带可信权限，也不能直接发布、部署或执行流程命令。

### AI Provider and approval assistance

AI 路径由 Provider SPI、运行时控制、OpenAI Responses adapter、审批辅助和受控自动化治理组成。

永久权威链为：

```text
AI advisory
  -> typed NON_EXECUTABLE proposal
  -> fresh server policy and precondition evaluation
  -> fresh authorization preview
  -> explicit human confirmation
  -> existing application command service
  -> immutable audited result
```

当前主线在可执行 command-service admission 前保持 fail closed。永久禁止：

```text
Provider -> direct command
```

AI 不能制造 tenant、operator、permission、resource、audit、worker、lease、credential、engine 或 command authority。原始客户 Prompt、原始输入、原始 Provider 响应和 Secret Material 不能作为已接受的持久审计内容。

## 客户端边界

Web、H5、微信小程序和 Mobile 只提供业务输入和展示：

- 不得制造可信 tenant、operator、permission 或 audit；
- 迁移运维界面保持受权只读；
- AI 生成必须来自显式用户动作；
- advisory、UNKNOWN、stale 和 human-review 状态必须明确展示；
- 客户端隐藏按钮不是安全边界。

## 部署形态

当前适合模块化单体部署：无状态 API、平台数据库、Flowable、可选对象存储、Connector 和受治理 AI Provider。水平扩展前必须确认所有节点使用一致的应用、编译器、Flyway、可信身份、数据库时钟和默认关闭配置。

系统可以按运行压力拆分 Worker 或外部适配器，但不能通过拆分削弱事务、审计、租户、UNKNOWN、幂等或权限边界。
