# PDK 编译与部署手册

本文档覆盖源码检查、本地编译、传统 Linux 部署、Docker Compose 部署、数据库初始化、升级回滚和上线验收。

## 1. 组件与端口

| 组件 | 技术 | 默认端口 | 产物 |
| --- | --- | --- | --- |
| 后端 | Java 17、Spring Boot 3.3、MyBatis-Plus | 8080 | `backend-springboot/target/pdk-commercial-system-1.0.0-SNAPSHOT.jar` |
| 管理端 | Vue 3、Vite、Element Plus | 开发 8081 | `admin-vue3/dist/` |
| 客户端 | Python 3.10+、PyQt6 | 无 | Python 源码，可选 PyInstaller 打包 |
| MySQL | MySQL 8.x | 3306 | 持久化业务数据 |
| Redis | Redis 7.x | 6379 | 设备活跃缓存和短效资源租约 |

## 2. 编译环境

- JDK 17，确保 `JAVA_HOME` 指向 JDK 根目录。
- Maven 3.9+。
- Node.js 20 或 22、npm 10+。
- Python 3.10+。
- MySQL 8.x 与 Redis 7.x 用于运行时验收；纯单元测试不需要外部服务。

Windows PowerShell 示例：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
java -version
mvn.cmd -version
node --version
npm.cmd --version
python --version
```

## 3. 后端编译与测试

```powershell
cd E:\pdk\backend-springboot
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
mvn.cmd clean test
mvn.cmd clean package
```

Linux：

```bash
cd /opt/src/pdk/backend-springboot
mvn clean test
mvn clean package
```

`mvn test` 当前覆盖卡密核销、金额防篡改、设备互斥、试用限制、资源领取、成功扣次、失败免责、重复上报幂等、租约过期、财务计算、Redis 降级、AES 加解密和角色矩阵。任何测试失败都不应继续发布。

启动前需要的环境变量：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/pdk_biz_db?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
export DB_USER='pdk'
export DB_PASS='replace-me'
export SPRING_DATA_REDIS_HOST='127.0.0.1'
export PDK_ADMIN_PASSWORD_PEPPER='replace-with-random-value'
export PDK_SECURITY_ROOT_SALT='replace-with-random-value'
export PDK_SMS_PROVIDER='local'              # 仅开发联调
export PDK_SMS_FIXED_CODE_ENABLED='false'    # 默认及正式环境禁止固定验证码
```

当前 `AliyunSmsSender` 仍是明确返回 `50320` 的预留适配器，尚未安装阿里云 SDK，因此不能把 `PDK_SMS_PROVIDER` 直接改成 `aliyun` 上生产。正式发布前需完成阿里云适配和回执测试；本地模式只允许开发联调，验证码会写入服务端日志。

运行：

```bash
java -XX:MaxRAMPercentage=75.0 \
  -jar target/pdk-commercial-system-1.0.0-SNAPSHOT.jar
```

健康检查：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

应返回 `{"status":"UP"}`。

## 4. MySQL 自动初始化机制

后端启动顺序如下：

1. JDBC URL 的 `createDatabaseIfNotExist=true` 在空环境创建 `pdk_biz_db`。
2. `spring.sql.init.mode=always` 自动加载 classpath 下的 `schema-mysql.sql`。
3. SQL 使用 `CREATE TABLE IF NOT EXISTS`、`ON DUPLICATE KEY UPDATE` 和条件插入，因此同一最终结构可以重复执行。
4. `continue-on-error=false` 保证任何 DDL/种子错误都会终止启动，不允许应用在残缺结构上运行。

数据库账号首次建库时需要 `CREATE` 权限；如果生产环境禁止应用账号建库，应由 DBA 预先创建空的 `pdk_biz_db` 并授予该库全部 DDL/DML 权限。当前基线不包含旧表 ALTER：已有旧结构时先备份并重建为空库，再启动新版本。

当前机制适合原型与全新部署。产生必须保留的正式生产数据后，应在下一次结构变化前迁移到 Flyway/Liquibase 版本化迁移。

### IDEA 启动日志说明

运行 `compile spring-boot:run` 后出现 `Started PdkApplication` 就表示后端已经启动。Maven 任务会持续占用运行窗口来承载 Web 服务，这不是卡住；停止服务时使用 IDEA 的停止按钮。

项目通过 `.mvn/jvm.config`、Maven 编码属性和 Spring Boot 运行参数统一使用 UTF-8。如果控制台仍显示 `Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=GBK`，说明 IDEA 运行配置或 Windows 环境变量仍注入了 GBK；应在 IDEA 的 Maven Runner/运行配置中删除该环境变量，或改为 `-Dfile.encoding=UTF-8`。

MyBatis 默认不再逐条打印 SqlSession、JDBC 和定时清理 SQL。需要临时检查完整 SQL 时，在 IDEA 运行配置增加：

