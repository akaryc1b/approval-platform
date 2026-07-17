# Security Policy

## 报告安全问题

仓库公开后，请通过 GitHub Private Vulnerability Reporting 报告安全问题。不要在公开 Issue 中披露未修复漏洞、访问令牌、客户数据或可利用细节。

## 安全基线

- 所有外部请求必须携带可验证的身份、租户上下文和请求 ID。
- Webhook 必须支持签名、时间戳、防重放和幂等处理。
- 附件、表单字段和 AI 输入必须执行字段级权限与数据脱敏。
- 跨租户管理操作必须二次授权并完整审计。
- 运维控制台禁止直接执行任意 SQL；所有修复操作必须预演、授权和留痕。
- 密钥使用外部 Secret Manager 管理，不进入源码或普通配置表。

详细设计见 `docs/SECURITY_MODEL.md`。
