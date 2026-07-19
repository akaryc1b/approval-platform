# 动态表单协议

平台保存自有表单协议，不把 Element Plus、Wot UI、FormCreate、Formily 或任意前端组件模块配置作为核心数据模型。

## 四层模型

- `formSchema`：字段、类型、数据约束、静态选项和安全默认值；
- `uiSchema`：区块树、顺序、布局、展示提示、受控可见性和组件描述；
- `ruleSchema`：后续的联动、公式和跨字段业务规则；
- `permissionSchema`：发起和各审批节点的隐藏、只读、可编辑及必填覆盖。

Form Schema 与 UI Schema 分别形成不可变版本和内容哈希。Form Package 精确绑定 Form/UI 版本与哈希，流程发布和运行实例继续绑定该快照。

## Form Schema

当前字段协议包含：

- `TEXT`、`TEXTAREA`；
- `MONEY`、`NUMBER`；
- `DATE`、`DATETIME`；
- `BOOLEAN`、`SELECT`；
- `ATTACHMENT`。

字段业务类型决定服务端数据校验和存储语义。UI 组件不得改变字段的业务数据类型、必填约束、数值精度、最小值、文本长度、静态选项或附件约束。

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

字段在整个 Section 树中只能出现一次。服务端拒绝重复 Section Key、重复字段归属、乱序或重复同级顺序、循环/复用节点、超过深度或数量限制以及缺失字段。

`readonlySummary` 只能降低权限。它会在服务端校验、设计预览、发起、任务运行时和重提中把原本 `EDITABLE` 的字段降为 `READONLY`，但不能把 `HIDDEN` 或 `READONLY` 字段提升权限。没有服务端默认值的发起必填字段不得被区块降为不可编辑。

## 受控可见性

Section 只支持封闭条件协议：

- `ALWAYS`；
- `FIELD_EQUALS`；
- `FIELD_NOT_EMPTY`。

条件只能引用当前 Form Schema 的字段，比较值只允许有界的字符串、数字或布尔值。协议不执行 JavaScript、表达式语言、模板代码、正则脚本或远程查询。

可见性是展示语义，不替代字段访问权限和服务端提交校验。隐藏字段仍由节点权限决定是否可读取或提交。

## 白名单组件协议

字段布局可以附带一个闭合的组件描述：

- `componentType`；
- `componentVersion`；
- 受组件注册表限制的惰性属性；
- 安全只读 fallback。

当前注册表仅接受版本 `1` 的以下组件：

- 基础组件：`TEXT`、`TEXTAREA`、`MONEY`、`NUMBER`、`DATE`、`DATETIME`、`BOOLEAN`、`SELECT`、`ATTACHMENT`；
- 业务组件：`BUSINESS_REFERENCE`、`USER_SELECTOR`、`DEPARTMENT_SELECTOR`。

业务选择器复用 `TEXT` 的服务端数据语义。注册表校验组件与字段类型的兼容关系、版本和属性 Key/值的大小与类型。

组件描述禁止包含模块路径、动态 import、URL、HTML、脚本、事件处理器或可执行表达式。客户端只能从宿主应用预注册的组件集合中解析。未知历史组件或当前端不支持的组件必须使用 `READONLY_TEXT` 或 `READONLY_JSON` fallback，不能动态加载代码。

## 节点字段权限

每个权限上下文必须显式覆盖全部字段：

- `EDITABLE`：当前上下文可提交修改；
- `READONLY`：可见但服务端拒绝修改；
- `HIDDEN`：不向运行时视图暴露且服务端拒绝提交。

必填覆盖包含 `INHERIT`、`REQUIRED`、`OPTIONAL`。服务端将节点权限、区块只读约束、Form Schema 必填约束和安全默认值合并为最终权限。PC、H5 和微信小程序的禁用状态仅用于交互反馈，不能作为安全边界。

## 确定性哈希与持久化

UI Schema 哈希包含区块层级、同级顺序、布局、可见性、只读摘要、组件类型/版本/fallback、按 Key 排序后的组件属性以及节点权限。组件属性的 JSON 输入顺序不影响哈希，区块顺序变化会改变哈希。

PostgreSQL JSON 往返测试覆盖递归区块、组件描述、可见性、权限和稳定哈希。发布后同一 UI Schema 版本不得保存不同内容。

## 多端渲染

- PC：Element Plus Renderer；
- H5 / 微信小程序：UniApp + Wot UI Renderer；
- 不支持组件：安全只读 fallback；
- 后续：打印/PDF Renderer。

跨端共享相同的 Section 排序、条件可见性、字段隐藏、必填和只读降级语义，但各端可以采用适合平台的布局与交互控件。

## 兼容性

现有平面 Section 构造器和 JSON 字段保持兼容；缺失的新字段使用安全默认值。未来新增组件或协议版本必须显式注册、增加兼容性测试，并经过完整服务端重新校验和重新哈希。未知组件版本和未知属性不会被静默接受。
