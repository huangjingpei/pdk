# ZHIBO_LIVE MediaMTX 开发说明

客户端接入开发者请先阅读 [ZHIBO_LIVE 客户端接入指南](./ZHIBO_LIVE_CLIENT_INTEGRATION_GUIDE.md)。

## 1. 已实现目标

本次实现覆盖 `appId=3 / bizId=3 / ZHIBO_LIVE` 的推流准入闭环：

1. 客户端用手机号、密码、设备 UUID 登录业务服务器。
2. 登录用户申请 90 秒短效、单 path、单连接的推流票据。
3. MediaMTX 在 RTMP publish 前调用后端 HTTP auth。
4. 无票据、伪造票据、过期票据、错误业务、错误 path 和票据重放均返回非 2xx。
5. 首次有效鉴权返回裸 HTTP 204；MediaMTX 才允许推流。
6. `runOnReady/runOnNotReady`（v1.11）或 `runOnAvailable/runOnUnavailable`（新版）驱动 `LIVE/ENDED`，并在首次 LIVE 时扣一次。
7. 客户端和管理员可查看会话、停止/踢掉在线连接。
8. 数据库管理 MediaMTX/SRS 节点；登录前发现公开地址，开播时按健康、容量和权重选择节点。
9. read/unread 或 SRS on_play/on_stop 独立记录拉流连接，节点指标定时采集并在后台展示。

## 2. 代码布局

```text
backend-springboot/src/main/java/com/pdk/business/zhibo/live/
├─ config/       MediaMtxProperties
├─ controller/   客户端、管理员、MediaMTX auth/event、SRS callback
├─ dto/          票据、节点配置与媒体服务器 payload
├─ entity/       推流、拉流、节点、节点快照
├─ mapper/       对应 MyBatis-Plus Mapper
├─ media/        MediaMTX/SRS Provider SPI
├─ service/      票据、鉴权、事件、节点调度、采集与 Control API
└─ vo/           客户端安全响应

deploy/mediamtx/
├─ Dockerfile
├─ mediamtx.yml
└─ event-hook.sh

deploy/srs/
├─ srs.conf.example
└─ README.md

mtx/mediamtx/    用户本机 Windows v1.11.3 可运行配置

client-pyqt/
├─ pdk_client.py
└─ live_push_demo.py

scripts/verify-zhibo-live-auth.ps1
```

直播代码放在 `business/zhibo/live`，复用平台的用户、套餐、设备和 `BusinessContext`，没有复制用户中心。

## 3. 客户端接口

### 3.1 申请推流票据

```http
POST /api/v1/client/zhibo-live/publish-tickets
X-PDK-App-ID: 3
X-PDK-Phone: 139...
X-PDK-Device-ID: stable-device-uuid
satoken: <client-login-token>
Content-Type: application/json

{
  "clientRequestId": "uuid-per-attempt",
  "title": "测试直播",
  "requestedProtocol": "RTMP"
}
```

后端从已登录请求上下文取得用户和业务，不接受客户端提交 `bizId`。返回 `streamSessionNo`、
`publishUrl`、`expiresAt`、`ticketTtlSeconds` 和 `status`。`publishUrl` 含一次性秘密，不得打印。

### 3.2 会话接口

```text
GET  /api/v1/client/zhibo-live/streams/current
POST /api/v1/client/zhibo-live/streams/{streamSessionNo}/stop
```

两者都经过现有设备安全拦截器，并在服务层再次限定 bizId 和 userId。

## 4. 内部接口

```text
POST /api/v1/internal/mediamtx/auth?serviceToken=...
POST /api/v1/internal/mediamtx/events/available
POST /api/v1/internal/mediamtx/events/unavailable
POST /api/v1/internal/mediamtx/events/read
POST /api/v1/internal/mediamtx/events/unread
POST /api/v1/internal/srs/callback
```

