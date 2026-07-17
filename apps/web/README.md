# Approval Platform Web

PC 管理端将基于 Vben Admin 5.7.0 的 `web-ele` 应用导入，并使用 Element Plus。

导入原则：

- 保留上游许可证和版本记录；
- 只保留 Element Plus 应用和必要公共包；
- 审批业务代码放在独立模块；
- 流程设计器使用 Approval DSL；
- 高级流程和运维视图使用 LogicFlow；
- 不把 Vben 内部类型暴露到共享协议包。

上游代码将在独立 PR 中导入，便于审查第三方来源和补丁。
