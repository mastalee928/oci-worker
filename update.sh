#!/bin/bash
set -e

INSTALL_DIR="/opt/oci-worker"
JAR_NAME="oci-worker.jar"
JAR_URL="https://github.com/mastalee928/oci-worker/releases/download/latest/oci-worker-1.0.0.jar"
SERVICE_NAME="oci-worker"

echo "=== OCI Worker 一键更新 ==="
echo ""

if [ ! -f "$INSTALL_DIR/$JAR_NAME" ]; then
    echo "❌ 未找到 $INSTALL_DIR/$JAR_NAME，请先执行部署脚本安装"
    exit 1
fi

OLD_SIZE=$(stat -c%s "$INSTALL_DIR/$JAR_NAME" 2>/dev/null || echo "0")
echo "📦 当前 JAR 大小: $(numfmt --to=iec $OLD_SIZE 2>/dev/null || echo "${OLD_SIZE} bytes")"

echo "⏹  停止服务..."
sudo systemctl stop "$SERVICE_NAME" 2>/dev/null || true
sleep 1

echo "⬇  下载最新版本..."
sudo curl -fSL -o "$INSTALL_DIR/$JAR_NAME.tmp" "$JAR_URL"

NEW_SIZE=$(stat -c%s "$INSTALL_DIR/$JAR_NAME.tmp" 2>/dev/null || echo "0")
if [ "$NEW_SIZE" -lt 1000 ]; then
    echo "❌ 下载失败：文件大小异常 (${NEW_SIZE} bytes)，可能是 404"
    sudo rm -f "$INSTALL_DIR/$JAR_NAME.tmp"
    echo "▶  恢复启动旧版本..."
    sudo systemctl start "$SERVICE_NAME"
    exit 1
fi

sudo mv "$INSTALL_DIR/$JAR_NAME.tmp" "$INSTALL_DIR/$JAR_NAME"
echo "📦 新 JAR 大小: $(numfmt --to=iec $NEW_SIZE 2>/dev/null || echo "${NEW_SIZE} bytes")"

echo "▶  启动服务..."
sudo systemctl start "$SERVICE_NAME"
sleep 2

if systemctl is-active --quiet "$SERVICE_NAME"; then
    echo ""
    echo "✅ 更新完成，服务已启动"
    echo "📋 查看日志: sudo journalctl -u $SERVICE_NAME -f --no-pager"
else
    echo ""
    echo "⚠  服务启动异常，请检查日志:"
    echo "   sudo journalctl -u $SERVICE_NAME -n 30 --no-pager"
fi
