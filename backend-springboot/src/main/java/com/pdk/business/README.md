# 业务扩展目录

本目录只承载不同 `bizCode` 的差异实现，不复制用户、登录、套餐、卡密、扣次、财务和审计等平台公共能力。

- `spi/`：业务扩展契约与 Handler 注册表。
- `pdd/`：`appId=1 / bizCode=PDD` 的现有拼多多实现。
- 后续业务使用独立目录：`zhiboai/`、`zhibolive/`。

统一 Controller 和公共 Service 通过 `BusinessHandlerRegistry` 查找 Handler。客户端协议已由
`com.pdk.platform.business.BusinessRequestResolver` 接受并校验 `X-PDK-App-ID/appId`；当前兼容阶段
只支持 appId=1，调度服务仍固定选择 `PDD`。接入 `pdk_business` 与完整 `BusinessContext` 后，只替换
Handler 选择来源，不复制公共事务流程。

业务目录允许包含：动作校验、凭证编码、失败分类、资源健康检查和业务专属 payload。禁止在业务目录复制用户鉴权、套餐卡密、财务和数据库通用 CRUD。