```text
PDK_MAPPER_LOG_LEVEL=DEBUG
```

## 5. 管理端编译

```powershell
cd E:\pdk\admin-vue3
npm.cmd ci
npm.cmd run build
```

Linux：

```bash
cd /opt/src/pdk/admin-vue3
npm ci
npm run build
```

将 `dist/` 发布到 Nginx，并配置：

- 所有 `/api/` 请求反向代理到 Spring Boot 8080。
- Vue history 路由使用 `try_files $uri $uri/ /index.html`。
- 外网必须启用 HTTPS。

仓库中的 `admin-vue3/nginx.conf` 可直接作为容器部署模板。

## 6. PyQt 客户端编译

```powershell
cd E:\pdk\client-pyqt
python -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\python -m pip install -r requirements.txt
$env:PDK_SECURITY_ROOT_SALT='与服务端完全一致的值'
.\.venv\Scripts\python main.py
```

可选单文件打包：

```powershell
.\.venv\Scripts\python -m pip install pyinstaller
.\.venv\Scripts\pyinstaller --noconfirm --windowed --name PdkClient main.py
```

产物位于 `client-pyqt/dist/PdkClient/`。AES 根盐不能硬编码进公开发布包；生产客户端应改为设备注册后通过安全派生或密钥服务获得版本化密钥。

## 7. Docker Compose 部署

服务器安装 Docker Engine 与 Compose 插件后：

```bash
cd /opt/pdk
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
vi deploy/.env
docker compose --env-file deploy/.env -f deploy/docker-compose.yml build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f backend
```

访问 `http://服务器IP:8081`。Compose 不向宿主机暴露 MySQL、Redis 和后端端口，管理端 Nginx 通过容器网络访问后端。

备份：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml exec -T mysql \
  sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction pdk_biz_db' \
  > pdk_biz_db_$(date +%F_%H%M%S).sql
```

## 8. 传统 Linux 服务部署

推荐目录：

```text
/opt/pdk/backend/app.jar
/opt/pdk/admin/dist/
/etc/pdk/backend.env
/etc/systemd/system/pdk-backend.service
```

`/etc/systemd/system/pdk-backend.service`：

```ini
[Unit]
Description=PDK Spring Boot Backend
After=network-online.target mysql.service redis.service

[Service]
User=pdk
Group=pdk
WorkingDirectory=/opt/pdk/backend
EnvironmentFile=/etc/pdk/backend.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75.0 -jar /opt/pdk/backend/app.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

启用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now pdk-backend
sudo systemctl status pdk-backend
journalctl -u pdk-backend -f
```

## 9. 升级与回滚

1. 升级前执行 MySQL 全量备份并保存当前 JAR、前端 `dist` 和环境配置。
2. 在预发布环境运行 `mvn clean test`、前端构建及完整客户端调用链。
3. 先停止入口流量，再替换后端 JAR和前端静态文件。
4. 启动后检查 `/actuator/health`、登录、制卡、激活、资源领取、成功/失败上报和权限拦截。
5. 回滚时恢复上一版本 JAR/静态文件。若未来引入非兼容数据库迁移，必须使用对应的数据库回滚脚本或备份。

## 10. 上线验收清单

- MySQL 首次启动建库建表成功，第二次启动无重复数据错误。
- Redis 关闭时健康检查为异常；恢复后短效租约可正常领取。
- 同一个底层资源领取后状态为 `BUSY`，上报或超时后恢复 `HEALTHY`。
- 同一 `leaseTraceId` 重复上报不会重复扣次。
- `SUCCESS` 扣 1 次；网络失败、业务失败和底层账号封禁扣 0 次。
- 底层账号封禁后资源变为 `FAULT_BLACK`。
- 用户解绑后旧设备会话不能继续调用，新设备可以登录。
- `AGENT` 不能访问财务和资源池，且只能看到自己的卡密。
- `FINANCE` 不能生成卡密，`SUPPORT` 只能执行用户支持相关任务。
- 管理端资源列表不返回完整 Session Token。

## 11. 当前生产限制

- Sa-Token 当前使用进程内会话存储，只能运行一个后端实例；部署多副本前必须接入 Sa-Token Redis DAO。
- 短信已抽象为统一发送接口，本地验证码模式仅供开发；正式上线前必须完成 `AliyunSmsSender` SDK/签名/模板/回执实现，并关闭固定验证码和本地发送模式。
- 管理员密码当前为 pepper + SHA-256，正式上线应迁移到 BCrypt 或 Argon2，并增加登录失败锁定和 MFA。
- 当前 SQL 初始化是全新库最终基线，不是版本化迁移工具；旧结构必须先备份并重建，未来保留生产数据后的结构升级应引入 Flyway/Liquibase。
- Redis 租约消费已使用 Lua 保证单次消费，但 Redis 与 MySQL 之间仍不是分布式事务；对严格账务场景建议引入数据库租约表或事务消息。
