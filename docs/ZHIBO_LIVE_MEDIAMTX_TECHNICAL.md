# ZHIBO_LIVE MediaMTX 技术说明

## 1. 信任边界

- 业务服务器是用户、业务开关、设备、套餐和次数的权威来源。
- MediaMTX 是媒体入口，只在业务服务器返回 2xx 时接收 publish。
- 客户端 Sa-Token 和登录密码不会发送给 MediaMTX。
- RTMP URL 只携带一次性随机票据；MySQL 只存其 SHA-256。
- auth/event/Control API 必须位于私网。共享服务令牌用于应用层二次校验，但不能替代网络隔离。
- MediaMTX 功能启用时 Spring 配置校验要求内部令牌至少 32 字节；缺失或过短会启动失败。

## 2. 鉴权状态机

```text
ISSUED --首次有效 HTTP auth--> AUTHORIZED --available--> LIVE --unavailable/stop--> ENDED
   └--TTL 到期--> EXPIRED
```

同一用户在一个业务中只允许一条活动会话。MySQL 生成列 `active_user_guard` 仅为活动状态生成 userId，
唯一索引 `(biz_id, active_user_guard)` 在数据库层消除并发签发竞态。

首次 auth 使用条件更新 `status=ISSUED AND ticket_expires_at>now`，同时绑定 MediaMTX connection ID。
相同连接 ID 的重复 auth 幂等放行，不同 ID 使用同一票据返回 409。

## 3. HTTP auth 判定顺序

1. `PDK_MEDIAMTX_ENABLED=true`。
2. 常量时间比较内部服务令牌。
3. token、path、connection id 非空。
4. 只允许 `action=publish`、`protocol=rtmp`。
5. path 必须匹配 `^zhibo-live/ls_[A-Za-z0-9]{16,64}$`。
6. SHA-256 查询票据并检查 TTL、path。
7. 服务端解析 appId=3，确认 ZHIBO_LIVE 在当前部署和数据库均可用。
8. 重新读取用户，检查业务归属、冻结状态、设备绑定、套餐有效期和剩余次数。
9. 原子消费票据并绑定连接。

任何异常均 fail-closed。特别是该 Controller 直接返回 `ResponseEntity<Void>`，避免项目通用异常处理把拒绝包装成 HTTP 200。

## 4. 次数一致性

申请票据和 HTTP auth 不扣次数。首次 `available` 在同一数据库事务内执行：

1. 条件更新 `AUTHORIZED -> LIVE`；
2. `remaining_calls > 0` 条件下原子减 1；
3. 若扣减失败则抛出异常，事务回滚，内部事件接口返回 409。

重复 available 看到状态已经为 LIVE，直接幂等成功，不重复扣次。unavailable 只从活动状态进入 ENDED。

## 5. 数据表与索引

`schema-mysql.sql` 直接创建最终态 `pdk_live_stream_session`，没有 ALTER 迁移段。核心约束：

| 索引 | 作用 |
| --- | --- |
| `uk_live_session_no` | 会话号不可重复 |
| `uk_live_ticket_hash` | 票据摘要不可重复 |
| `uk_live_client_request` | 同用户请求幂等键不可复用 |
| `uk_live_active_user` | 单业务单用户单活动流 |
| `uk_live_mediamtx_conn` | 一个媒体连接只绑定一个会话 |
| `idx_live_user_status` | 用户/管理后台会话查询 |
| `idx_live_ticket_expire` | 过期票据清理 |

所有用户、会话查询都包含 bizId，避免 ZHIBO_AI 与 ZHIBO_LIVE 数据串用。

## 6. MediaMTX 配置

部署固定 `bluenviron/mediamtx:1.20.1`。配置只开启 RTMP 与内网 Control API，关闭 RTSP、HLS、
WebRTC、SRT 和 MoQ；`overridePublisher=false` 防止后来的发布者替换已在线发布者。

`authHTTPExclude` 只排除 api/metrics/pprof，绝不排除 publish。Docker Compose 仅发布 1935，9997 不映射宿主机。

Hook 容器安装 curl，并将 available/unavailable 转为后端表单请求。脚本不读取、不转发、不记录 `MTX_QUERY`。

## 7. 主动停止

服务端保存 MediaMTX connection ID。用户或管理员停止时调用：

```text
POST /v3/rtmpconns/kick/{id}
```

Control API 失败时不伪造成功，返回业务错误 50371。Control API 地址只能配置为私网地址。

## 8. 已知边界和生产要求

- 当前为单 MediaMTX 节点、单用户单流 MVP，尚未实现多节点调度和节点级票据绑定。
- 当前默认 RTMP 用于本地联调；公网生产必须配置 MediaMTX RTMPS 证书、开放加密端口并扩展协议配置。
- 当前以开播次数计费；直播分钟、码率、分辨率等套餐扩展尚未实现。
- Hook 是即时通知；生产高可用阶段仍需增加 Control API 定时对账任务。
- 内部令牌目前通过 auth URL 查询参数传给 MediaMTX；反向代理和访问日志必须禁止记录该 URL，或把内部接口完全限制在容器网络。
- 用户冻结、解绑、业务关闭后的“正在直播立即踢流”需由相应业务操作调用 `revokeUserSessions`；当前手工 stop/kick 已实现，自动联动属于下一阶段。
