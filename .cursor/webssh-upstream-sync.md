# WebSSH 上游同步记录

- **来源**: https://github.com/a06342637/webssh2
- **提交**: `f77dcef`（main，2026-05-02）
- **同步内容**: `public/` → `backend/src/main/resources/static/webssh/`；整仓 → `webssh/`
- **OCI 适配**（勿在上游原样覆盖）:
  - 静态资源前缀 `/webssh/static/`
  - API/WebSocket 前缀 `/webssh-api/`（对应原 Go `/term`、`/sysinfo`、`/file/*`、`/config`）
  - 已删除页脚「棍之勇者」及外链
  - `/webssh-api/config` 返回 `showFooter: false`

还原上游纯前端（仅 static）:

```bash
git clone --depth 1 https://github.com/a06342637/webssh2.git /tmp/webssh2
# 再按上文做路径与页脚补丁后覆盖 static/webssh
```
