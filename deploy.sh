#!/bin/bash
set -e

echo "=== OCI Worker 部署脚本 ==="

# 安装 JDK 21
if ! java -version 2>&1 | grep -q "21"; then
    echo "[1/4] 安装 JDK 21..."
    apt-get update -qq
    apt-get install -y -qq wget
    wget -q https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.7%2B6/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.7_6.tar.gz -O /tmp/jdk21.tar.gz
    mkdir -p /opt/java
    tar -xzf /tmp/jdk21.tar.gz -C /opt/java
    ln -sf /opt/java/jdk-21.0.7+6-jre/bin/java /usr/local/bin/java
    rm -f /tmp/jdk21.tar.gz
    echo "JDK 21 安装完成"
else
    echo "[1/4] JDK 21 已安装，跳过"
fi

java -version

# 创建目录
echo "[2/4] 创建目录..."
mkdir -p /opt/oci-worker/keys
cd /opt/oci-worker

# 下载 JAR
echo "[3/4] 下载最新 JAR..."
wget -q https://github.com/mastalee928/oci-worker/releases/download/latest/oci-worker-1.0.0.jar -O oci-worker.jar
echo "下载完成"

# 创建配置文件
if [ ! -f application.yml ]; then
    echo "[4/4] 创建默认配置文件..."
    cat > application.yml << 'EOF'
server:
  port: 8818

web:
  account: admin
  password: admin123

spring:
  threads:
    virtual:
      enabled: true
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/oci_worker?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: ociworker
    password: ociworker123
  sql:
    init:
      mode: never

mybatis-plus:
  mapper-locations: classpath*:com/ociworker/mapper/xml/*.xml,classpath*:mapper/*.xml

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} %-5level %msg%n"
  level:
    com.oracle.bmc: error

oci-cfg:
  key-dir-path: ./keys
EOF
    echo "配置文件已创建，请修改 application.yml 中的账号密码"
else
    echo "[4/4] 配置文件已存在，跳过"
fi

# 创建 systemd 服务
cat > /etc/systemd/system/oci-worker.service << 'EOF'
[Unit]
Description=OCI Worker
After=network.target docker.service

[Service]
Type=simple
WorkingDirectory=/opt/oci-worker
ExecStart=/usr/local/bin/java -Xmx256m -Duser.timezone=Asia/Shanghai -Duser.dir=/opt/oci-worker -jar oci-worker.jar --spring.config.additional-location=file:/opt/oci-worker/application.yml
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable oci-worker
systemctl restart oci-worker

# 部署 WebSSH（需要 Docker）
if command -v docker &>/dev/null; then
    echo "[5/5] 部署 WebSSH..."
    WEBSSH_DIR="/opt/oci-worker/webssh"
    if [ -d "$WEBSSH_DIR" ] && [ -f "$WEBSSH_DIR/Dockerfile" ]; then
        cd "$WEBSSH_DIR"
        docker compose down 2>/dev/null || true
        docker compose up -d --build
        echo "WebSSH 已启动（端口 8008）"
    else
        echo "未找到 webssh 目录，跳过 WebSSH 部署"
        echo "可手动部署: 将项目中 webssh/ 目录复制到 $WEBSSH_DIR 后运行 docker compose up -d --build"
    fi
else
    echo "[5/5] 未安装 Docker，跳过 WebSSH 部署"
    echo "如需 WebSSH，请先安装 Docker: curl -fsSL https://get.docker.com | sh"
fi

PUBLIC_IP=$(curl -s ifconfig.me)
echo ""
echo "=== 部署完成 ==="
echo "OCI Worker: http://$PUBLIC_IP:8818"
echo "默认账号:   admin / admin123"
echo "WebSSH:     http://$PUBLIC_IP:8008"
echo ""
echo "常用命令:"
echo "  查看状态: systemctl status oci-worker"
echo "  查看日志: journalctl -u oci-worker -f"
echo "  重启服务: systemctl restart oci-worker"
echo "  停止服务: systemctl stop oci-worker"
