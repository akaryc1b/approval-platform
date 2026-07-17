# Approval Platform Mobile

移动端将基于 Unibest 4.4.1 + Wot UI v2 建立，目标平台包括 H5、微信小程序、钉钉小程序和 App。

审批表单使用平台自有 Form Schema，通过 Wot UI Renderer 渲染。PC 和移动端共享协议、校验和权限逻辑，但不共享 Vue 页面组件。

上游脚手架将在独立 PR 中生成并导入。
