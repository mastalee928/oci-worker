# 验证本机到 New API (OpenAI 兼容) 是否通畅，用于对照 Void 若仍 403 时的排查。
# 用法（PowerShell）:
#   $env:NEAPI_KEY = "sk-你的key"
#   .\tools\verify-newapi-for-void.ps1
#
# 可选参数:
#   -BaseHost "https://newapi.ee"   # 不要带 /v1 结尾，脚本会测 /v1/chat/completions 与 /v1/models

param(
  [string] $BaseHost = "https://newapi.ee"
)

$ErrorActionPreference = "Stop"

if (-not $env:NEAPI_KEY) {
  Write-Host "请先在当前终端设置: `$env:NEAPI_KEY = 'sk-...'" -ForegroundColor Yellow
  exit 1
}

$BaseHost = $BaseHost.TrimEnd("/")
$ChatUrl = "$BaseHost/v1/chat/completions"
$ModelsUrl = "$BaseHost/v1/models"
$Key = $env:NEAPI_KEY
$Model = "xai.grok-4.20-multi-agent-0309"
$Body = (@{
  model    = $Model
  messages = @(@{ role = "user"; content = "hi" })
  max_tokens = 20
} | ConvertTo-Json -Compress -Depth 5)

function Invoke-One {
  param([string] $Name, [hashtable] $Headers)
  try {
    $r = Invoke-WebRequest -Uri $ChatUrl -Method POST -Headers $Headers -Body $Body `
      -ContentType "application/json; charset=utf-8" -UseBasicParsing -TimeoutSec 60
    Write-Host "[$Name]  OK  $($r.StatusCode)  字节=$($r.RawContentLength)" -ForegroundColor Green
    return $true
  } catch {
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    $detail = $_.ErrorDetails.Message
    if (-not $detail) { $detail = $_.Exception.Message }
    Write-Host "[$Name]  FAIL  HTTP=$code  $detail" -ForegroundColor Red
    return $false
  }
}

Write-Host "=== 目标 ===" -ForegroundColor Cyan
Write-Host "  POST  $ChatUrl"
Write-Host "  GET   $ModelsUrl"
Write-Host ""

$auth = @{ Authorization = "Bearer $Key" }

Write-Host "=== 1) 最简头（与此前能 200 的调用一致）===" -ForegroundColor Cyan
$ok1 = Invoke-One "minimal" ($auth + @{
    "Content-Type" = "application/json"
  })

Write-Host ""
Write-Host "=== 2) 带常见 Chrome/Electron User-Agent（模拟桌面壳）===" -ForegroundColor Cyan
$ok2 = Invoke-One "ua-electron" ($auth + @{
    "Content-Type" = "application/json"
    "User-Agent"   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
  })

Write-Host ""
Write-Host "=== 3) GET /v1/models ===" -ForegroundColor Cyan
try {
  $m = Invoke-WebRequest -Uri $ModelsUrl -Headers $auth -UseBasicParsing -TimeoutSec 45
  Write-Host "models  OK  $($m.StatusCode)  字节=$($m.RawContentLength)" -ForegroundColor Green
} catch {
  $code = $null
  if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  Write-Host "models  FAIL  HTTP=$code" -ForegroundColor Red
}

Write-Host ""
if ($ok1) {
  Write-Host "本机脚本能连上 New API。若 Void 仍 403，问题在 Void 侧，请看脚本末尾 4 项与下方下一步。`n" -ForegroundColor Green
} else {
  Write-Host "本机脚本也失败，请先解决网络/Key/服务后再试 Void。`n" -ForegroundColor Yellow
}

Write-Host "【Void 里建议立刻核对的 4 项】" -ForegroundColor Cyan
Write-Host "  1) OpenAI-Compatible 的 Base 先试: $BaseHost  与  ${BaseHost}/v1  二选一，保存后重开对话"
Write-Host "  2) 同一对话里模型选: $Model  （与 New API 里可用模型一致）"
Write-Host "  3) 关系统代理 / 退出 TUN(Clash 等) 再试，或让 Void 与系统代理设置一致"
Write-Host "  4) 仍失败：用 Fiddler/Charles 抓包，看 403 时真实 URL 是否仍是 .../v1/chat/completions"
