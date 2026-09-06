# ZHIBO_LIVE MediaMTX 测试说明

## 1. 自动化测试

在 `backend-springboot` 下执行：

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' test
```

直播相关测试覆盖：

- appId=3 用户可申请随机票据，其他业务不能申请；
- 无票据、伪造票据、错误内部令牌拒绝；
- 首次有效票据允许，相同票据换 connection ID 重放拒绝；
- Controller 拒绝一定保持非 2xx，允许使用裸 204；
- MediaMTX v1.11.3 从 `query` 读取 token；
- available Hook 只更新票据申请时创建的原推流记录，重复 Hook 不新增记录、不重复扣费；
- read Hook 重试不重复创建拉流记录；
- 客户端明文 JSON 经加密 Advice 检查后仍可被 Jackson 正常读取。

当前全量实测结果：`81 tests, 0 failures, 0 errors, BUILD SUCCESS`。

## 2. 后端真实 HTTP 契约测试

准备一个属于 bizId=3 的 ACTIVE 用户，为测试设备分配并绑定一张 ACTIVE 许可证，确保许可证
`expire_at` 在未来且 `remaining_calls > 0`，启用业务和 MediaMTX 配置后运行：

```powershell
./scripts/verify-zhibo-live-auth.ps1 `
  -BackendBaseUrl http://127.0.0.1:8080 `
  -Phone 13900000003 `
  -Password 'your-password' `
  -DeviceId 'your-device-id' `
  -MediaMtxServiceToken $env:PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN
```

脚本依次断言：

| 场景 | 期待 |
| --- | --- |
| appId=3 业务登录 | CommonResult code 200，bizCode=ZHIBO_LIVE |
| 登录后签发票据 | code 200 |
| 模拟直接 ffmpeg，无 token | HTTP 401 |
| 有效票据首次鉴权 | HTTP 204 |
| 同票据换连接重放 | HTTP 409 |
| available | HTTP 204，会话 LIVE，billedUnits=1 |
| unavailable | HTTP 204，会话 ENDED |

本次已使用真实 Spring Boot + MySQL 临时库跑通全部断言。

## 3. MediaMTX 配置测试

本机联调使用 `mtx/mediamtx/mediamtx.exe` v1.11.3 与同目录 `mediamtx.yml`；Docker 部署模板固定 v1.20.1。Windows 本机启动：

```powershell
cd E:\pdk\mtx\mediamtx
.\mediamtx.exe .\mediamtx.yml
```

日志必须包含：

```text
configuration loaded from ...mediamtx.yml
[RTMP] started with listener on :1935
[API] started with listener on :9997
```

Control API 检查：

```powershell
Invoke-RestMethod http://127.0.0.1:9997/v3/paths/list
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:9998/metrics
```

期待 Control API 和 Metrics 均返回 HTTP 200，RTMP 监听 1935、HLS 监听 8888。本次已用用户目录中的 Windows v1.11.3 二进制实际验证配置可加载；Docker v1.20.1 仍需在具有 Docker 的部署机验收。

同时验证登录前发现：

```powershell
$live = Invoke-RestMethod http://127.0.0.1:8080/api/v1/client/business/by-app/3 -Headers @{'X-PDK-App-ID'='3'}
$pdd  = Invoke-RestMethod http://127.0.0.1:8080/api/v1/client/business/by-app/1 -Headers @{'X-PDK-App-ID'='1'}
$live.data.liveMedia.enabled       # 期待 True
$null -eq $pdd.data.liveMedia      # 期待 True
```

## 4. FFmpeg 黑盒验收

### 4.1 未登录直接推流必须失败

使用符合 path 正则但不含 token 的地址：

```powershell
ffmpeg -re -f lavfi -i "testsrc=size=640x360:rate=25" `
  -c:v libx264 -f flv `
  "rtmp://127.0.0.1:1935/zhibo-live/ls_0123456789abcdef"
```

期待：FFmpeg 连接/发布失败；后端 auth 返回 401，MediaMTX 不出现可用流。

### 4.2 登录客户端推流必须成功

```powershell
python client-pyqt/live_push_demo.py --api http://127.0.0.1:8080 `
  --phone 13900000003 --password 'your-password' --device-id 'your-device-id'
```

期待：登录和票据签发成功，MediaMTX 出现 publisher，后端会话为 LIVE；Ctrl+C 后变为 ENDED。

本机本次没有预装 FFmpeg，因此未执行音视频帧级黑盒推流；HTTP auth 契约、真实 Spring/MySQL 状态机和 MediaMTX 配置加载均已验证。

## 5. 安全回归

- 搜索应用日志，不得出现完整 `publishUrl`、ticket、`MTX_QUERY`。
- 将内部服务令牌改错，auth/event 必须返回 403。
- 把 ZHIBO_LIVE 关闭或移出部署 allowlist，新票据签发和 auth 均应失败。
- 用户冻结、许可证到期/暂停/作废、许可证次数为 0、设备 UUID 或绑定关系变化时均应失败。

## 6. 多设备许可证专项验收

专项测试以 [多设备许可证解决方案](./ZHIBO_LIVE_MULTI_DEVICE_LICENSE_SOLUTION.md) 第 16、20 节为准。
本次真实 MySQL 已验证同手机号 10 张卡/10 台设备登录成功，第 11 台无卡返回 40380、复用旧卡返回
40383，续费保留原卡和 licenseId，解绑换机不重算到期时间。
- MediaMTX API 9997 不应映射到公网；公网防火墙只开放必要的 RTMP/RTMPS 端口。

## 7. 管理后台与节点验收

1. SUPER_ADMIN 打开“直播中心”，可查看节点、连接测试、切换 ACTIVE/DRAINING/DISABLED。
2. 节点关闭时不能签发新推流；恢复后定时采集将健康状态更新为 UP。
3. Dashboard 显示节点、推流、拉流和上下行；初次无法计算带宽时显示 `--`，不能显示伪造的 0。
4. ZHIBO_LIVE PARTNER 只能看到名下许可证的推流、拉流与概览，不能看到节点配置或平台总带宽。
5. PDD/ZHIBO_AI PARTNER 登录响应不含 `live:*` 权限，侧栏不显示直播中心。
6. 节点创建、修改、状态切换和踢流均可在操作审计中追溯。

## 8. SRS 部署验收

参考 `deploy/srs/srs.conf.example` 启动真实 SRS，并在后台创建 providerType=SRS、nodeCode 一致的节点。分别验证无票据 on_publish 被拒、有效票据进入 LIVE、on_unpublish 进入 ENDED、on_play/on_stop 形成一条拉流连接记录。当前代码与配置样例已完成，本机尚未运行真实 SRS 黑盒。
