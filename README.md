# Approval Platform

[中文](#项目定位) | [English](#english-summary)

生产级、可独立部署的通用审批与流程协作平台。

基于 Flowable 流程执行内核，提供类似钉钉 / 飞书审批体验的流程设计器、动态表单、审批工作台、任务协作、流程运维、AI 辅助能力以及可插拔连接器。

> 当前状态：M2 Designer & Forms、M3 Collaboration Governance 已完成。平台已具备流程设计、动态表单、版本治理、审批协作、委派治理、通知治理和生产级运维能力。

## 项目定位

Approval Platform 不是业务系统内的审批模块，也不是 Flowable 的简单封装。

设计原则：

- 业务系统不直接依赖 Flowable API
- 不直接操作 Flowable ACT_* 表
- 审批领域模型与流程引擎解耦
- Approval DSL 是产品模型，BPMN 是执行产物
- 表单 Schema 独立于前端组件

支持接入：

- RuoYi-Vue-Plus 5.x / 6.x
- Spring Boot 项目
- 微服务系统
- 非 Java 系统
- 钉钉、飞书等第三方平台

## 技术基线

- 后端：Java 21、Spring Boot 4、Flowable 8
- PC：Vue 3、TypeScript、Vben Admin、Element Plus
- 移动端：UniApp Vue 3、Unibest、Wot UI
- 流程：Approval DSL 编译 BPMN / DMN
- 表单：Form Schema + 多端 Renderer
- 数据库：PostgreSQL
- 构建：Maven 多模块 + pnpm workspace
- License：Apache License 2.0

## 核心能力

### 流程设计

- 可视化审批设计器
- Approval DSL
- BPMN / DMN 编译
- 流程版本管理
- 发布与生效版本控制
- 实例绑定流程快照

### 动态表单

- Schema 驱动表单
- PC / H5 / 微信多端 Renderer
- 字段联动
- 节点字段权限
- 复合表单区块

### M3 协作治理

已完成：

- 审批评论
- 回复与讨论
- @人员
- 附件协作
- 评论编辑与删除审计
- 可见范围控制
- 协作权限治理

### 委派治理

支持：

- 审批任务委派
- 全局委派
- 指定流程委派
- 委派生命周期管理
- 委派关系审计

### 通知治理

支持：

- 审批事件通知
- 通知策略管理
- 消息一致性治理
- 失败处理机制
- 可扩展通知渠道

## Monorepo 结构

```text
approval-platform/
├── apps/
│   ├── server/
│   ├── web/
│   ├── mobile/
│   └── docs-site/
├── server-modules/
├── packages/
├── connectors/
├── deploy/
├── examples/
├── docs/
└── scripts/
```

## 当前里程碑

### M2 Designer & Forms ✅

- Approval DSL
- 动态表单
- Form Package
- 流程发布
- 生效版本管理
- Preflight 校验
- 冲突保护
- 安全导入导出
- 节点字段权限
- 多端 Renderer

### M3 Collaboration Governance ✅

- 评论协作体系
- 附件协作
- 委派治理
- 通知治理
- 审计一致性
- 权限边界治理
- 性能门禁
- 生产验收体系

## Roadmap

### M4 Intelligence

规划：

- AI 审批助手
- 材料检查
- 风险分析
- 智能审批建议

### M5 Ecosystem

规划：

- 更多连接器
- SDK
- 流程模板中心

## 文档

- `docs/PRODUCT_CHARTER.md`
- `docs/ARCHITECTURE.md`
- `docs/PROCESS_DSL.md`
- `docs/FORM_SCHEMA.md`
- `docs/OPERATIONS.md`

## English summary

Approval Platform is a production-oriented standalone approval and workflow platform built on Flowable.

It provides workflow designer, dynamic forms, approval workspace, collaboration, delegation, notification governance, audit management and extensible connectors.

The platform keeps business models independent from Flowable and supports integration through REST API, Webhook, SDK and connectors.

## License

Apache License 2.0
