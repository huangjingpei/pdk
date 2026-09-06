# Windows 本地开发环境指南

面向 `E:\pdk` 仓库，覆盖「后端 + 前端 + MySQL/Redis + MediaMTX」在本机（Windows 10/11）的搭建、启动与自检。
生产环境（阿里云 Ubuntu）见 `deploy/aliyun/README.md`。

---

## 1. 本机现状（2026-09-06 实测）

| 组件 | 状态 | 说明 |
| --- | --- | --- |
| JDK | ✅ 17.0.15 | `C:\Program Files\Java\jdk-17` |
| Node | ✅ v22.22.2 | 前端 `admin-vue3` 用 |
| Python | ✅ 3.13 | 升级包脚本 `scripts/build_update_package.py` 用 |
| MySQL | ✅ 服务 `MySQL80` Running | 库 `pdk_biz_db` 由后端自动创建 |
| Redis | ✅ 服务 `Redis` Running | 无密码（`application.yml` 未配 password） |
| Maven 启动器 | ⚠️ 不可用 | 本机 `mvn` 启动器损坏，用 IDE 或 javac 编译校验（见 `.workbuddy/memory/MEMORY.md`） |
| MediaMTX | ✅ `mtx/mediamtx/mediamtx.exe` | 配置 `mtx/mediamtx/mediamtx.yml`（`mtx/` 已 gitignore） |
| ffmpeg | ❌ 未安装 | 本地推流自检需要，装法见 §6 |

---

## 2. 首次搭建

1. 安装 JDK 17、Node 22、MySQL 8（服务名默认 `MySQL80`）、Redis（Windows 版或 WSL）
2. `git clone` 本仓库到 `E:\pdk`
3. 前端装依赖：`cd admin-vue3 && npm ci`
4. 生成客户端升级签名密钥（若 `backend-springboot/config/client-update-keys.yml` 不存在）：
   ```bash
   cp backend-springboot/config/client-update-keys.example.yml backend-springboot/config/client-update-keys.yml
   # 或重新生成：python scripts/generate_update_keys.py
   ```
   该文件已在 `.gitignore` 中，**不要提交**
5. 设置环境变量（见 §3）

---

## 3. 环境变量（本地只需要很少）

| 变量 | 是否必需 | 说明 |
| --- | --- | --- |
| `PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN` | ✅ 直播功能必需 | 后端与本地 MediaMTX 共用；≥32 字节随机值，生成见下 |
| `DB_USER` / `DB_PASS` | 建议 | 默认值为 `root` / 配置文件里的默认密码；建议改成环境变量注入，避免密码进仓库 |
| `PDK_MEDIAMTX_PUBLIC_RTMP_BASE_URL` | 可选 | 默认 `rtmp://localhost:1935` |
| `PDK_MEDIAMTX_TICKET_TTL_SECONDS` | 可选 | 默认 90 |

设置（用户级，一次生效；**需重启 IDE/终端**）：

```powershell
[Environment]::SetEnvironmentVariable('PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN','<64位hex>','User')
```

生成令牌（任选其一）：

```bash
openssl rand -hex 32
python -c "import secrets; print(secrets.token_hex(32))"
```

> 服务器上不需要手动生成：`deploy/aliyun/server/02-init-infra.sh` 会自动生成并幂等写入 `/opt/pdk/.env`。

---

## 4. 启动顺序

```
1) MySQL80 + Redis 服务（Windows 服务，开机自启即可）
2) 后端  : IDE 运行 PdkApplication，或 mvn spring-boot:run（若本机 mvn 可用）
          端口 8080；首次启动自动建库 pdk_biz_db + 执行 schema-mysql.sql（mode: always）
3) 前端  : cd admin-vue3 && npm run dev      → http://localhost:8081
4) 直播  : 见 §5（按需，只有做直播功能才需要）
```

前端类型校验（改完 Vue/TS 应跑一次）：

```bash
cd admin-vue3 && ./node_modules/.bin/vue-tsc --noEmit -p tsconfig.json
```

---

## 5. MediaMTX（Windows 本地）

### 配置文件是哪一个？

| 场景 | 配置文件 | 位置 |
| --- | --- | --- |
| **Windows 本地** | `mediamtx.yml`（由模板渲染生成） | `E:\pdk\mtx\mediamtx\mediamtx.yml` |
| **Linux/Docker（生产）** | `deploy/mediamtx/mediamtx.yml` | 随部署上传 |

⚠️ `mtx/` 目录含 31MB 的 `mediamtx.exe`，已 gitignore。**仓库里的可复现模板**是：
`deploy/mediamtx/mediamtx-windows.example.yml`（令牌用 `__PDK_MEDIAMTX_TOKEN__` 占位）。

