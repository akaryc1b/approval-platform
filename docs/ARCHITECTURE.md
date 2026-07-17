# 总体架构

## 系统形态

Approval Platform 是独立服务，不作为 RuoYi 或其他宿主项目的内嵌工作流模块。

```text
Host Systems
  REST / Webhook / SDK / SSO
              │
              ▼
Integration & Connector Layer
              │
              ▼
Approval Product Layer
  Process · Form · Task · Agent · Message · Audit · Ops · AI
              │
              ▼
Approval DSL Compiler / Engine SPI
              │
              ▼
Flowable 8
```

## 分层

### Domain

纯 Java 领域模型和业务规则。禁止依赖 Spring、Flowable、数据库和宿主框架。

### Application

编排用例、事务边界、权限检查、幂等和领域事件。只依赖 Domain 和 SPI。

### Engine SPI

定义启动、办理、迁移、暂停、恢复和任务协作等抽象。Flowable 仅作为一个实现。

### Infrastructure

数据库、缓存、消息、对象存储、搜索、AI Provider 和外部 API 实现。

### Connector

负责身份、组织、文件、消息、业务回调和第三方待办映射。连接器不得改变审批核心语义。

## 关键约束

- Approval DSL 是产品主模型；BPMN/DMN 是编译产物。
- 平台业务表是查询与审计主入口；Flowable 表仅由引擎适配层访问。
- 跨系统状态同步采用 Transactional Outbox、Inbox 去重和签名 Webhook。
- 流程发布后不可修改，修改产生新版本。
- 运行实例绑定流程、表单、规则和组织解析快照。

## 部署

- 单体模块化部署：适合早期与中小规模；
- 水平扩展集群：无状态 API + 数据库 + Redis + 对象存储；
- 后续可按消息、AI、报表和运维任务拆分，但不提前微服务化。
