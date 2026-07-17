# ADR-0002: 审批平台独立部署

状态：Accepted

## 决策

审批核心作为独立服务运行。RuoYi-Vue-Plus 5.X、6.X 和其他宿主通过 REST、Webhook、SSO 和连接器接入。

## 原因

RuoYi 5.X 与 6.X 使用不同 Java/Spring Boot 基线。内嵌 Starter 会产生依赖冲突，并阻碍非 Java 系统接入。独立部署还能独立扩容、升级 Flowable 和执行安全隔离。
