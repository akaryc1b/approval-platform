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
- 通用表单提交、服务端权威校验和不可变数据快照；
- 表单 Key/精确版本到流程启动器的绑定；
- 移动端真实发布表单发起和审批详情快照；
- 租户隔离、不可变的 UI Schema 版本仓库；
- UI Schema 与精确 Form Schema Key/版本强绑定；
- 字段顺序、区块、placeholder、helpText、跨度和默认折叠协议；
- `EDITABLE`、`READONLY`、`HIDDEN` 节点字段权限；
- 发起、普通审批和发起人修改节点的后端权威权限解析；
- 提交、审批详情和重提的字段权限校验；
- UI Schema 版本/哈希固定与不可变表单修订快照；
- 无 UI Schema 历史表单的安全默认行为；
- PC Form/UI Schema JSON 工作台和 Element Plus Renderer；
- Wot UI 移动 Renderer、H5 和微信小程序共用协议；
- TEXT、MONEY、ATTACHMENT 基础字段协议。

### 下一优先级

- 可视化表单设计器 MVP；
- 节点字段必填覆盖和字段级默认值表达式；
- 树状审批设计器、条件/并行分支和静态检查；
- 流程模拟器和 DSL 发布联动；
- 更多字段类型、复合区块和可插拔自定义组件；
- UI Schema 与流程版本发布编排。

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
