# PDK 云控商业化平台

仓库包含三个可运行部分：

- `backend-springboot`：Spring Boot 3、MyBatis-Plus、MySQL、Redis、Sa-Token
- `admin-vue3`：Vue 3 + Element Plus 多角色管理后台
- `client-pyqt`：PyQt6 客户端接口联调 Demo

完整的模块边界、角色权限和接口说明见 [IMPLEMENTATION_ANALYSIS.md](./IMPLEMENTATION_ANALYSIS.md)。

完整编译、Docker/传统 Linux 部署、升级回滚与验收步骤见 [BUILD_AND_DEPLOY.md](./BUILD_AND_DEPLOY.md)。

## 1. 启动基础服务

准备 MySQL 8 和 Redis。默认连接为：

- MySQL：`localhost:3306`，账号 `root`，密码读取 `DB_PASS`
- Redis：`localhost:6379`

## 2. 启动后端

```powershell
cd backend-springboot
$env:DB_USER='root'
$env:DB_PASS='你的MySQL密码'
mvn spring-boot:run
```

首次启动会由 JDBC 自动创建 `pdk_biz_db`，随后 Spring Boot 自动执行 `src/main/resources/schema-mysql.sql`。脚本使用 `IF NOT EXISTS`、`ON DUPLICATE KEY UPDATE` 和条件插入，可重复启动。

## 3. 启动管理端

```powershell
cd admin-vue3
npm install
npm run dev
```

访问 `http://localhost:8081`。默认超级管理员为 `13454118762 / admin123`。注册用户默认是 `CUSTOMER`，只能由超级管理员提升为 `PARTNER` 后登录管理后台并制套餐、制卡。

本地短信使用统一短信接口的 `local` 实现；使用 `--spring.profiles.active=local` 才会启用固定测试验证码，验证码由 `PDK_SMS_FIXED_CODE` 配置。默认配置和生产环境均关闭固定验证码；生产环境应将 `PDK_SMS_PROVIDER` 切换为 `aliyun`。当前仓库已预留阿里云配置和适配器外壳，正式 SDK 调用需在取得 AccessKey、签名和模板后接入。

## 4. 启动 PyQt 客户端

参见 [client-pyqt/README.md](./client-pyqt/README.md)。

> SQL 中的管理员和小号资源均为本地联调种子。部署前必须替换默认密码、密码 pepper、数据库密码、AES 根密钥及演示 Token。

## 5. 分支说明

- master 分支：是原先拼多多操作类工具，包含小号，下单，退单操作
- pkd分支：是一个通用的授权，业务系统，和客户端联合联合用来限制客户端登录，注销，检测版本升级

## 6. 客户端升级系统

服务端、Vue 3 管理后台、PyQt 客户端和独立 Windows updater 的一期实现与部署顺序见 [客户端升级系统一期实施方案](./docs/CLIENT_UPDATE_IMPLEMENTATION_PLAN.md)。生产启用强制更新前必须执行 [升级验收指南](./docs/UPGRADE_TESTING_GUIDE.md)，并先完成旧客户端桥接覆盖。
