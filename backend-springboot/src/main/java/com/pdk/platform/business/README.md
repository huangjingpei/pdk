# 平台业务上下文入口

`BusinessRequestResolver` 是客户端公开 `appId` 进入平台的统一入口，不包含 PDD 专属规则。

当前兼容阶段：

- Header：`X-PDK-App-ID`。
- 短信、注册、登录、改密和卡密激活请求体同时接受 `appId`。
- Header 与 Body 同时存在时必须一致。
- 缺省 appId 暂按现有 PDD 的 `1` 处理。
- 当前部署只包含 PDD，其他 appId 返回 `50350`。
- 解析结果写入 request attribute：`pdkAppId`、`pdkBizCode`。

后续新增 `pdk_business` 后，本类升级为 `appId -> BusinessContext` 查询和缓存入口；Controller、URL
以及各语言 SDK 不需要再次改路径。
