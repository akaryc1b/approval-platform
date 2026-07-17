# 动态表单协议

平台保存自有表单协议，不把 Element Plus、Wot UI、FormCreate 或 Formily 的组件配置作为核心数据模型。

## 四层模型

- `formSchema`：字段、类型、结构和数据约束；
- `uiSchema`：布局、展示和多端提示；
- `ruleSchema`：联动、公式、校验和默认值；
- `permissionSchema`：发起和各审批节点的可见、只读、可编辑、必填权限。

## 示例

```json
{
  "schemaVersion": "1.0",
  "formKey": "purchase-payment",
  "fields": [
    {
      "key": "amount",
      "type": "MONEY",
      "label": "付款金额",
      "required": true,
      "precision": 2
    },
    {
      "key": "attachments",
      "type": "ATTACHMENT",
      "label": "付款材料",
      "multiple": true
    }
  ]
}
```

## 渲染器

- PC Element Plus Renderer；
- UniApp Wot UI Renderer；
- Readonly Renderer；
- Print/PDF Renderer。

渲染器可以扩展平台特有组件，但不得改变字段的业务数据语义。

## 版本

流程发布时绑定不可变的表单版本和字段权限版本。运行实例继续使用原快照，不受后续编辑影响。
