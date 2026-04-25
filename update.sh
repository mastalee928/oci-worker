#!/bin/bash
set -e

INSTALL_DIR="/opt/oci-worker"
JAR_NAME="oci-worker.jar"
SERVICE_NAME="oci-worker"
REPO="mastalee928/oci-worker"
ASSET_NAME="oci-worker-1.0.0.jar"

ts() { date "+%Y-%m-%d %H:%M:%S"; }

detect_port() {
    local cfg="${INSTALL_DIR}/application.yml"
    local p=""
    if [ -f "${cfg}" ]; then
        # naive yaml parse: take the first "port:" after "server:"
        p="$(awk '
            $0 ~ /^server:/ { in_server=1; next }
            in_server && $0 ~ /^[[:space:]]*port:[[:space:]]*[0-9]+/ {
                gsub(/[^0-9]/, "", $0); print $0; exit
            }
            in_server && $0 ~ /^[^[:space:]]/ { in_server=0 }
        ' "${cfg}" 2>/dev/null || true)"
    fi
    if [ -z "${p}" ]; then
        p="8818"
    fi
    echo "${p}"
}

wait_for_port() {
    local port="$1" timeout_s="${2:-90}"
    local start now
    start="$(date +%s)"
    while true; do
        if curl -fsS --max-time 1 "http://127.0.0.1:${port}/" >/dev/null 2>&1; then
            return 0
        fi
        now="$(date +%s)"
        if [ $((now-start)) -ge "${timeout_s}" ]; then
            return 1
        fi
        sleep 1
    done
}

echo "=== OCI Worker 一键更新 ==="
echo ""

if [ ! -f "$INSTALL_DIR/$JAR_NAME" ]; then
    echo "❌ 未找到 $INSTALL_DIR/$JAR_NAME，请先执行部署脚本安装"
    exit 1
fi

OLD_SIZE=$(stat -c%s "$INSTALL_DIR/$JAR_NAME" 2>/dev/null || echo "0")
echo "📦 当前 JAR 大小: $(numfmt --to=iec $OLD_SIZE 2>/dev/null || echo "${OLD_SIZE} bytes")"

echo "[$(ts)] 🔍 获取最新版本下载地址..."
JAR_URL=$(curl -sL "https://api.github.com/repos/${REPO}/releases/latest" \
  | grep -o "https://github.com/${REPO}/releases/download/[^\"]*${ASSET_NAME}" \
  | head -1)

if [ -z "$JAR_URL" ]; then
    JAR_URL="https://github.com/${REPO}/releases/download/latest/${ASSET_NAME}"
    echo "⚠  API 获取失败，使用默认地址"
fi
echo "📎 下载地址: $JAR_URL"

echo "[$(ts)] ⏹  停止服务..."
sudo systemctl stop "$SERVICE_NAME" 2>/dev/null || true
sleep 1

echo "[$(ts)] ⬇  下载最新版本（首次无输出可能在 DNS/TLS/下载中，请稍等）..."
sudo curl -fSL --retry 3 --retry-delay 5 --connect-timeout 15 --max-time 600 \
  --progress-bar -o "$INSTALL_DIR/$JAR_NAME.tmp" "$JAR_URL"

NEW_SIZE=$(stat -c%s "$INSTALL_DIR/$JAR_NAME.tmp" 2>/dev/null || echo "0")
if [ "$NEW_SIZE" -lt 1000 ]; then
    echo "❌ 下载失败：文件大小异常 (${NEW_SIZE} bytes)"
    sudo rm -f "$INSTALL_DIR/$JAR_NAME.tmp"
    echo "▶  恢复启动旧版本..."
    sudo systemctl start "$SERVICE_NAME"
    exit 1
fi

sudo mv "$INSTALL_DIR/$JAR_NAME.tmp" "$INSTALL_DIR/$JAR_NAME"
echo "📦 新 JAR 大小: $(numfmt --to=iec $NEW_SIZE 2>/dev/null || echo "${NEW_SIZE} bytes")"

echo "[$(ts)] ▶  启动服务..."
sudo systemctl start "$SERVICE_NAME"

PORT="$(detect_port)"
echo "[$(ts)] ⏳ 等待服务就绪（最多 90s，端口 ${PORT}）..."
if systemctl is-active --quiet "$SERVICE_NAME" && wait_for_port "${PORT}" 90; then
    echo ""
    echo "✅ 更新完成，服务已启动"
    echo "🌐 本机访问: http://127.0.0.1:${PORT}"
    echo "📋 查看日志: sudo journalctl -u $SERVICE_NAME -f --no-pager"
else
    echo ""
    echo "⚠  服务启动异常，请检查日志:"
    echo "   sudo journalctl -u $SERVICE_NAME -n 30 --no-pager"
    echo "   sudo systemctl status $SERVICE_NAME --no-pager"
fi
