# 动态表单协议

## 文档状态

本文是 Form Schema、UI Schema、ruleSchema 和 permissionSchema 的 living protocol document，适用于 M4 合并后的当前基线。

- 当前正式 `main`：`58efb4255394fe3911700719669c4423a3ab212e`；
- Form/UI version 和 content hash 已纳入 immutable Form Package 与 Approval Release Package；
- release-bound instance 保存精确 form/UI binding evidence；
- PC、H5 和微信小程序共享服务端验证和权限语义；
- 客户端 Renderer、禁用状态和隐藏状态不是安全边界。

相关文档：

- [`PROCESS_DSL.md`](PROCESS_DSL.md)
- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)

## 定位

平台保存自有表单协议，不把 Element Plus、Wot UI、FormCreate、Formily 或任意前端组件配置作为核心数据模型。

前端组件是 Renderer 实现。业务数据类型、约束、权限、版本和哈希由平台协议与服务端校验决定。

## 四层模型

- `formSchema`：字段、类型、数据约束、静态选项和安全默认值；
- `uiSchema`：区块树、顺序、布局、展示提示、受控可见性和组件描述；
- `ruleSchema`：受控联动、公式和跨字段业务规则；
- `permissionSchema`：发起和各审批节点的隐藏、只读、可编辑及必填覆盖。

Form Schema 与 UI Schema 分别形成不可变版本和内容哈希。Form Package 精确绑定 Form/UI version 与 hash，Approval Release Package 再绑定 exact Form Package。release-bound instance 保留该发布快照。

## Form Schema

当前字段协议包含：

- `TEXT`、`TEXTAREA`；
- `MONEY`、`NUMBER`；
- `DATE`、`DATETIME`；
- `BOOLEAN`、`SELECT`；
- `ATTACHMENT`。

字段业务类型决定服务端数据校验和存储语义。UI 组件不得改变：

- 字段业务类型；
- 必填约束；
- 数值精度和范围；
- 文本长度；
- 静态选项；
- 附件数量、大小或类型约束；
- 服务端默认值语义。

客户端提交必须按 exact bound schema 重新验证。旧客户端、未知字段或禁用控件不能绕过服务端约束。

## 复合 UI 区块

UI Schema 支持最多四层的递归 Section 树。每个 Section 包含：

- 全局唯一且安全的 `key`；
- 标题和可选帮助说明；
- 同级 `order`；
- 1–4 列布局；
- 是否允许折叠和默认折叠状态；
- `readonlySummary` 只读摘要模式；
- 字段布局列表；
- 子 Section 列表；
- 受控可见性条件。

字段在整个 Section 树中只能出现一次。服务端拒绝：

- 重复 Section key；
- 重复字段归属；
- 乱序或重复同级顺序；
- 循环、共享或复用节点；
- 超过深度或数量限制；
- 引用缺失字段；
- 不安全组件描述或未知协议版本。

`readonlySummary` 只能降低权限。它可以把 `EDITABLE` 降为 `READONLY`，不能把 `HIDDEN` 或 `READONLY` 提升为可编辑。没有服务端默认值的发起必填字段不得被区块降为不可编辑。

## 受控可见性

Section 只支持封闭条件协议：

- `ALWAYS`；
- `FIELD_EQUALS`；
- `FIELD_NOT_EMPTY`。

条件只能引用当前 Form Schema 字段，比较值只允许有界字符串、数字或布尔值。

协议不执行：

- JavaScript；
- 表达式语言；
- 模板代码；
- 正则脚本；
- 动态 import；
- 远程查询；
- 任意 URL 或 HTML。

可见性是展示语义，不替代字段访问权限和服务端提交校验。隐藏字段仍由 permissionSchema 决定是否可以读取或提交。

## 白名单组件协议

字段布局可以附带闭合组件描述：

- `componentType`；
- `componentVersion`；
- 受组件注册表限制的有界属性；
- 安全只读 fallback。

当前注册表接受版本 `1` 的组件：

基础组件：

- `TEXT`、`TEXTAREA`；
- `MONEY`、`NUMBER`；
- `DATE`、`DATETIME`；
- `BOOLEAN`、`SELECT`；
- `ATTACHMENT`。

业务组件：

- `BUSINESS_REFERENCE`；
- `USER_SELECTOR`；
- `DEPARTMENT_SELECTOR`。