auth 接口严格返回 HTTP 语义，不套 `CommonResult`：允许为 204，拒绝为 401/403/409/503。
事件接口携带 `serviceToken/nodeCode/path` 和对应 source/reader 字段；Hook 明确不转发 `MTX_QUERY`。

## 5. 管理接口

```text
GET  /api/v1/admin/zhibo-live/streams?status=LIVE
POST /api/v1/admin/zhibo-live/streams/{streamSessionNo}/kick
GET  /api/v1/admin/zhibo-live/overview
GET  /api/v1/admin/zhibo-live/play-sessions
GET  /api/v1/admin/zhibo-live/media-nodes
POST /api/v1/admin/zhibo-live/media-nodes
PUT  /api/v1/admin/zhibo-live/media-nodes/{nodeId}
PUT  /api/v1/admin/zhibo-live/media-nodes/{nodeId}/status
POST /api/v1/admin/zhibo-live/media-nodes/{nodeId}/test
```

SUPER_ADMIN 可管理节点并查看全局指标；ZHIBO_LIVE PARTNER 只能查看和停止自己名下卡密许可证产生的推流、拉流和概览。其他业务的 PARTNER 登录响应不下发直播权限。

## 6. 配置

| 环境变量 | 必填 | 示例/说明 |
| --- | --- | --- |
| `PDK_ENABLED_BIZ_CODES` | 是 | 包含 `ZHIBO` 或 `ZHIBO_LIVE` |
| `PDK_MEDIAMTX_ENABLED` | 是 | `true` |
| `PDK_MEDIAMTX_PUBLIC_RTMP_BASE_URL` | 是 | 旧单节点兼容/首次引导地址；最终以节点表为准 |
| `PDK_MEDIAMTX_CONTROL_BASE_URL` | 是 | 旧单节点兼容地址；最终以节点表为准 |
| `PDK_MEDIAMTX_NODE_CODE` | 是 | 当前 MediaMTX 实例必须与节点表 nodeCode 一致 |
| `PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN` | 是 | 至少 32 字节随机秘密 |
| `PDK_MEDIAMTX_TICKET_TTL_SECONDS` | 否 | 默认 90，代码限制为 30～300 秒 |

流媒体公开地址、内部 API、Metrics、协议、权重和容量以 `pdk_media_server_node`/直播中心配置为准。内部服务令牌仍由安全配置持有，不保存明文到数据库。必须先使至少一个节点健康并置为 ACTIVE，才能在管理后台启用 ZHIBO_LIVE；部署 allowlist 和数据库开关必须同时满足。

Docker 使用 `nodeCode=mediamtx-docker-1`，首次部署需在直播中心创建对应节点，内部 API 填 `http://mediamtx:9997`、Metrics 填 `http://mediamtx:9998/metrics`。本机配置使用 `mediamtx-local`，schema 已提供该开发节点。

## 7. 本地运行

复制 `deploy/.env.example` 为 `deploy/.env`，填入强随机秘密和数据库密码，然后在 `deploy` 下运行：

```powershell
docker compose up --build
```

仅验证后端时可直接启动 Spring Boot，再执行：

```powershell
./scripts/verify-zhibo-live-auth.ps1 `
  -BackendBaseUrl http://127.0.0.1:8080 `
  -Phone 13900000003 `
  -Password 'your-password' `
  -DeviceId 'your-stable-device-id' `
  -MediaMtxServiceToken $env:PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN
```

测试脚本只在内存中使用推流票据，不输出完整 URL。

## 8. 客户端推流 Demo

先安装 PyQt 客户端依赖和 FFmpeg，然后：

```powershell
python client-pyqt/live_push_demo.py --api http://127.0.0.1:8080 `
  --phone 13900000003 --password 'your-password'
```

Demo 固定使用 `appId=3`，先登录再申请票据，随后把 URL 直接交给 FFmpeg。Python SDK 同样提供
`create_live_publish_ticket()`、`live_streams()`、`stop_live_stream()`。
