# Oracle AI Client Compatibility

OCIworker exposes OpenAI-compatible and Anthropic-compatible routes for Oracle AI forwarding.

## Base URL

Use the same `/v1` base for single-account ports and load-balance ports:

```text
http://<host>:<port>/v1
```

Examples:

```text
http://127.0.0.1:30001/v1
http://127.0.0.1:50000/v1
```

## Codex / Codex++

Use Chat Completions when the client allows selecting the wire API:

```text
Wire API: Chat Completions
Base URL: http://<host>:<port>/v1
API Key: sk-...
Model: xai.grok-4.3
```

Responses API is also supported for compatible clients:

```text
Wire API: Responses
Base URL: http://<host>:<port>/v1
API Key: sk-...
Model: xai.grok-4.3
```

## Claude Code / CC Switch / Anthropic SDK

Use Anthropic Messages-compatible settings:

```text
ANTHROPIC_BASE_URL=http://<host>:<port>/v1
ANTHROPIC_API_KEY=sk-...
ANTHROPIC_DEFAULT_SONNET_MODEL=xai.grok-4.3
```

OCIworker accepts both `x-api-key` and `Authorization: Bearer` on `/v1/messages`.

## Other Anthropic-Compatible Clients

OpenClaw, Hermes, or similar clients should use:

```text
Endpoint: POST /v1/messages
Base URL: http://<host>:<port>/v1
API Key header: x-api-key or Authorization: Bearer
```

Supported core features:

- text messages
- system text, including system text arrays
- tools
- tool_use and tool_result history
- tool_choice auto, none, any, and named tool
- Anthropic image blocks, converted to OpenAI `image_url`
- Anthropic document blocks, converted to extracted text for common text and office formats
- safe public `http/https` document URLs, downloaded and parsed with the same document limits
- SQLite database files (`.db`, `.sqlite`, `.sqlite3`), converted to a read-only schema and sample-row summary
- streaming responses via Anthropic SSE events
- count_tokens compatibility via `/v1/count_tokens` and `/v1/messages/count_tokens`

Current compatibility limits:

- document parsing is text extraction only; scanned PDFs and image-only documents still need OCR and may return a clear unsupported notice
- SQLite database extraction is read-only; it helps the model understand schema/data and write SQL, but does not modify the uploaded database
- remote URL documents are limited to 10MB, 3 redirects, short timeouts, and public addresses only; localhost, private networks, link-local, and metadata addresses are rejected
- Anthropic streaming is compatibility-first; tool calls are stable, but upstream token deltas may be buffered by the bridge
- legacy `/v1/complete` is not implemented
