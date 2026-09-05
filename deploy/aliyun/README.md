# PDK 部署到阿里云（121.43.150.109）

把 `backend-springboot`（Spring Boot 3.3.2）与 `admin-vue3`（Vue 3 + Vite）部署到阿里云服务器。

> ## ⚠️ 这不是一台空服务器 —— 共存部署
>
> 服务器上**已在运行生产服务**，本方案设计为与之共存，**不抢占、不修改、不删除**任何既有配置：
>
> | 既有服务 | 占用 | 本方案的处理 |
> | --- | --- | --- |
> | Nginx 站点（live-chat） | 80 `default_server` | **不碰**，PDK 用子域名匹配的新 server 块 |
> | `graddu.com` 证书 | 443 | **不动**，PDK 单独签 `pdk.graddu.com` 证书 |
> | MySQL 8.0.46 | 3306 | **复用**，只新建 `pdk_biz_db` 库（不起容器） |
> | PM2: RustDesk `hbbs`/`hbbr` | 21115-21119 | **不碰** |
> | PM2: `live-chat` | 3000 | **不碰** |
> | vsftpd | 21 | **不碰** |
>
> 三条铁律：
> 1. **绝不使用 `default_server`** —— 会造成 nginx 配置冲突
> 2. **绝不删除 `sites-enabled/default`** —— 那是线上 live-chat 站点
> 3. **不起 MySQL 容器** —— 3306 已被占用，容器根本起不来

## 部署拓扑

```
                        阿里云 121.43.150.109 (2核 / 1.6GB)
   ┌────────────────────────────────────────────────────────────┐
   │  pdk.graddu.com ─▶ Nginx :443 (SSL)                        │
   │      ├── /            → /opt/pdk/www/admin    Vue 静态文件  │
   │      ├── /api/        → 127.0.0.1:8080        反向代理      │
   │      └── /actuator    → 仅 127.0.0.1                      │
   │  Nginx :80 → 301 跳转 HTTPS（放行 certbot 续期路径）         │
   │                                                            │
   │  ── 以下为既有服务，本方案完全不触碰 ──                      │
   │  Nginx default_server :80/:443 → live-chat (graddu.com)     │
   │  PM2: hbbs/hbbr (RustDesk) / live-chat:3000                 │
   │                                                            │
   │  systemd: pdk-backend → /opt/pdk/app/app.jar (8080)         │
   │       ├── MySQL 8.0.46  (复用本机实例, 127.0.0.1:3306)      │
   │       └── Redis 7.4     (Docker, 127.0.0.1:6379)           │
   └────────────────────────────────────────────────────────────┘
```

数据库和缓存**只监听 127.0.0.1**，公网无法直连，只能经后端访问。

## 服务器内存优化记录（已执行）

服务器总内存仅 1673MB，部署前可用 713MB，直接部署必然 OOM。已做优化：

| 优化项 | 效果 | 风险 |
| --- | --- | --- |
| 禁用 `apache2`、`caddy`（原本就 failed） | — | 零（本来就没在跑） |
| 停 `tuned`、`multipathd`、`packagekit` | +58MB | 低（云服务器无用） |
| 停 snap 版 dockerd，保留 apt 版 | +41MB | 低（两者均 0 容器 0 镜像） |
| MySQL 调优（`performance_schema=OFF` 等） | **+210MB** | 低（无业务库） |
| `vm.swappiness=10` | 避免过早换页 | 无 |

**可用内存：713MB → 1008MB（+41%）**。所有生产服务验收通过（nginx/mysql/docker/certbot/PM2 全部在线）。

> MySQL 调优写在独立文件 `/etc/mysql/conf.d/pdk-tuning.cnf`，**删掉该文件重启 mysql 即可完全恢复**。

## 前置条件（只需做一次）

### 1. 阿里云安全组放行端口

控制台 → 云服务器 ECS → 实例 → 安全组 → 配置规则 → 入方向添加：

| 协议  | 端口  | 授权对象      | 说明                     |
| --- | --- | --------- | ---------------------- |
| TCP | 22  | 你的 IP     | SSH                    |
| TCP | 80  | 0.0.0.0/0 | **必须**，certbot 证书签发与续期 |
| TCP | 443 | 0.0.0.0/0 | **必须**，HTTPS 后台访问      |

> 当前状态：**22 和 80 已实测连通**，443 需你确认是否已放行 —— 未放行会导致 HTTPS 打不开。
> 8080 不需要放行：后端只监听 127.0.0.1，由 Nginx 反代。