业务选择器复用受控服务端数据语义。注册表校验组件和字段类型兼容性、版本、属性 key、属性类型和大小。

组件描述禁止包含模块路径、动态 import、URL、HTML、脚本、事件处理器或可执行表达式。客户端只能从宿主应用预注册组件集合中解析。

未知历史组件或当前端不支持的组件必须使用 `READONLY_TEXT` 或 `READONLY_JSON` fallback，不能动态加载代码。

## 节点字段权限

每个权限上下文必须显式覆盖全部字段：

- `EDITABLE`：当前上下文可以提交修改；
- `READONLY`：可以读取，但服务端拒绝修改；
- `HIDDEN`：运行时视图不暴露，服务端拒绝提交。

必填覆盖包含：

- `INHERIT`；
- `REQUIRED`；
- `OPTIONAL`。

服务端将以下约束合并为最终权限：

- node permission；
- Section `readonlySummary`；
- Form Schema required constraint；
- server-owned default value；
- current task and process state；
- immutable release/runtime binding。

PC、H5 和微信小程序的 disabled、readonly 或 hidden 状态只用于交互反馈。服务端权限和 exact bound schema 是安全边界。

## 确定性哈希与持久化

Form Schema 和 UI Schema 使用 canonical content 计算 deterministic hash。

UI Schema hash 包含：

- Section 层级；
- 同级顺序；
- 布局；
- 可见性；
- readonly summary；
- component type/version/fallback；
- 按 key 排序的组件属性；
- node permissions。

组件属性 JSON 输入顺序不影响 hash，区块顺序和有效协议变化会改变 hash。

PostgreSQL JSON 往返测试覆盖：

- 递归区块；
- 组件描述；
- 受控可见性；
- 字段权限；
- deterministic hash；
- immutable version conflicts。

发布后同一 Form/UI version 不得保存不同内容。修改必须生成新 version、Form Package 和 Release Package。

## Form Package 和 Release Package

Form Package 精确绑定：

- Form Schema version/hash；
- UI Schema version/hash；
- rule and permission protocol evidence；
- package identity and hash；
- publication evidence。

Approval Release Package 再绑定：

- exact Form Package；
- Approval DSL definition；
- compiler and compiled artifact；
- deployment evidence；
- release lifecycle identity。

package hash possession 不授权跨租户读取。所有 lookup 和 mutation 必须包含 authenticated tenant scope。

## Runtime binding

release-bound instance 保存 exact form evidence：

- form package version/hash；
- form schema version/hash；
- UI schema version/hash；
- release package hash；
- compiler/artifact/deployment identity；
- bindingEvidenceHash。

有效 release 切换不会改变已有实例的 Form/UI 语义。已有任务继续使用原 binding 的 schema 和 permission evidence。

missing 或 conflicting binding 必须 fail closed，而不是改用当前最新表单或由客户端补齐 hash。

## 多端渲染

- PC：Element Plus Renderer；
- H5 / 微信小程序：UniApp + Wot UI Renderer；
- 不支持组件：安全只读 fallback；
- 后续可以增加打印/PDF Renderer。

跨端共享：

- Section 顺序；
- 条件可见性；
- 字段隐藏；
- 必填；
- readonly 降级；
- exact schema validation；
- release/runtime binding。

各端可以采用适合平台的布局和控件，但不能改变业务类型、权限和服务端校验。

## 兼容性

现有平面 Section 构造器和 JSON 字段保持兼容；缺失的新字段使用安全默认值。

未来新增组件或协议版本必须：

1. 显式注册；
2. 保持旧 version reader；
3. 增加兼容性和 round-trip tests；
4. 经过服务端重新校验和重新 hash；
5. 保留已发布 Form Package；
6. 禁止未知组件版本和未知属性被静默接受；
7. 验证 PC、H5、微信小程序和只读 fallback；
8. 不重写历史 runtime binding。

## 安全边界

- 表单协议不执行任意代码；
- client-provided component metadata 不成为模块加载指令；
- client state 不替代服务端 permission；
- client 不能提供 trusted tenant、operator、permission、audit 或 binding identity；
- attachment 和 business reference 必须经过 tenant、instance 和 visibility authorization；
- 表单校验和运维不得查询或修改 Flowable `ACT_*` 表；
- migration assessment 不允许通过表单字段制造运行实例迁移命令。
