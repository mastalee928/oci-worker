export type TunnelInstallArch = 'amd64' | 'arm64'
export type TunnelInstallOs = 'ubuntu-debian' | 'centos-rhel' | 'alpine' | 'macos' | 'generic'
export type TunnelInstallProtocol = 'quic' | 'http2'

const CF_RELEASE = 'https://github.com/cloudflare/cloudflared/releases/latest/download'

export const tunnelArchOptions = [
  { value: 'amd64', label: 'x86_64 (amd64)' },
  { value: 'arm64', label: 'ARM64 (arm64)' },
]

export const tunnelOsOptions = [
  { value: 'ubuntu-debian', label: 'Ubuntu / Debian' },
  { value: 'centos-rhel', label: 'CentOS / RHEL' },
  { value: 'alpine', label: 'Alpine Linux' },
  { value: 'macos', label: 'macOS' },
  { value: 'generic', label: '通用（Linux 二进制）' },
]

export const tunnelProtocolOptions = [
  { value: 'quic', label: 'QUIC（UDP 7844）' },
  { value: 'http2', label: 'HTTP/2（TCP 443）' },
]

function installSteps(os: TunnelInstallOs, arch: TunnelInstallArch): string[] {
  switch (os) {
    case 'ubuntu-debian': {
      const pkg = arch === 'amd64' ? 'cloudflared-linux-amd64.deb' : 'cloudflared-linux-arm64.deb'
      return [
        '# 1. 安装 cloudflared (Debian/Ubuntu)',
        `curl -fsSL ${CF_RELEASE}/${pkg} -o /tmp/cloudflared.deb`,
        'sudo dpkg -i /tmp/cloudflared.deb',
      ]
    }
    case 'centos-rhel': {
      const pkg = arch === 'amd64' ? 'cloudflared-linux-x86_64.rpm' : 'cloudflared-linux-aarch64.rpm'
      return [
        '# 1. 安装 cloudflared (CentOS/RHEL)',
        `curl -fsSL ${CF_RELEASE}/${pkg} -o /tmp/cloudflared.rpm`,
        'sudo rpm -i /tmp/cloudflared.rpm',
      ]
    }
    case 'alpine':
    case 'generic': {
      const bin = arch === 'amd64' ? 'cloudflared-linux-amd64' : 'cloudflared-linux-arm64'
      const label = os === 'alpine' ? 'Alpine Linux' : '通用 Linux'
      return [
        `# 1. 安装 cloudflared (${label})`,
        `curl -fsSL ${CF_RELEASE}/${bin} -o /tmp/cloudflared`,
        'sudo install -m 755 /tmp/cloudflared /usr/local/bin/cloudflared',
      ]
    }
    case 'macos': {
      const darwin = arch === 'amd64' ? 'cloudflared-darwin-amd64.tgz' : 'cloudflared-darwin-arm64.tgz'
      return [
        '# 1. 安装 cloudflared (macOS)',
        '# 方式 A：Homebrew（推荐）',
        'brew install cloudflared',
        '# 方式 B：官方二进制（无 Homebrew 时使用）',
        `# curl -fsSL ${CF_RELEASE}/${darwin} -o /tmp/cloudflared.tgz`,
        '# tar -xzf /tmp/cloudflared.tgz -C /tmp',
        '# sudo install -m 755 /tmp/cloudflared /usr/local/bin/cloudflared',
      ]
    }
  }
}

function escapeShellSingleQuoted(value: string): string {
  return value.replace(/'/g, "'\\''")
}

export function canBuildTunnelInstallScript(
  arch: TunnelInstallArch | undefined,
  os: TunnelInstallOs | undefined,
  protocol: TunnelInstallProtocol | undefined,
  token: string,
): boolean {
  return !!(arch && os && protocol && token.trim())
}

export function buildTunnelInstallScript(opts: {
  arch: TunnelInstallArch
  os: TunnelInstallOs
  protocol: TunnelInstallProtocol
  token: string
  tunnelName?: string
}): string {
  const { arch, os, protocol, token } = opts
  const safeToken = escapeShellSingleQuoted(token.trim())
  const protocolFlag = protocol === 'http2' ? ' --protocol http2' : ''
  const protocolNote =
    protocol === 'http2'
      ? 'HTTP/2（TCP 443）'
      : 'QUIC（UDP 7844；若 UDP 不通会自动尝试 HTTP/2）'

  const lines: string[] = [
    ...installSteps(os, arch),
    '',
    `# 2. 运行 Tunnel — ${protocolNote}`,
    `cloudflared tunnel run --token '${safeToken}'${protocolFlag}`,
  ]

  if (os === 'macos') {
    lines.push(
      '',
      '# --- 可选：macOS 后台运行 ---',
      '# brew services start cloudflared',
      '# 或使用 launchd，见 Cloudflare 文档',
    )
  } else {
    lines.push(
      '',
      '# --- 可选：安装为 systemd 服务 ---',
      `# sudo cloudflared service install '${safeToken}'`,
      '# sudo systemctl enable cloudflared && sudo systemctl start cloudflared',
    )
    if (protocol === 'http2') {
      lines.push(
        '# HTTP/2 服务方式请在 /etc/systemd/system/cloudflared.service 的 [Service] 增加：',
        '# Environment="TUNNEL_TRANSPORT_PROTOCOL=http2"',
        '# sudo systemctl daemon-reload && sudo systemctl restart cloudflared',
      )
    }
  }

  lines.push(
    '',
    '# 3. 在 OCI Worker → 账户服务 → Tunnel →「路由」配置 Public Hostname（将自动创建 CNAME）',
  )

  return lines.join('\n')
}
