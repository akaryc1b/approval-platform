# Roadmap

## M0 Foundation — substantially complete

- Monorepo 和构建基线；
- 产品章程、架构、DSL、表单和连接器协议；
- Java 领域、应用、Engine SPI 和 Flowable 适配；
- TypeScript contracts、process-dsl 和 form-schema 包；
- CI、依赖、安全、多租户和质量门禁；
- Vben PC、Unibest 移动端和 Generic REST 宿主基础；
- RuoYi-Vue-Plus 5.X / 6.X 宿主 Starter。

## M1 First Vertical Slice — in progress

以采购付款审批为样板：

### 已完成

- 版本化 Approval DSL 和 Form Schema；
- DSL 编译、Flowable 部署和真实条件/并行路由；
- 发布、发起、同意命令；
- 动态审批人解析和不可变身份快照；
- 平台实例/任务投影、幂等、并发和审计；
- 签名业务完成回调和 Outbox 重试基础；
- PC 与移动端工程和页面骨架；
- 待我审批列表、待办详情和审批时间线读取模型。

### 当前优先级

- PC 待办、详情和同意操作接入真实 API；
- 移动端待办、详情和同意操作接入真实 API；
- 驳回到发起人、任意退回、撤回、拿回和转办；
- 催办、抄送、已阅、评论和附件；
- 完整采购付款端到端验收与权限加固。

## M2 Designer and Forms

- 树状审批设计器；
- 条件/并行分支和静态检查；
- 表单设计器、联动、公式和节点权限；
- DSL 编译器和流程模拟器。

## M3 Collaboration

- 顺序/并行会签、或签、票签、权重；
- 前后加签、减签、转办、委派和协办；
- 代理、离职转办和流程交接；
- 评论、@、附件、已阅和多渠道消息。

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
