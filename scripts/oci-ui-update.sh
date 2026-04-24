#!/usr/bin/env bash
# 从源码拉取、构建前后端、替换 /opt/oci-worker/oci-worker.jar 并重启服务（需 sudo）
# 用法：
#   cd /path/to/oci-worker
#   bash scripts/oci-ui-update.sh
# 或：
#   OCI_GIT_REF=master bash scripts/oci-ui-update.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

OCI_GIT_REF="${OCI_GIT_REF:-feature/ui-polish}"
JAR_NAME="${JAR_NAME:-oci-worker-1.0.0.jar}"
JAR_TARGET="${JAR_TARGET:-/opt/oci-worker/oci-worker.jar}"
UNIT="${UNIT:-oci-worker}"

# 若未设 JAVA_HOME，在常见 Linux 路径里猜 OpenJDK 21
if ! command -v javac >/dev/null 2>&1; then
  for d in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/temurin-21-jdk* /opt/java/*21*; do
    if [ -d "$d" ] && [ -x "$d/bin/javac" ]; then
      export JAVA_HOME="$d"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "错误: 未找到 mvn，请先安装: apt install -y maven   （并确保已安装 openjdk-21-jdk）"
  exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
  echo "错误: 未找到 javac，请设置 JAVA_HOME 为 JDK 21 安装目录（不是 JRE）"
  echo "  例: export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64"
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "错误: 未找到 npm，请安装 Node.js 18+（如 NodeSource 或 apt install npm）"
  exit 1
fi

echo "==> 仓库: $REPO_ROOT"
echo "==> 分支/引用: $OCI_GIT_REF"
git fetch origin
git checkout "$OCI_GIT_REF"
git pull origin "$OCI_GIT_REF"

echo "==> 前端构建"
( cd "$REPO_ROOT/frontend" && npm install && npm run build )

echo "==> 后端打包 (JAVA_HOME=${JAVA_HOME:-未设置; 将使用当前 PATH 中的 javac})"
( cd "$REPO_ROOT/backend" && mvn clean package -DskipTests )

BUILT_JAR="$REPO_ROOT/backend/target/$JAR_NAME"
if [ ! -f "$BUILT_JAR" ]; then
  echo "未找到 $BUILT_JAR，改列目录:"
  ls -la "$REPO_ROOT/backend/target/"*.jar 2>/dev/null || true
  exit 1
fi

STAMP=$(date +%Y%m%d%H%M)
echo "==> 备份并部署 $JAR_TARGET"
sudo cp "$JAR_TARGET" "${JAR_TARGET}.bak.$STAMP"
sudo cp "$BUILT_JAR" "$JAR_TARGET"
ls -la "$JAR_TARGET"

echo "==> 重启 $UNIT"
sudo systemctl restart "$UNIT"
sudo systemctl --no-pager is-active "$UNIT" && echo "服务状态: 运行中" || (echo "服务未 active，见 journal:"; sudo journalctl -u "$UNIT" -n 40 --no-pager; exit 1)

echo "完成。请浏览器对面板地址按 Ctrl+F5 强刷。"
