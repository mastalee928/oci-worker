# 终端页上游同步记录

- **来源**: https://github.com/a06342637/webssh2
- **提交**: `f77dcef`（main，2026-05-02）
- **历史导入关系**: 过去曾从上游 `public/` 导入 `backend/src/main/resources/static/webssh/`，整仓内容保留在 `webssh/` 供回溯。
- **当前生产唯一来源**: `backend/src/main/resources/static/webssh/`
- **旧 Go 目录规则**: `webssh/public/` 仅是历史快照，不得自动反向覆盖生产资源；上游更新必须人工适配，并通过 `tools/verify-webssh-static-source.sh`。
- **OCI 适配**（勿在上游原样覆盖）:
  - 静态资源前缀 `/webssh/static/`
  - API/WebSocket 前缀 `/webssh-api/`
  - 已删除页脚「棍之勇者」及外链
  - `/webssh-api/config` 返回 `showFooter: false`
