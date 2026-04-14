# OCI Worker

基于 Spring Boot 3 + Vue 3 + Ant Design Vue 开发的 Oracle Cloud (OCI) 管理面板。

## 功能特性

- **多租户配置管理**：批量添加、编辑、删除，支持模糊搜索和状态筛选
- **批量抢机 + 断点续抢**：多租户同时抢机，任务持久化，服务重启自动恢复
- **实例管理**：启动/停止/重启/终止，实例配置修改
- **引导卷管理**：列表查看、配置修改
- **IP 管理**：根据 CIDR 网段更换公网 IP
- **安全列表管理**：入站/出站规则 CRUD，一键放行所有端口
- **实时流量统计**：分钟级别流量监控
- **实时日志查看**：WebSocket 推送后端日志
- **Cloudflare DNS 管理**：DNS 记录 CRUD
- **消息通知**：Telegram Bot + 钉钉 Webhook
- **加密备份恢复**：数据迁移
- **Docker 部署**：docker-compose 一键部署

## 技术栈

- **后端**：Spring Boot 3.5 + JDK 21 (虚拟线程) + MyBatis-Plus + MySQL 8.0
- **前端**：Vue 3 + Vite + Ant Design Vue 4 + Pinia + Vue Router 4
- **OCI SDK**：oci-java-sdk 3.83+
- **部署**：Docker + docker-compose

## 快速开始

### 本地开发

**后端**（需要 JDK 21 + Maven）：

```bash
cd backend
mvn spring-boot:run
```

**前端**（需要 Node.js 18+）：

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173，默认账号密码：`admin` / `admin123`

### Docker 部署

```bash
# 创建数据目录
mkdir -p data/keys data/mysql

# 复制配置文件
cp backend/src/main/resources/application.yml data/application.yml

# 修改 data/application.yml 中的配置：
#   - web 账号密码
#   - MySQL 连接地址改为: jdbc:mysql://mysql:3306/oci_worker?...
#     (Docker 容器间通过服务名 mysql 连接)

# 构建并启动
docker-compose up -d
```

访问 http://your-ip:8818

> **注意**：Docker 部署时，`application.yml` 中的数据库地址应使用容器服务名 `mysql` 而非 `localhost`。

### 生产构建

```bash
# 构建前端
cd frontend && npm run build

# 构建后端 (前端产物已自动输出到 backend/src/main/resources/dist/)
cd backend && mvn clean package -DskipTests

# 运行
java -jar target/oci-worker-1.0.0.jar
```

## 目录结构

```
oci-worker/
├── backend/           # Spring Boot 后端
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── frontend/          # Vue 3 前端
│   ├── package.json
│   └── src/
├── docker-compose.yml
└── README.md
```

## 配置说明

编辑 `application.yml`：

```yaml
web:
  account: admin        # 登录账号
  password: admin123    # 登录密码

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/oci_worker?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: ociworker
    password: ociworker123

oci-cfg:
  key-dir-path: ./keys  # PEM 密钥存放目录
```

### MySQL 准备

本地开发时需要先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS oci_worker
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'ociworker'@'%' IDENTIFIED BY 'ociworker123';
GRANT ALL PRIVILEGES ON oci_worker.* TO 'ociworker'@'%';
FLUSH PRIVILEGES;
```

Docker 部署时会自动创建。

## 免责声明

- 因开机、换IP频率过高而导致的封号，使用者自行承担
- 建议使用 Nginx 反向代理配置 HTTPS 访问
- 建议使用密钥登录服务器，防止 SSH 爆破
