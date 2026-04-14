# OCI Worker

基于 Spring Boot 3 + Vue 3 + Ant Design Vue 开发的 Oracle Cloud (OCI) 管理面板。

## 功能特性

- **多租户配置管理**：批量添加、编辑、删除，快速导入 OCI 配置，PEM 拖拽上传
- **批量抢机 + 断点续抢**：多租户同时抢机，任务持久化，服务重启自动恢复
- **实例管理**：启动/停止/重启/终止，安全列表/引导卷/网络/流量统计一站式查看
- **用户管理**：查看域中用户，创建用户，重置密码，清理 MFA，管理员组操作
- **IP 管理**：一键更换公网 IP
- **安全列表管理**：入站/出站规则查看，一键放行所有端口
- **实时日志查看**：WebSocket 实时推送全量后端日志
- **消息通知**：Telegram Bot + 钉钉 Webhook
- **加密备份恢复**：数据迁移
- **登录安全**：Token 24 小时过期，支持在线修改密码

## 技术栈

- **后端**：Spring Boot 3.5 + JDK 21 (虚拟线程) + MyBatis-Plus + MySQL 8.0
- **前端**：Vue 3 + Vite + Ant Design Vue 4 + Pinia + Vue Router 4
- **OCI SDK**：oci-java-sdk 3.83+

---

## 一键安装（推荐）

适用于 Debian / Ubuntu 服务器（支持 ARM64 和 AMD64）。

### 前置条件

- 一台 Linux 服务器（Debian 11+ / Ubuntu 20.04+）
- MySQL 8.0（可通过 Docker 运行，见下方说明）

### 第一步：启动 MySQL

如果服务器上没有 MySQL，使用 Docker 快速启动：

```bash
# 安装 Docker（如已安装跳过）
curl -fsSL https://get.docker.com | sh

# 启动 MySQL 容器
docker run -d \
  --name oci-worker-mysql \
  --restart always \
  -p 3306:3306 \
  -v /opt/oci-worker/data/mysql:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD=root123 \
  -e MYSQL_DATABASE=oci_worker \
  -e MYSQL_USER=ociworker \
  -e MYSQL_PASSWORD=ociworker123 \
  -e TZ=Asia/Shanghai \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

### 第二步：一键部署应用

```bash
bash <(curl -sL https://raw.githubusercontent.com/mastalee928/oci-worker/master/deploy.sh)
```

脚本会自动完成：
1. 安装 JDK 21（如未安装）
2. 下载最新 JAR 到 `/opt/oci-worker/`
3. 生成默认配置文件 `application.yml`（已存在则跳过）
4. 创建 systemd 服务并启动

部署完成后访问 `http://你的IP:8818`，默认账号密码：`admin` / `admin123`

> **首次登录后请立即到「系统设置 → 安全设置」修改密码。**

---

## 更新方法

```bash
# 停止服务
sudo systemctl stop oci-worker

# 下载最新版 JAR（覆盖旧文件）
cd /opt/oci-worker
sudo curl -L -o oci-worker.jar \
  https://github.com/mastalee928/oci-worker/releases/download/latest/oci-worker-1.0.0.jar

# 启动服务
sudo systemctl start oci-worker

# 查看启动日志（确认启动成功）
sudo journalctl -u oci-worker -f --no-pager
```

也可以重新运行安装脚本，它会自动跳过已安装的组件并下载最新 JAR：

```bash
bash <(curl -sL https://raw.githubusercontent.com/mastalee928/oci-worker/master/deploy.sh)
```

---

## 常用运维命令

```bash
# 查看服务状态
systemctl status oci-worker

# 查看实时日志
journalctl -u oci-worker -f

# 重启服务
systemctl restart oci-worker

# 停止服务
systemctl stop oci-worker

# 编辑配置（修改后需重启）
nano /opt/oci-worker/application.yml
```

---

## 本地开发

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

### 生产构建

```bash
# 构建前端
cd frontend && npm run build

# 构建后端（前端产物已自动输出到 backend/src/main/resources/dist/）
cd backend && mvn clean package -DskipTests

# 运行
java -jar target/oci-worker-1.0.0.jar
```

---

## 配置说明

编辑 `/opt/oci-worker/application.yml`：

```yaml
server:
  port: 8818            # 服务端口

web:
  account: admin        # 登录账号
  password: admin123    # 初始密码（登录后可在系统设置中修改）

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/oci_worker?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: ociworker
    password: ociworker123

oci-cfg:
  key-dir-path: ./keys  # PEM 密钥存放目录
```

### 手动创建 MySQL 数据库

如果使用已有的 MySQL 服务，需要先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS oci_worker
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'ociworker'@'%' IDENTIFIED BY 'ociworker123';
GRANT ALL PRIVILEGES ON oci_worker.* TO 'ociworker'@'%';
FLUSH PRIVILEGES;
```

---

## 目录结构

```
/opt/oci-worker/          # 生产部署目录
├── oci-worker.jar        # 应用 JAR
├── application.yml       # 配置文件
└── keys/                 # PEM 密钥目录

oci-worker/               # 源码目录
├── backend/              # Spring Boot 后端
│   ├── pom.xml
│   └── src/
├── frontend/             # Vue 3 前端
│   ├── package.json
│   └── src/
├── deploy.sh             # 一键部署脚本
├── docker-compose.yml    # MySQL Docker 配置
└── README.md
```

## 免责声明

- 因开机、换 IP 频率过高而导致的封号，使用者自行承担
- 建议使用 Nginx 反向代理配置 HTTPS 访问
- 建议使用密钥登录服务器，防止 SSH 爆破
- 首次登录后请立即修改默认密码
