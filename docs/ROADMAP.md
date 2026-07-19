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
- TEXT、MONEY、ATTACHMENT 基础字段协议；
- 租户隔离的表单设计草稿、空白/模板/已发布版本复制和草稿状态生命周期；
- revision 乐观锁、自动保存幂等、过期 revision 冲突和已发布草稿不可修改；
- 草稿服务端校验、节点上下文预览、归档和低噪声审计；
- `INHERIT`、`REQUIRED`、`OPTIONAL` 节点必填覆盖及服务端有效必填校验；
- `NONE`、`LITERAL`、`CURRENT_USER`、`CURRENT_DATE`、`CURRENT_DATETIME` 安全类型化默认值；
- 默认值后端解析、Runtime 预填和不可变提交快照持久化；
- 不可变 Form Package、精确 Form/UI 版本与哈希绑定和事务联合发布；
- Flyway V9 草稿、Form Package、租户约束、精确外键和查询索引；
- PC 三栏可视化表单设计器、草稿搜索/创建、分组与字段拖拽、属性编辑、复制/删除和撤销；
- revision CAS 自动保存、服务端冲突提示与重新加载恢复；
- TEXTAREA、NUMBER、DATE、DATETIME、BOOLEAN、SELECT 静态选项字段协议、校验、哈希和 PostgreSQL JSON 往返；
- Element Plus 与移动端 H5/微信共用 Renderer 对新增字段类型的真实渲染；
- 节点字段访问/必填覆盖矩阵、服务端多节点预览和不可变 Form Package 发布确认摘要；
- 租户隔离的 Approval DSL 草稿仓库、空白/模板/已发布版本复制、搜索、归档和 revision CAS 生命周期；
- Approval DSL 服务端权威静态检查，区分 error、warning 和 info，并校验条件字段、审批人规则、受控修订回路和 UI 权限上下文；
- 正式的并行 split/join、条件与并行组合、嵌套并行拓扑和确定性 Flowable BPMN 编译；
- 不部署真实流程的服务端权威模拟器，覆盖同意、驳回、条件默认路由、Handle 回路、并行等待/join、阻塞和迁移上限；
- 不可变 Approval DSL 版本仓库、确定性 DSL 哈希、不可变 BPMN 编译制品仓库和稳定编译哈希；
- Approval DSL、Form Package、Form/UI Schema、BPMN、编译器版本和部署元数据精确绑定的不可变 Release Package；
- Release Package 版本锁定、请求幂等、语义重放、版本冲突和单事务原子发布/失败回滚；
- Flyway V10 Approval 草稿、DSL 版本、编译制品和 Release Package 表、租户边界、状态约束、精确外键和检索索引；
- PC 服务端流程设计工作台、草稿刷新恢复、显式/自动保存、冲突安全重载、撤销、复制/删除、条件/并行分支、服务端检查/模拟和发布确认；
- Release Package 到 Flowable 的显式幂等部署、平台部署投影、失败持久化、显式重试、部署审计和相同制品语义去重；
- Approval DSL 与 Release Package 版本中心、稳定分页排序、完整制品关联、部署身份和确定性结构 Diff；
- PC 版本管理页面、版本时间线、双版本比较、结构 Diff、Release Package/BPMN 只读详情和历史 DSL 新建草稿；
- 平台拥有的当前生效 Release projection、revision CAS、激活历史、版本切换和历史版本回滚；
- 新实例通过精确 Release Package、engineDefinitionId、DSL、Form Package 和编译器版本启动并持久化版本快照；
- PC 版本生效页面、激活/回滚确认摘要、变更原因、expected revision、当前生效详情和激活历史；
- Flyway V11–V13 部署、生效版本、激活历史、实例版本快照、租户边界、精确外键和 PostgreSQL 并发 CAS 约束；
- 发布和部署前服务端权威 Preflight，统一输出 errors、warnings、infos、制品哈希、编译、模拟和部署兼容性摘要；
- 确定性 preflight hash、事务内重新计算、草稿 revision/制品变化防陈旧提交和 warning 精确确认；
- 发布与部署审计记录 Preflight Hash、目标环境、确认警告、确认人和确认时间；
- BPMN 安全解析、process key 校验、Release Package 完整性复算以及编译制品精确字节保留；
- PC 流程设计器和版本中心真实 Preflight、阻断项展示、warning 确认及同 Hash 发布/部署；
- 服务端权威命名场景批量模拟，支持 1–100 个场景、稳定排序、草稿 revision/状态/租户边界检查且不部署或启动流程；
- 场景输入深度、元素、文本、决策、身份快照、预期节点和迁移次数限制，以及 `MASKED`、`FIELD_NAMES_ONLY`、`FULL` 表单值披露模式；
- 精确绑定 Approval DSL、Form Package、Form/UI Schema 版本与哈希的确定性模拟报告、稳定 `reportHash` 和可审计元数据；
- 节点、Start/End、同意/驳回、条件路由/默认路由、并行 split/branch/join、Handle 回路、阻塞和迁移上限的路径覆盖率与未覆盖项；
- PostgreSQL/Testcontainers 验证真实 JDBC 草稿/Form/UI 读取、过期 revision、跨租户隔离和模拟过程零草稿/审计写入；
- PC 批量模拟工作台、采购付款预设、场景编辑/复制、路径详情、覆盖率、基线复制以及来自同一权威报告的 JSON 复制与下载；
- PC 流程设计器基于上次服务端保存基线、本地状态和最新服务端 revision 的确定性三方 Diff、重叠路径识别和仅限非重叠修改的安全合并；
- 流程设计器保存失败状态、Form Package 版本变更跟踪、撤销/重做历史以及条件路由操作历史；
- 浏览器刷新、路由离开、草稿重新加载/切换、新建和归档操作的未保存修改或冲突保护，包含同草稿重新打开的数据丢失防护；
- 流程节点 ID、顺序、入边、出边和类型统计的缓存拓扑索引，替代设计器热点路径中的重复全量扫描；
- 100、300、500 节点拓扑构建与搜索基准门禁，以及首屏 120 节点的有界渲染和分批加载；
- 大流程拓扑基准作为 PR 验证门禁持续执行并保存可追溯日志；
- 条件与并行分支默认/手动折叠、节点名称/标识/类型搜索、多类型筛选和大流程自动折叠；
- 保存、撤销、重做和删除快捷键，输入框、可编辑内容、按钮和链接的快捷键保护；
- 服务端校验问题、模拟步骤和模拟问题的一键节点定位；
- 删除节点前的引用、重连、驳回目标和并行汇聚影响摘要，以及不安全拓扑删除阻断；
- 取消未保存修改确认时保留新建流程弹窗和用户输入。

### 下一优先级

- Approval DSL 与 Release Package 安全导入导出；
- 复合区块和可插拔自定义组件。

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