### 2. 配置 SSH 免密登录（本机执行）

```bash
# 生成密钥（已有可跳过，一路回车）
ssh-keygen -t ed25519 -f ~/.ssh/id_rsa

# 把公钥传到服务器（最后输入一次服务器密码）
ssh-copy-id -p 22 root@121.43.150.109

# Windows 没有 ssh-copy-id 时用这条：
cat ~/.ssh/id_rsa.pub | ssh root@121.43.150.109 "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

> 当前状态：**已配置完成**（实测可免密登录，无需再操作）。

### 3. 确认 DNS 解析生效

```bash
nslookup pdk.graddu.com      # 应返回 121.43.150.109
```

> 当前状态：**已生效**。

## 快速开始

```bash
cd E:/pdk/deploy/aliyun

# 1) 配置文件已按服务器实况预填（子域名 + 内存参数），按需微调
cp env.example.sh env.sh

# 2) 先体检一次，确认服务器状态（推荐首次执行）
bash deploy.sh --precheck

# 3) 完整部署（约 15-30 分钟，主要在下载依赖和编译）
bash deploy.sh
```

脚本会依次完成：建库授权 → 起 Redis → 编译 → 签发证书 → 上线 → 健康检查。
结束后终端会打印**管理后台地址和超级管理员初始密码**，务必记下来。

> `env.sh` 中 `SKIP_PREPARE="yes"`：服务器已具备 JDK17 / Maven 3.9.9 / Node 22 / Nginx / Docker，
> 跳过环境安装可显著加快部署。换一台新服务器时改回 `no`。

## 配置文件 env.sh

| 变量                    | 默认值                     | 说明                                              |
| --------------------- | ----------------------- | ----------------------------------------------- |
| `SERVER_IP`           | 121.43.150.109          | 服务器公网 IP                                        |
| `SERVER_USER`         | root                    | 登录用户                                            |
| `SSH_KEY`             | ~/.ssh/id_rsa           | 本机私钥                                            |
| `PDK_PUBLIC_BASE_URL` | <http://121.43.150.109> | **重要**：客户端升级系统会用这个地址下发下载链接                      |
| `ADMIN_INIT_PASSWORD` | 空（随机生成）                 | 留空更安全，随机密码存在服务器 `/opt/pdk/.admin-init-password` |
| `JAVA_XMX`            | 768m                    | 建议设为物理内存的 50%~60%                               |
| `SKIP_PREPARE`        | no                      | 环境已装好时设为 `yes`，跳过 apt 安装，快很多                    |

## 日常运维

```bash
bash deploy.sh --status                  # 查看运行状态、健康、资源、日志
bash deploy.sh --build                   # 只重新编译（不部署）
bash deploy.sh --deploy-only             # 只切换已构建的版本上线
bash deploy.sh --rollback 20260904-140000 # 回滚到指定版本
bash deploy.sh --rollback                # 回滚到上一个版本
bash deploy.sh --rollback --list         # 列出可回滚版本
bash deploy.sh --migrate                 # 部署时同步表结构（新版本带了新表时用）
bash deploy.sh --skip-typecheck          # 前端跳过 vue-tsc（小内存机器救急）
```

也可以直接登录服务器操作：

```bash
sudo systemctl status pdk-backend           # 服务状态
sudo journalctl -u pdk-backend -f           # 实时日志
tail -f /opt/pdk/logs/backend.log           # 日志文件
sudo bash /opt/pdk/scripts/06-status.sh     # 状态总览
docker logs -f pdk-mysql                    # MySQL 日志
```

## 关键设计说明

### 1. 为什么管理员密码必须由脚本重算

密码摘要 = `SHA256(pepper + ":" + 明文)`，而 `pepper` 每次全新部署都会**随机生成**。`schema-mysql.sql` 里写死的那个摘要是按开发环境默认 pepper 算的，直接部署会**永远登录不进去**。

所以 `02-init-infra.sh` 在导入表结构后，会用当前 pepper 重新计算并写入超级管理员（账号 `13454118762`）的密码。

> 重复执行部署**不会**重置密码——`.env` 已存在时脚本保持 pepper 不变。只有删掉 `/opt/pdk/.env` 或用 `--rotate-secrets` 才会轮换。

### 2. 升级密钥只走配置文件，不走环境变量

`application.yml` 里 `pdk.client-update.*` 除了上传大小外**没有**环境变量占位符（这是项目刻意的设计，注释里说明了混用会导致 key-id 和私钥来源分裂）。因此：

- 所有升级相关配置只能由 `/opt/pdk/config/client-update-keys.yml` 注入
- 脚本默认**上传开发机已有的密钥**，因为客户端已内置对应公钥；换密钥会导致所有客户端验签失败
- 只有在开发机没有密钥文件时，才在服务器现场生成（此时必须把新公钥同步到 `client-pyqt/config/*.json`）

### 3. 建表策略

生产环境 `.env` 中设置 `SPRING_SQL_INIT_MODE=never`：表结构由 `02-init-infra.sh` 手工导入一次。这样避免 43KB 的建表脚本在每次重启时重复执行，也避免 `continue-on-error: false` 让任何一条语句报错直接搞挂启动。

**升级时如果新版本带了新表**，用 `bash deploy.sh --migrate` 部署。

### 4. 日志字符集

`application.yml` 里写死了 `logging.charset.console: GBK`（为 Windows 控制台准备）。Linux 上必须覆盖，否则日志乱码。脚本通过环境变量 `LOGGING_CHARSET_CONSOLE=UTF-8` 处理。

### 5. 回滚只回滚应用

`05-rollback.sh` 只切换 jar 和前端文件，**不回滚数据库**。若 schema 含破坏性变更需人工处理。

## 常见问题

**访问 <http://121.43.150.109> 打不开**  
先查安全组是否放行 80；再 `bash deploy.sh --status` 看 Nginx 和后端状态。

**后端起不来**

```bash
sudo journalctl -u pdk-backend -n 100 --no-pager
```

常见原因：MySQL/Redis 没起来、`.env` 配置错误、升级密钥自检失败（私钥和公钥不配对会直接拒绝启动）。

**部署后登录提示账号或密码错误**  
密码摘要依赖 pepper，确认：① 用了脚本输出的初始密码；② 若手动改过 `/opt/pdk/.env` 的 pepper，需要重跑 `02-init-infra.sh` 重算密码。

**npm build 内存溢出**  
脚本已自动加 2GB swap 并设 `NODE_OPTIONS=--max-old-space-size=2048`。仍失败就用 `bash deploy.sh --skip-typecheck`。

**客户端无法下载升级包**  
检查 `/opt/pdk/config/client-update-keys.yml` 里的 `public-base-url` 是否为 `http://121.43.150.109`（不能是 localhost）。改完后：

```bash
sudo systemctl restart pdk-backend
```

## 上生产前的安全清单

- [ ] 删掉服务器上的 `/opt/pdk/.admin-init-password`（初始密码明文）
- [ ] 登录后立即修改超级管理员密码
- [ ] 确认 MySQL/Redis 端口只绑 127.0.0.1：`ss -lntp | grep -E '3306|6379'`
- [ ] 配置数据库定期备份：`/opt/pdk/data/mysql`
- [ ] 加域名并启用 HTTPS（见下）

## 后续：加 HTTPS

有域名后，在服务器上执行：

```bash
apt-get install -y certbot python3-certbot-nginx
certbot --nginx -d your-domain.com
# 证书会自动续期，然后把 env.sh 的 PDK_PUBLIC_BASE_URL 改为 https://your-domain.com 重新部署
```

## 文件清单

| 文件                            | 运行位置 | 作用                                        |
| ----------------------------- | ---- | ----------------------------------------- |
| `deploy.sh`                   | 本机   | 一键入口：打包上传 + 驱动服务器执行                       |
| `env.sh`                      | 本机   | 部署配置（IP、SSH、密码），不入库                       |
| `server/01-prepare-server.sh` | 服务器  | 装 JDK17 / Maven / Node22 / Nginx / Docker |
| `server/02-init-infra.sh`     | 服务器  | 生成密钥与 .env、起 MySQL+Redis、建表、设密码           |
| `server/03-build.sh`          | 服务器  | mvn package + npm build                   |
| `server/04-deploy.sh`         | 服务器  | 切版本上线 + systemd + Nginx + 健康检查            |
| `server/05-rollback.sh`       | 服务器  | 回滚到历史版本                                   |
| `server/06-status.sh`         | 服务器  | 运行状态总览                                    |
| `server/infra-compose.yml`    | 服务器  | MySQL + Redis 定义                          |
| `server/nginx-pdk.conf`       | 服务器  | Nginx 站点模板                                |
| `server/gen-update-keys.py`   | 服务器  | 生成升级密钥（含公钥）                               |