### 一键启动

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-mediamtx-win.ps1            # 前台
powershell -ExecutionPolicy Bypass -File scripts/start-mediamtx-win.ps1 -Background # 后台
```

脚本做三件事：确保令牌存在（缺失则随机生成并写入用户环境变量）→ 用令牌渲染模板到 `mtx/mediamtx/mediamtx.yml`（旧配置自动备份）→ 启动 `mediamtx.exe`。

### Windows 与 Linux 的钩子差异（重要）

| 项 | Windows 本地 | Linux/Docker |
| --- | --- | --- |
| 鉴权 | `authHTTPAddress` URL 里带 `?serviceToken=...` | 同左（由 compose 注入 `MTX_AUTHHTTPADDRESS`） |
| 事件回调 | `runOnReady/runOnNotReady/runOnRead/runOnUnread` 直接调用 `curl.exe`，变量用 `%MTX_PATH%` | `event-hook.sh`（bash），变量用 `$MTX_PATH` |
| 为何不同 | Windows 无法执行 `.sh` | — |

Windows 配置里**不能**写 `bash event-hook.sh`；也不要用 `$MTX_PATH`（那是 bash 语法），必须用 `%MTX_PATH%`。

### 关键端口

| 端口 | 用途 |
| --- | --- |
| 1935 | RTMP 推流 |
| 8888 | HLS 拉流 |
| 9997 | MediaMTX 控制 API（后端踢流用） |
| 9998 | metrics |

---

## 6. 推流自检

需要 ffmpeg（本机暂未安装）：

```powershell
winget install Gyan.FFmpeg     # 或 https://ffmpeg.org 下载后加 PATH
```

自检步骤：

```bash
# 1) 申请票据（需客户端许可证会话，返回 publishUrl 里含 ?token=xxx，90 秒有效）
POST http://localhost:8080/api/v1/client/zhibo-live/publish-tickets

# 2) 推流（用 publishUrl，或手工拼）
ffmpeg -re -i test.mp4 -c copy -f flv "rtmp://127.0.0.1:1935/zhibo-live/ls_xxxx?token=<ticket>"

# 3) 端到端校验脚本
powershell -ExecutionPolicy Bypass -File scripts/verify-zhibo-live-auth.ps1
```

预期：后端收到 `/internal/mediamtx/auth` 放行 → `events/available`（会话转 LIVE 并扣次数）→ 断流后 `events/unavailable`（转 ENDED）。
后端另有 `MediaServerMonitorJob` 每 15 秒轮询兜底（`@EnableScheduling` 已开启）。

---

## 7. 与生产环境对照

| 维度 | Windows 开发 | 阿里云生产 |
| --- | --- | --- |
| 后端 | IDE/手动启动，端口 8080 全网卡 | systemd `pdk-backend`，**只监听 127.0.0.1:8080**，Nginx 反代 |
| 前端 | Vite dev server 8081 | 构建产物 `/opt/pdk/www/admin`，Nginx 静态 |
| 数据库 | 本机 MySQL80，自动建库建表 | 本机 MySQL 8.0.46，`SPRING_SQL_INIT_MODE=never`（`--migrate` 手动同步） |
| Redis | Windows 服务，无密码 | Docker 容器 `pdk-redis`（`restart=unless-stopped`），有密码 |
| MediaMTX | 本机 exe + Windows 钩子（curl.exe） | Docker + event-hook.sh |
| 秘密来源 | 用户环境变量 + `backend-springboot/config/*.yml`（gitignore） | `/opt/pdk/.env`（systemd EnvironmentFile） |

---

## 8. 秘密管理约定（红线）

- **任何密码/令牌都不写进 `application.yml` 默认值**，只走 `${ENV_VAR:}` 占位
- 令牌/密钥生成：本地用用户环境变量，服务器由 `02-init-infra.sh` 自动生成
- 已 gitignore：`backend-springboot/config/client-update-keys.yml`、`mtx/`、`assets/`、`deploy/aliyun/env.sh`
- 轮换 `PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN` 需同步四处：本地用户环境变量 + 本地 `mtx/mediamtx/mediamtx.yml` + 服务器 `.env` + 重建 mediamtx 容器

---

## 9. 常见坑

| 现象 | 原因 / 处理 |
| --- | --- |
| 后端起不来报 `Access denied for user 'root'` | 未设置 `DB_USER`/`DB_PASS` 环境变量（重启 IDE 后生效） |
| 推流被拒，日志 `UNTRUSTED_MEDIAMTX` | 后端与 MediaMTX 令牌不一致；重跑 `start-mediamtx-win.ps1` 重新渲染配置 |
| 推流成功但会话不转 LIVE | Windows 钩子没配/配错（应含 `runOnReady` 调 curl.exe） |
| PowerShell 报「意外的标记…中文」 | 脚本需保存为 **UTF-8 with BOM**（PS 5.1 按 ANSI 解析无 BOM 的 UTF-8） |
| 前端端口不是 8081 | 8081 被占用时 Vite 自动顺延（如 8082），以终端输出为准 |
