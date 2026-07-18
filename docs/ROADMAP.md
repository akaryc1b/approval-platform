# Roadmap

## M0 Foundation — complete

- Monorepo 和构建基线；
- 产品章程、架构、DSL、表单和连接器协议；
- Java 领域、应用、Engine SPI 和 Flowable 适配；
- TypeScript contracts、process-dsl 和 form-schema 包；
- CI、依赖、安全、多租户和质量门禁；
- Vben PC、Unibest 移动端和 Generic REST 宿主基础；
- RuoYi-Vue-Plus 5.X / 6.X 宿主 Starter。

## M1 First Vertical Slice — complete

采购付款审批已形成可运行闭环：

- 版本化 Approval DSL、Form Schema、BPMN 编译与 Flowable 部署；
- 动态审批人解析和不可变身份快照；
- 发布、发起、条件分支、并行会签和完成回调；
- 同意、驳回到发起人、修改重提、撤回、拿回和转办；
- 催办、抄送、消息中心、已读回执、评论和 @提及；
- 一层评论回复、真实附件上传与受权限控制的下载；
- 平台实例/任务投影、幂等、并发、审计与 Outbox；
- PC、H5 和微信小程序真实接口工作台与协作页面。

## M2 Designer and Forms — in progress

### 已完成

- 租户隔离、不可变的 Form Schema 版本仓库；
- Form Schema 服务端校验、确定性内容哈希和幂等发布；
- 表单版本列表、搜索、详情与采购付款模板 API；
- PC JSON 编辑、服务端校验、发布和 Element Plus 运行时预览；
- 移动端真实已发布表单列表和 Wot UI 运行时 Renderer；
- TEXT、MONEY、ATTACHMENT 基础字段协议。

### 下一优先级

- 通用表单实例提交、校验结果和不可变数据快照；
- 将采购付款发起页迁移到通用 Form Schema 提交链路；
- UI Schema、布局、默认值、帮助文本和只读展示；
- 节点级字段可见、可编辑和必填权限；
- 可视化表单设计器 MVP；
- 树状审批设计器、条件/并行分支和静态检查；
- 流程模拟器和 DSL 发布联动。

## M3 Collaboration

- 顺序/并行会签、或签、票签、权重；
- 前后加签、减签、委派和协办；
- 代理、离职转办和流程交接；
- 评论治理、消息渠道和通知偏好。

## M4 Operations

- 工作日历、SLA、超时升级和自动处理；
- 运维控制台、Job 重试、流程跳转和补偿；
- 流程版本迁移和批次预演；
- 监控、告警、容量和数据清理。

## M5 Ecosystem and AI

- 钉钉、飞书连接器；
- SDK、模板市场和第三方组件；
- AI 材料检查、摘要、风险和意见建议；
- 受控自动审批和 AI 审计。
