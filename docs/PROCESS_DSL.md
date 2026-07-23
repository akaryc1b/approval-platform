# Approval DSL

## 文档状态

本文是 Approval DSL 的 living protocol document，适用于 M4 合并后的当前基线。

- 当前正式 `main`：`58efb4255394fe3911700719669c4423a3ab212e`；
- Approval DSL、编译产物和 Release Package 已纳入版本与运行治理；
- 运行实例通过 immutable runtime binding 关联精确 release、compiler 和 engine definition；
- M4 只支持 detect-only migration assessment，不支持真实运行实例迁移执行。

相关文档：

- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`FORM_SCHEMA.md`](FORM_SCHEMA.md)
- [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md)
- [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md)

本文中的“迁移器”用于 DSL schema/protocol 的兼容升级，不代表运行中 Flowable 实例迁移能力。

## 定位

Approval DSL 是设计器、编译器、发布、版本治理和运行时证据之间的稳定产品协议。它不能直接等同于 BPMN XML，也不能暴露 Flowable 内部 ID 作为产品主模型。

## 设计目标

- 对普通审批管理员友好；
- 能表达中国式审批语义；
- 可静态验证、模拟运行和版本比较；
- 可确定性编译为 Flowable BPMN/DMN；
- 不泄漏 Flowable 内部 ID 和实现细节；
- 同一 DSL、compiler version 和依赖快照产生等价编译产物；
- 发布后形成不可变定义和 Release Package 证据；
- 新实例绑定精确 ACTIVE release，旧实例保留原 binding。

## 最小结构

```json
{
  "schemaVersion": "1.0",
  "definitionKey": "purchase-payment",
  "name": "采购付款审批",
  "start": "start",
  "nodes": [
    {
      "id": "start",
      "type": "START",
      "next": "manager"
    },
    {
      "id": "manager",
      "type": "APPROVAL",
      "assignee": {
        "resolver": "INITIATOR_MANAGER",
        "emptyPolicy": "FAIL"
      },
      "approvalMode": {
        "type": "ALL"
      },
      "next": "end"
    },
    {
      "id": "end",
      "type": "END"
    }
  ]
}
```

## 节点类型

当前产品协议包含：

`START`、`APPROVAL`、`HANDLE`、`CC`、`CONDITION`、`PARALLEL`、`SUB_PROCESS`、`TIMER`、`AUTOMATION`、`WEBHOOK`、`END`。

每个节点类型必须通过闭合 schema、静态验证和确定性编译器处理。客户端不能通过任意脚本、模块路径、原始 BPMN 片段或 Flowable internal ID 扩展节点语义。

## 静态验证

发布前至少验证：

- start 和 end 结构有效；
- node ID 唯一且格式安全；
- next/branch 引用存在；
- 不存在不可达节点；
- condition 分支完整且确定；
- parallel 和 nested branch 结构有效；
- 审批人规则存在且 empty policy 明确；
- 不存在不安全循环；
- 表单、字段权限和依赖版本可解析；
- 编译器支持所有使用的 schema 和 node type；
- 目标 Release Package 可以形成完整不可变证据。

无效设计不能发布。客户端预览或 route authority 不能绕过服务端验证。

## 编译

编译器把产品 DSL 转换为执行产物：

- BPMN；
- 可选 DMN；
- deterministic element IDs；
- validation report；
- compiler identity and version；
- canonical content hash；
- artifact hash。

同一 canonical DSL、相同 Form Package 和相同 compiler version 必须产生等价产物。JSON 输入字段顺序或无关表示差异不能造成非确定性发布身份。

## 发布产物

每次正式发布保存：

- 原始 canonical DSL；
- Definition version；
- 编译后的 BPMN/DMN；
- compiler version；
- 静态验证报告；
- 审批人规则和组织解析协议快照；
- Form Package、Form Schema、UI Schema 和 permission version/hash；
- 内容哈希、artifact hash 和 package hash；
- 发布人、requestId、traceId 和 audit evidence；
- deployment 和 engine definition evidence；
- release lifecycle evidence。

已发布 Definition 和 Release Package 不得原地修改。修改设计必须生成新版本和新 Release Package。

## Release Lifecycle 关系

持久化 release lifecycle 是闭合状态机：

- `PUBLISHED`；
- `ACTIVE`；
- `DEPRECATED`；
- `RETIRED`。

`DRAFT` 表示发布前设计阶段。

同一 tenant + definition 最多一个 `ACTIVE` release。普通新实例只能从 exact ACTIVE release 启动。

生命周期变化不修改 DSL、编译产物或 Release Package 内容。

## Runtime binding

release-bound 实例保存 immutable runtime binding，包括：

- tenant 和 approval instance identity；
- definition key/version；
- release version/package hash；
- form/UI/compiler/artifact hashes；
- deployment 和 engine definition identity；
- bindingEvidenceHash；
- server-owned operator、requestId、traceId、audit reference 和 timestamp。

已有实例在新 release 激活后继续保留原 binding。读取和 replay 必须验证 binding 与平台 instance projection 一致。缺失或冲突证据 fail closed。

## 版本比较与 detect-only assessment

M4 可以比较 source 和 target 已发布定义 topology，并结合 runtime binding 和 active task projections 生成 migration assessment。

评估可以发现：

- source/target release 或 package 缺失；
- target deployment 不可用；
- active task definition key 在 target 中不存在；
- runtime binding 缺失或不一致；
- task transition 正在进行；
- paging 不完整；
- 结构性高影响变化。

评估输出 reportHash 和每实例 bindingEvidenceHash，但它不是 executable migration plan，不调用 Flowable 迁移 API，不修改实例、任务或 binding。

## 兼容性

DSL 字段只允许向后兼容增加。破坏性协议变化必须：

1. 提升 `schemaVersion`；
2. 提供显式 DSL schema migration；
3. 保留旧版本 reader/compiler 或明确升级路径；
4. 增加 golden compilation tests；
5. 证明旧发布产物仍可复现；
6. 不重写已发布 DSL、artifact 或 Release Package；
7. 不将 DSL schema migration 与运行实例迁移混为一谈。

## 安全边界

- 不接受任意脚本、EL、模板代码或动态模块路径；
- 不接受客户端提供 trusted tenant、operator、permission、audit 或 engine identity；
- 不通过 BPMN/DSL 字段读取或修改 Flowable `ACT_*` 表；
- 不允许浏览器指定任意 engine activity mapping 并直接执行迁移；
- 发布、部署、激活和未来迁移均必须经过独立治理能力和审计门禁。
