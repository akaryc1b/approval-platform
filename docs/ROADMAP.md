# Roadmap

## 文档职责

Roadmap 只描述未来优先级、目标和完成条件，不再承担当前实现状态的权威职责。

当前事实请查看：

- [`current/capability-status.md`](current/capability-status.md)；
- [`current/compatibility.md`](current/compatibility.md)；
- [`acceptance/README.md`](acceptance/README.md)；
- [`releases/README.md`](releases/README.md)。

Implemented、Tested、Accepted、Merged、Released 和 Production Supported 必须分别维护。Roadmap 中出现某项工作，不表示它尚未实现；代码中出现某个模块，也不表示它已经发布或支持生产。

## 当前优先级

### 1. 文档权威与状态自动化

目标：彻底分离 Current、Acceptance、Release、Reference 和 Roadmap。

完成条件：

- README 不再手工维护里程碑和数据库版本；
- Capability Status 与 Compatibility 可确定性生成；
- Current 不包含历史 SHA、Run、PR 或 Artifact 身份；
- 现有 Acceptance 受不可变锁保护，修正只能追加；
- 版本目录只在真实 tag 和 GitHub Release 存在时创建。

### 2. 可发布产品基线

目标：形成第一个真实 Release Candidate，而不是把默认分支当作发布物。

完成条件：

- Quick Start、采购付款 E2E、管理员与普通用户操作说明齐备；
- Release manifest、SBOM、制品摘要、兼容快照和升级/恢复说明齐备；
- PC、H5、微信小程序统一场景通过；
- 浏览器兼容、无障碍、容量、性能和恢复基线公开；
- Git tag 与 GitHub Release 绑定精确制品；
- Release 等级和支持边界明确。

### 3. 双数据库生产兼容

目标：在不削弱 PostgreSQL 语义的前提下完成 MySQL 8.4 的等价支持。

完成条件：

- clean install、历史升级、恢复和后续 migration 策略完整；
- 所有平台 JDBC store、Flowable、锁、CAS、lease、timestamp、JSON 和故障语义等价验证；
- 双数据库永久 CI、运维文档、容量和恢复演练通过；
- Compatibility、Capability Status 和 Release 同步更新；
- 在正式合并、发布和支持批准前保持 fail closed。

### 4. Connector 与 Event 生产运行闭环

目标：从契约和限定调用能力推进到可运维的真实外部集成。

完成条件：

- durable subscription、Outbox delivery、Worker、Broker 或等价运行时；
- Secret/Token 生命周期、限流、幂等、UNKNOWN、dead-letter 和 replay；
- Connector/Event 积压恢复、容量、告警、on-call 和 incident 演练；
- 至少一个真实业务集成从授权环境完成 E2E；
- 不允许外部系统绕过审批核心权限和状态机。

### 5. AI 生产授权与有价值的受控动作

目标：保持 AI_IS_NOT_AN_OPERATOR，同时完成真实可运维 Provider 和受控命令链。

完成条件：

- 客户生产环境、Secret、egress、模型、Prompt、成本、retention 和 on-call 获得批准；
- Provider 故障、UNKNOWN、Circuit、RateLimiter 和 incident 演练通过；
- 找到并审计至少一个有价值且已有的 Application Command；
- whitelist、separation of duties、fresh authorization、生产重新认证和 single-use confirmation 完整；
- 命令只能通过既有 Application Service 执行；
- 永久禁止 `Provider -> direct command`。

### 6. 产品体验、容量和恢复证据

目标：不仅证明代码符合设计，还证明用户能够完成工作，系统能够在真实负载和故障下恢复。

完成条件：

- 新用户可在 10 分钟内启动演示环境；
- 一条完整审批 E2E 长期稳定通过；
- 大租户、大流程、大表单和多实例吞吐量有公开基线；
- Outbox、Connector、Event 和 AI backlog 有可执行恢复步骤；
- RPO、RTO、备份恢复和升级前后数据一致性完成演练。

## 永久边界

- Roadmap 不覆盖或改写历史 Acceptance；
- 没有真实 Release 时不得创建版本发布目录；
- 测试通过不等于生产支持；
- 默认分支不等于 Release；
- 客户端不能制造可信身份、权限或执行上下文；
- 生产代码不得访问 Flowable `ACT_*` 内部表；
- UNKNOWN 不得盲目重试；
- AI 建议不等于审批决定。
