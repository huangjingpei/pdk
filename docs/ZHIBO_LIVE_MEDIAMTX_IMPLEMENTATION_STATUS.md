# ZHIBO_LIVE 流媒体功能完成情况

> 最后核对：2026-09-06
> 适用业务：`appId=3 / bizCode=ZHIBO_LIVE / bizId=3`

## 1. 当前结论

ZHIBO_LIVE 的业务代码闭环已经完成：客户端必须先登录并取得设备许可证，再申请一次性短效推流地址；MediaMTX/SRS 回调鉴权失败即拒绝推流。客户端申请一次推流只创建一条 `pdk_live_stream_session`，HTTP auth、ready/not-ready Hook 和客户端停止动作只推动这一条记录的状态，重复 Hook 不新增记录、不重复扣次数。

流媒体节点、公开服务发现、多节点调度、推拉流管理、指标采集、后台页面和代理数据隔离也已落地。PDD（appId=1）和 ZHIBO_AI（appId=2）不会返回 `liveMedia`，也不能使用直播接口。

## 2. 已实现

### 2.1 鉴权、许可证与会话

- [x] appId=3、bizId、bizCode、部署开关和数据库开关校验。
- [x] 多设备卡密许可证、独立设备绑定、独立到期时间和次数校验。
- [x] 256 bit 随机短效票据；数据库仅保存 SHA-256。
- [x] 票据绑定节点、path、协议、设备和许可证，单次消费并阻断重放。
- [x] MediaMTX HTTP auth 使用裸 HTTP 状态；兼容 v1.11.3 `query` 携带 token 和新版独立 token 字段。
- [x] 一次客户端开播申请只 INSERT 一条推流记录；auth、Hook、停止、踢流只 UPDATE 原记录。
- [x] `ISSUED -> AUTHORIZED -> LIVE -> ENDED` 状态机与重复 Hook 幂等。
- [x] 只有首次进入 LIVE 扣一次，重复 ready Hook 不重复扣费。
- [x] 许可证到期、暂停、作废、解绑和用户冻结会禁止继续使用并停止对应活动流。

### 2.2 节点、拉流与监控

- [x] `pdk_media_server_node` 支持 MediaMTX/SRS、多节点、权重、容量和 `ACTIVE/DRAINING/DISABLED`。
- [x] 数据库为最终建表 DDL，不依赖 ALTER 兼容旧库。
- [x] appId=3 登录前公开发现返回安全的 `liveMedia`；内部 API、Metrics 和 Secret 不下发。
- [x] 服务端按健康、容量、协议和权重选路，并返回完整 `publishUrl`。
- [x] MediaMTX Provider：Control API、Metrics、健康检测、踢流与 v1.11.3 指标兼容。
- [x] SRS Provider：HTTP API、HTTP Callback、指标聚合与踢流适配。
- [x] `pdk_live_play_session` 独立记录每个拉流连接；read/unread Hook 幂等流转。
- [x] `pdk_media_server_node_snapshot` 保存最新采集结果，上下行无法计算时返回 `null` 而不是伪造 0。
- [x] 15 秒定时采集节点健康、推流数、拉流数和上下行速率。
- [x] 本机 MediaMTX v1.11.3 配置已落到 `mtx/mediamtx/mediamtx.yml`，包含 auth、ready/not-ready、read/unread、API 和 Metrics。

### 2.3 管理端、客户端与权限

- [x] 管理后台“直播中心”：概览、推流列表/踢流、拉流列表、节点 CRUD、连接测试与状态切换。
- [x] Dashboard 显示直播节点、推拉流和带宽；业务管理页显示节点数并可跳转；用户页可跳转直播会话。
- [x] 只有 SUPER_ADMIN 能配置节点和查看平台级节点/带宽数据。
- [x] PARTNER 只能查询和停止其名下卡密/许可证产生的推流，拉流及概览同样按许可证隔离。
- [x] 节点变更和管理员踢流写入审计日志。
- [x] Python、PyQt 与 Python SDK 增加 `live_media_info()`，最终推流仍使用票据接口返回的完整 URL。

## 3. 已完成的验证

- [x] Maven 测试：81 项，0 failure，0 error。
- [x] Vue `vue-tsc + vite build` 构建通过。
- [x] Python 客户端与 SDK `compileall` 通过。
- [x] Spring Boot 在独立端口启动、MySQL schema 初始化和 `/actuator/health` 通过。
- [x] appId=3 公开发现返回可用 MediaMTX 地址；appId=1 响应不含 `liveMedia`。
- [x] 本机 MediaMTX v1.11.3 成功加载配置，RTMP、HLS、Control API 和 Metrics 监听正常。
- [x] 单元测试覆盖无票据拒绝、错误票据拒绝、票据重放拒绝、auth/Hook 原记录更新、重复 Hook 不重复扣费、重复 read Hook 不重复建拉流记录。

## 4. 尚需部署环境验收或后续增强

- [ ] 本机未安装 FFmpeg，尚未执行真实编码 RTMP 推流黑盒；可按测试文档在装有 FFmpeg 的部署机验收。
- [ ] 尚未用真实 SRS 进程做黑盒联调；SRS Provider 与 Callback 代码已实现。
- [ ] 当前 read 鉴权按“节点上存在有效直播 path”放行；若播放内容也必须限定到具体观众，应再签发独立 play ticket。
- [ ] 快照表只保存最新值；历史带宽趋势、峰值和告警建议接 Prometheus/Grafana。
- [ ] Hook 丢失后的 Control API 自动对账、未经授权源自动清理仍属于生产增强。
- [ ] 公网生产环境仍需配置 RTMPS/HTTPS/WSS 证书、私网隔离 Control/Metrics、Secret 轮换与限流告警。

这些条目不影响“已登录且许可证有效才能推流、未登录 FFmpeg 被拒绝”的核心准入闭环，但上线前必须完成对应生产验收。

## 5. 关键文件

- 节点与后台完整方案：`docs/ZHIBO_LIVE_MEDIA_SERVER_MANAGEMENT_SOLUTION.md`
- 客户端接入：`docs/ZHIBO_LIVE_CLIENT_INTEGRATION_GUIDE.md`
- 鉴权总体方案：`docs/ZHIBO_LIVE_MEDIAMTX_AUTH_SOLUTION.md`
- 测试说明：`docs/ZHIBO_LIVE_MEDIAMTX_TEST.md`
- 数据库：`backend-springboot/src/main/resources/schema-mysql.sql`
- 后端实现：`backend-springboot/src/main/java/com/pdk/business/zhibo/live/`
- 后台页面：`admin-vue3/src/views/live/LiveCenter.vue`
- 本机 MediaMTX：`mtx/mediamtx/mediamtx.yml`
- PyQt/FFmpeg Demo：`client-pyqt/live_push_demo.py`
