# Approval Platform

[中文](#项目定位) | [English](#english-summary)

生产级、可独立部署、面向中国式审批场景的通用审批平台。项目以 Flowable 为流程执行内核，提供仿钉钉/飞书的审批设计体验、动态表单、审批工作台、复杂任务协作、流程运维、AI 辅助审批和可插拔连接器。

> 当前状态：M1 首条纵向业务链路阶段。M0 架构、工程、连接器与多端基础已建立，当前优先完成采购付款审批的 PC、移动端和后端真实闭环。

## 项目定位

本项目不是 RuoYi-Vue-Plus 的附属模块，也不让业务系统直接依赖 Flowable API。平台以独立服务运行，通过 REST、Webhook、SDK 和 Connector 接入：

- RuoYi-Vue-Plus 5.X / 6.X
- 普通 Spring Boot 项目
- 微服务与非 Java 系统
- 钉钉、飞书及其他第三方办公平台

目标是对齐钉钉、飞书审批领域公开可验证的通用能力，但不复制其商标、图标、源码或像素级视觉设计。

## 技术基线

- 后端：Java 21、Spring Boot 4、Flowable 8
- PC：Vue 3、TypeScript、Vben Admin `web-ele`、Element Plus
- 移动端：UniApp Vue 3、Unibest、Wot UI
- 流程：自研 Approval DSL，编译为 BPMN / DMN
- 表单：自研 Form Schema，多端独立 Renderer
- 数据库：PostgreSQL 作为标准主库；MySQL 8 为后续兼容目标
- 构建：Maven 多模块 + pnpm workspace
- 许可：Apache License 2.0

## Monorepo 结构

```text
approval-platform/
├── apps/
│   ├── server/                 # 后端启动应用
│   ├── web/                    # PC 管理端
│   ├── mobile/                 # UniApp 移动端
│   └── docs-site/              # 文档站
├── server-modules/             # Java 领域与基础设施模块
├── packages/                   # PC、移动端共享 TypeScript 包
├── connectors/                 # 通用与宿主系统连接器
├── deploy/                     # Docker、Compose、Kubernetes、Helm
├── examples/                   # 接入示例
├── docs/                       # 产品与架构文档
└── scripts/                    # 工程脚本
```

## 核心原则

1. 业务系统不得直接访问 Flowable `ACT_*` 表或调用 Flowable Service。
2. 领域层不得依赖 Flowable、RuoYi、Sa-Token 或具体 UI 框架。
3. 设计器保存 Approval DSL，BPMN 是编译产物而不是产品主模型。
4. 表单保存平台自有 Schema，Element Plus 与 Wot UI 只是渲染器。
5. 所有外部写请求必须支持租户上下文、幂等键、请求 ID 和审计。
6. 流程发布后形成不可变版本，运行实例必须绑定流程和表单快照。
7. 通用能力进入开源核心，公司和客户专用逻辑通过私有扩展实现。

## 计划能力

- 仿钉钉/飞书树状审批设计器
- 动态表单、字段联动、节点字段权限
- 顺序会签、并行会签、或签、票签和权重审批
- 任意驳回、撤回、拿回、转办、委派、协办
- 前加签、后加签、减签、代理和离职转办
- 抄送、催办、评论、@、附件和已阅
- 工作日历、SLA、超时提醒和自动处理
- 流程版本、迁移、异常恢复和运维控制台
- AI 材料检查、风险分析、审批建议和意见生成
- RuoYi 5/6、钉钉、飞书和通用 REST 连接器

## 当前里程碑

当前聚焦 `M1 First Vertical Slice`：

- 采购付款流程发布、发起和条件/并行路由
- 动态审批人解析与不可变身份快照
- 平台任务投影、幂等、并发和全链路审计
- 待办列表、待办详情和审批时间线查询
- PC 与移动端接入真实 API
- 驳回、撤回、拿回、转办、催办、抄送和已阅
- 完成后签名业务回调与失败重试

详细规划见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。

## 贡献

提交代码前请先阅读：

- [`docs/PRODUCT_CHARTER.md`](docs/PRODUCT_CHARTER.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/PROCESS_DSL.md`](docs/PROCESS_DSL.md)
- [`docs/FORM_SCHEMA.md`](docs/FORM_SCHEMA.md)
- [`CONTRIBUTING.md`](CONTRIBUTING.md)

## English summary

Approval Platform is a production-oriented, standalone approval and workflow product built on Flowable. It provides a DingTalk/Feishu-inspired approval designer, dynamic forms, workbench, Chinese-style task collaboration, operations tooling, AI-assisted review, and pluggable connectors. The product model remains independent from Flowable and host frameworks.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
