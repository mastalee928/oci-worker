# 串口叠行修复前基线（可还原）

**记录时间**：2026-05  
**原因**：`bdf9c31` 串口 80×24 + 二进制透传 上线后，用户反馈打开串口仅空白光标；需保留可回退点。

## Git 信息

| 项 | 值 |
|----|-----|
| **基线提交** | `94e48c1` |
| **基线说明** | `feat(instance): refresh basic info in drawer and serial console restart actions`（含串口重启按钮，**不含**叠行修复） |
| **问题提交** | `bdf9c31` — 首版叠行修复（`stty raw` + BinaryMessage，可能导致空白屏） |
| **二次修复** | 移除 `stty raw`；串口输出改 ISO-8859-1 文本帧；保留 80×24 + `TERM=vt100` |
| **本地标签** | `pre-console-serial-fix` → `94e48c1` |

## 还原命令

### 仅还原串口/WebSSH 相关 4 个文件（推荐）

```bash
git checkout 94e48c1 -- \
  backend/src/main/java/com/ociworker/service/ConsoleService.java \
  backend/src/main/java/com/ociworker/webssh/WebSshConsoleTerminalWebSocketHandler.java \
  backend/src/main/resources/static/webssh/index.html \
  backend/src/main/resources/static/webssh/static/js/app.js
```

然后重新编译/重启后端，浏览器 **强刷** WebSSH 页（或清缓存）。

### 整仓回到基线提交（慎用，会丢掉 `94e48c1` 之后的所有本地改动）

```bash
git checkout 94e48c1
```

### 用 revert 撤销叠行修复提交（保留历史）

```bash
git revert bdf9c31 --no-edit
```

## `94e48c1` 当时包含的串口相关能力

- 串口 WebSSH hash 传 `userId` / `instanceId` / `region` / `state`
- 顶栏「重启」「断电重启」
- **未包含**：80×24 固定、BinaryMessage、`TERM=vt100`、`stty raw`

## 后续改坏时

- 未经授权 **不要** 在基线上继续叠修；先 `git checkout 94e48c1 -- <paths>` 或 `git revert bdf9c31`
- 推远程 tag 需用户明确同意：`git push origin pre-console-serial-fix`
