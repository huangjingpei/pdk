# ZHIBO_LIVE MediaMTX 完成情况

## 1. 本次验收范围结论

“appId=3 客户端登录后可以获得授权推流；未登录的 ffmpeg 直接推流必须被 MediaMTX 拒绝”所需的代码闭环已经完成。

## 2. 已完成

- [x] ZHIBO 聚合目录下的 live 业务隔离。
- [x] appId=3、bizId、bizCode 和部署/数据库双开关校验。
- [x] 多设备许可证登录、用户状态、设备绑定、独立到期时间和独立次数校验。
- [x] 256 bit 随机短效票据，数据库只保存 SHA-256。
- [x] path 绑定、协议/action 白名单、单连接消费和重放阻断。
- [x] MediaMTX HTTP auth 裸 204/401/403/409 响应。
- [x] MySQL 单许可证单活动流唯一约束；同手机号不同许可证可并行；全新 schema，无 ALTER。
- [x] available/unavailable 状态机、幂等处理和首次开播扣 1 次。
- [x] 客户端查询/停止接口、管理员查询/踢流接口与权限。
- [x] MediaMTX v1.20.1 固定镜像、Docker Compose、Hook 和健康检查。
- [x] PyQt API 调试入口、FFmpeg 推流 Demo、Python SDK 方法。
- [x] publishUrl/token/query 日志脱敏约束。
- [x] 修复既有明文请求体被加密 Advice 提前读空导致真实登录 500 的问题。
- [x] 单元/契约测试共 66 项（含直播准入、设备许可证及配置安全校验），全部通过。
- [x] 真实 MySQL 10 卡/10 设备成功，第 11 台无卡/复用旧卡拒绝，续费与解绑换机通过。
- [x] 许可证到期、解绑、暂停、作废和用户冻结精确踢登录及正在直播的会话。
- [x] 真实 Spring Boot + MySQL HTTP 链路通过：无票据 401、有效票据 204、重放 409、LIVE/ENDED 正确。
- [x] 官方 MediaMTX v1.20.1 实际加载配置成功，Control API 确认 HTTP auth 和协议开关生效。

## 3. 本次环境未执行

- [ ] 本机没有 Docker，未执行 `docker compose up --build` 的容器整体联调。
- [ ] 本机没有 FFmpeg，未执行真实音视频编码和 RTMP publish 黑盒测试。

这两项已有可直接执行的配置、Demo 和测试步骤，不影响后端鉴权代码的完成；部署机安装 Docker/FFmpeg 后按测试文档验收即可。

## 4. 设计文档中的后续增强（不属于本次最小闭环）

- [ ] 公网 RTMPS 证书和证书轮换。
- [ ] 多 MediaMTX 节点、容量调度和节点专属票据。
- [ ] Redis 票据缓存、鉴权失败 IP 限流和告警。
- [ ] append-only 直播事件审计表。
- [ ] Control API 定时对账、Hook 丢失自动修复。
- [ ] 业务整体关闭时批量扫描并停止该业务全部活动流（许可证级停权联动已完成）。
- [ ] 直播分钟、码率、清晰度、并发数等套餐扩展表。
- [ ] 管理后台专用在线流可视化页面和节点监控页（后端接口已提供基础能力）。

## 5. 关键文件

- 总体方案：`docs/ZHIBO_LIVE_MEDIAMTX_AUTH_SOLUTION.md`
- 开发说明：`docs/ZHIBO_LIVE_MEDIAMTX_DEVELOPMENT.md`
- 技术说明：`docs/ZHIBO_LIVE_MEDIAMTX_TECHNICAL.md`
- 测试说明：`docs/ZHIBO_LIVE_MEDIAMTX_TEST.md`
- 数据库：`backend-springboot/src/main/resources/schema-mysql.sql`
- MediaMTX：`deploy/mediamtx/mediamtx.yml`
- 一键 HTTP 验证：`scripts/verify-zhibo-live-auth.ps1`
- PyQt/FFmpeg Demo：`client-pyqt/live_push_demo.py`
- 多设备许可证：`docs/ZHIBO_LIVE_MULTI_DEVICE_LICENSE_SOLUTION.md`
