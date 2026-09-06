# ZHIBO_LIVE MediaMTX 技术说明

## 1. 信任边界

- 业务服务器是用户、业务开关、设备许可证、独立到期时间和次数的权威来源。
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

同一设备许可证只允许一条活动会话。同一手机号的不同许可证可以各自推流。MySQL 生成列
`active_subject_guard` 为活动状态生成 `userId:deviceLicenseId`，唯一索引
`(biz_id, active_subject_guard)` 在数据库层消除并发签发竞态。

首次 auth 使用条件更新 `status=ISSUED AND ticket_expires_at>now`，同时绑定 MediaMTX connection ID。
相同连接 ID 的重复 auth 幂等放行，不同 ID 使用同一票据返回 409。

## 3. HTTP auth 判定顺序

1. `PDK_MEDIAMTX_ENABLED=true`。
2. 常量时间比较内部服务令牌。
3. 根据 `nodeCode` 读取数据库节点，校验厂商类型和节点状态。
4. path、connection id 非空；`action=read` 时仅允许读取该节点上处于 AUTHORIZED/LIVE 的 path。
5. publish 只允许 RTMP，path 必须匹配 `^zhibo-live/ls_[A-Za-z0-9]{16,64}$`。
6. 从新版 `token` 字段或 MediaMTX v1.11.3 的 `query` 提取票据，按 SHA-256 查询并检查 TTL、path 和节点绑定。
7. 服务端解析 appId=3，确认 ZHIBO_LIVE 在当前部署、数据库和健康节点层面均可用。
8. 重新读取用户、设备和许可证，检查业务归属、冻结状态、绑定关系、许可证状态、独立到期时间和剩余次数。
9. 原子消费票据并绑定媒体连接。

任何异常均 fail-closed。特别是该 Controller 直接返回 `ResponseEntity<Void>`，避免项目通用异常处理把拒绝包装成 HTTP 200。

## 4. 次数一致性

申请票据和 HTTP auth 不扣次数。首次 `available` 在同一数据库事务内执行：

1. 按固定锁顺序从 `pdk_device_license` 条件扣减当前许可证次数；
2. 条件更新对应会话 `AUTHORIZED -> LIVE`；
3. 若扣减失败则抛出异常，事务回滚，内部事件接口返回 409。

重复 available 看到状态已经为 LIVE，直接幂等成功，不重复扣次。unavailable 只从活动状态进入 ENDED。

## 5. 数据表与索引

`schema-mysql.sql` 直接创建最终态表，没有 ALTER 迁移段：

- `pdk_media_server_node`：业务级 MediaMTX/SRS 节点、公开/内部地址、协议、权重、容量、健康状态和配置版本；
- `pdk_live_stream_session`：一场推流一行；
- `pdk_live_play_session`：一个 reader/client 连接一行，与推流 N:1；
- `pdk_media_server_node_snapshot`：每节点最新指标快照。

推流表核心约束：

| 索引 | 作用 |
| --- | --- |
| `uk_live_session_no` | 会话号不可重复 |
| `uk_live_ticket_hash` | 票据摘要不可重复 |
| `uk_live_client_request` | 同用户请求幂等键不可复用 |
| `uk_live_active_subject` | 单业务单许可证单活动流 |
| `uk_live_mediamtx_conn` | 一个媒体连接只绑定一个会话 |
| `idx_live_user_status` | 用户/管理后台会话查询 |
| `idx_live_license_status` | 当前设备许可证会话查询与精确踢流 |
| `idx_live_ticket_expire` | 过期票据清理 |

所有用户、会话查询都包含 bizId，避免 ZHIBO_AI 与 ZHIBO_LIVE 数据串用。

拉流表使用仅对 `PLAYING` 生效的生成列唯一约束，同一 reader Hook 重试不会重复插入；连接结束后即使厂商将来复用 reader ID，也可以创建新记录。

## 6. MediaMTX/SRS 配置

项目提供两套 MediaMTX 配置：Docker 镜像固定 `bluenviron/mediamtx:1.20.1`；用户本机目录
`mtx/mediamtx` 是已实际验证的 Windows v1.11.3。二者均开启 RTMP、HLS、内网 Control API、Metrics、
publish/read auth 与推拉流 Hook，并关闭未使用协议；`overridePublisher=false/no` 防止后来的发布者替换已在线发布者。

`authHTTPExclude` 只排除 api/metrics/pprof，绝不排除 publish/read。Docker Compose 发布 RTMP 1935 与 HLS 8888，9997/9998 不映射公网。

Hook 容器安装 curl，将 available/unavailable/read/unread 转为后端请求，并携带 nodeCode。脚本不读取、不转发、不记录 `MTX_QUERY`。

SRS 通过同一 Provider SPI 输出统一节点快照，`deploy/srs/srs.conf.example` 展示 on_publish/on_unpublish/on_play/on_stop 回调。on_publish 内先做票据鉴权，返回非 2xx 或 `code != 0` 即拒绝。

## 7. 主动停止

服务端保存 MediaMTX connection ID。用户或管理员停止时调用：

```text
POST /v3/rtmpconns/kick/{id}
```

Control API 失败时不伪造成功，返回业务错误 50371。Control API 地址只能配置为私网地址。

## 8. 已知边界和生产要求

- 已支持多个 MediaMTX/SRS 节点、健康/容量/权重调度和节点级票据绑定；严格容量预占与跨机房故障迁移仍可增强。
- 当前默认 RTMP 用于本地联调；公网生产必须配置 MediaMTX RTMPS 证书、开放加密端口并扩展协议配置。
- 当前以开播次数计费；直播分钟、码率、分辨率等套餐扩展尚未实现。
- Hook 是即时通知；生产高可用阶段仍需增加 Control API 定时对账任务。
- 当前拉流按“存在有效直播 path”授权；如需限制具体观看者，应增加独立短效 play ticket。
- 最新快照已实现；历史趋势、峰值和告警仍应接 Prometheus/Grafana。
- 内部令牌目前通过 auth URL 查询参数传给 MediaMTX；反向代理和访问日志必须禁止记录该 URL，或把内部接口完全限制在容器网络。
- 用户冻结、许可证解绑/暂停/作废/到期已自动按 licenseId 精确踢流；业务整体关闭后的全量活动流收敛仍可继续增强。
