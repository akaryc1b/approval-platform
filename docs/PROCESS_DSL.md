# Approval DSL

Approval DSL 是设计器、编译器和版本管理之间的稳定协议。它不能直接等同于 BPMN XML。

## 设计目标

- 对普通审批管理员友好；
- 能表达中国式审批语义；
- 可静态验证、模拟运行和版本迁移；
- 可编译为 Flowable BPMN/DMN；
- 不泄漏 Flowable 内部 ID 和实现细节。

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

`START`、`APPROVAL`、`HANDLE`、`CC`、`CONDITION`、`PARALLEL`、`SUB_PROCESS`、`TIMER`、`AUTOMATION`、`WEBHOOK`、`END`。

## 发布产物

每次发布保存：

- 原始 DSL；
- 编译后的 BPMN/DMN；
- 编译器版本；
- 静态验证报告；
- 审批人规则快照；
- 表单和字段权限版本；
- 内容哈希和发布人。

## 兼容性

DSL 字段只允许向后兼容增加。破坏性变化必须提升 `schemaVersion` 并提供迁移器和黄金测试。
