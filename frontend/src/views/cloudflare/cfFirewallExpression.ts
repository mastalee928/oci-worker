/** Cloudflare Wirefilter 字段 / 运算符 → 表达式编译 */

export type FieldType = 'string' | 'number' | 'ip' | 'set' | 'bool'

export type OperatorId =
  | 'wildcard'
  | 'strict_wildcard'
  | 'eq'
  | 'ne'
  | 'contains'
  | 'not_contains'
  | 'matches'
  | 'not_matches'
  | 'starts_with'
  | 'not_starts_with'
  | 'ends_with'
  | 'not_ends_with'
  | 'gt'
  | 'lt'
  | 'ge'
  | 'le'
  | 'in'
  | 'not_in'

export interface OperatorDef {
  id: OperatorId
  label: string
  /** Wirefilter 关键字或函数名 */
  wire: string
}

/** 字符串类字段可用（与 CF 控制台一致） */
export const STRING_OPERATORS: OperatorDef[] = [
  { id: 'wildcard', label: '通配符', wire: 'wildcard' },
  { id: 'strict_wildcard', label: '严格通配符', wire: 'strict wildcard' },
  { id: 'eq', label: '等于', wire: 'eq' },
  { id: 'ne', label: '不等于', wire: 'ne' },
  { id: 'contains', label: '包含', wire: 'contains' },
  { id: 'not_contains', label: '不包含', wire: 'not contains' },
  { id: 'matches', label: '与正则表达式匹配', wire: 'matches' },
  { id: 'not_matches', label: '与正则表达式不匹配', wire: 'not matches' },
  { id: 'starts_with', label: '开头为', wire: 'starts_with' },
  { id: 'not_starts_with', label: '开头不是', wire: 'not starts_with' },
  { id: 'ends_with', label: '结尾为', wire: 'ends_with' },
  { id: 'not_ends_with', label: '结尾不是', wire: 'not ends_with' },
]

/** 数字类（ASN 等） */
export const NUMERIC_OPERATORS: OperatorDef[] = [
  { id: 'eq', label: '等于', wire: 'eq' },
  { id: 'ne', label: '不等于', wire: 'ne' },
  { id: 'gt', label: '大于', wire: 'gt' },
  { id: 'lt', label: '小于', wire: 'lt' },
  { id: 'ge', label: '大于或等于', wire: 'ge' },
  { id: 'le', label: '小于或等于', wire: 'le' },
  { id: 'in', label: '包含以下各项', wire: 'in' },
  { id: 'not_in', label: '不包含以下各项', wire: 'not in' },
]

/** 集合类（国家、洲、请求方法等） */
export const SET_OPERATORS: OperatorDef[] = [
  { id: 'eq', label: '等于', wire: 'eq' },
  { id: 'ne', label: '不等于', wire: 'ne' },
  { id: 'in', label: '包含以下各项', wire: 'in' },
  { id: 'not_in', label: '不包含以下各项', wire: 'not in' },
]

/** IP 源地址 */
export const IP_OPERATORS: OperatorDef[] = [
  { id: 'eq', label: '等于', wire: 'eq' },
  { id: 'ne', label: '不等于', wire: 'ne' },
  { id: 'in', label: '在列表中', wire: 'in' },
  { id: 'not_in', label: '不在列表中', wire: 'not in' },
]

export interface FieldDef {
  id: string
  label: string
  expr: string
  type: FieldType
  placeholder?: string
  valueHint?: string
}

export const FIREWALL_FIELDS: FieldDef[] = [
  { id: 'asn', label: 'ASN', expr: 'ip.src.asnum', type: 'number', placeholder: '13335' },
  { id: 'cookie', label: 'Cookie', expr: 'http.cookie', type: 'string', placeholder: 'session_id=abc' },
  { id: 'country', label: '国家/地区', expr: 'ip.src.country', type: 'set', placeholder: 'CN', valueHint: 'ISO 3166-1 两位码，多项用逗号分隔' },
  { id: 'continent', label: '洲', expr: 'ip.src.continent', type: 'set', placeholder: 'AS', valueHint: '如 AS、EU、NA' },
  { id: 'host', label: '主机名', expr: 'http.host', type: 'string', placeholder: 'example.com' },
  { id: 'ip', label: 'IP 源地址', expr: 'ip.src', type: 'ip', placeholder: '203.0.113.1', valueHint: '多项用逗号或空格分隔' },
  { id: 'method', label: '请求方法', expr: 'http.request.method', type: 'set', placeholder: 'POST', valueHint: 'GET、POST、PUT…' },
  { id: 'ssl', label: 'SSL/HTTPS', expr: 'ssl', type: 'bool' },
  { id: 'http_version', label: 'HTTP 版本', expr: 'http.request.version', type: 'set', placeholder: 'HTTP/2' },
  { id: 'user_agent', label: '用户代理', expr: 'http.user_agent', type: 'string', placeholder: 'Mozilla/*' },
  { id: 'referer', label: '引用方', expr: 'http.referer', type: 'string', placeholder: 'https://google.com' },
  { id: 'x_forwarded_for', label: 'X-Forwarded-For', expr: 'http.x_forwarded_for', type: 'string', placeholder: '203.0.113.1' },
  { id: 'uri_full', label: 'URI 完整', expr: 'http.request.uri', type: 'string', placeholder: '/path?q=1' },
  { id: 'uri', label: 'URI', expr: 'http.request.uri', type: 'string', placeholder: '/content/*?page=1234' },
  { id: 'uri_path', label: 'URI 路径', expr: 'http.request.uri.path', type: 'string', placeholder: '/content/*' },
  { id: 'uri_query', label: 'URI 查询字符串', expr: 'http.request.uri.query', type: 'string', placeholder: 'page=1234*' },
]

export function operatorsForField(field: FieldDef | undefined): OperatorDef[] {
  if (!field) return STRING_OPERATORS
  switch (field.type) {
    case 'number': return NUMERIC_OPERATORS
    case 'set': return SET_OPERATORS
    case 'ip': return IP_OPERATORS
    case 'bool': return [{ id: 'eq', label: '等于', wire: 'eq' }]
    default: return STRING_OPERATORS
  }
}

function escapeStr(s: string) {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function quoteStr(s: string) {
  return `"${escapeStr(s)}"`
}

function quoteRaw(s: string) {
  return `r"${escapeStr(s)}"`
}

/** 解析逗号/空格/换行分隔的多值 */
export function parseMultiValues(raw: string): string[] {
  return raw
    .split(/[\s,，]+/)
    .map(v => v.trim())
    .filter(Boolean)
}

function formatSetValues(values: string[], quoted: boolean): string {
  const parts = values.map(v => (quoted ? quoteStr(v) : v))
  return `{ ${parts.join(' ')} }`
}

/**
 * 编译单条条件为 Wirefilter 子表达式（含外层括号）
 */
export function compileFirewallClause(
  field: FieldDef,
  operatorId: OperatorId,
  rawValue: string,
): string | null {
  if (field.type === 'bool') {
    const on = rawValue === 'true' || rawValue === '1'
    return on ? '(ssl)' : '(not ssl)'
  }

  const op = operatorsForField(field).find(o => o.id === operatorId)
  if (!op) return null

  const value = rawValue.trim()
  const f = field.expr

  switch (operatorId) {
    case 'wildcard':
      if (!value) return `(${f} wildcard ${quoteRaw('')})`
      return `(${f} wildcard ${quoteRaw(value)})`
    case 'strict_wildcard':
      if (!value) return `(${f} strict wildcard ${quoteRaw('')})`
      return `(${f} strict wildcard ${quoteRaw(value)})`
    case 'eq':
      if (field.type === 'number') {
        if (!value) return null
        return `(${f} eq ${value})`
      }
      if (field.type === 'ip') {
        if (!value) return null
        return `(${f} eq ${value})`
      }
      if (!value) return null
      return `(${f} eq ${quoteStr(value)})`
    case 'ne':
      if (field.type === 'number' || field.type === 'ip') {
        if (!value) return null
        return `(${f} ne ${value})`
      }
      if (!value) return null
      return `(${f} ne ${quoteStr(value)})`
    case 'contains':
      if (!value) return null
      return `(${f} contains ${quoteStr(value)})`
    case 'not_contains':
      if (!value) return null
      return `(not ${f} contains ${quoteStr(value)})`
    case 'matches':
      if (!value) return null
      return `(${f} matches ${quoteStr(value)})`
    case 'not_matches':
      if (!value) return null
      return `(not ${f} matches ${quoteStr(value)})`
    case 'starts_with':
      if (!value) return null
      return `(starts_with(${f}, ${quoteStr(value)}))`
    case 'not_starts_with':
      if (!value) return null
      return `(not starts_with(${f}, ${quoteStr(value)}))`
    case 'ends_with':
      if (!value) return null
      return `(ends_with(${f}, ${quoteStr(value)}))`
    case 'not_ends_with':
      if (!value) return null
      return `(not ends_with(${f}, ${quoteStr(value)}))`
    case 'gt':
    case 'lt':
    case 'ge':
    case 'le':
      if (!value) return null
      return `(${f} ${operatorId} ${value})`
    case 'in': {
      const items = parseMultiValues(value)
      if (items.length === 0) return null
      const quoted = field.type === 'set' || field.type === 'string'
      return `(${f} in ${formatSetValues(items, quoted && field.type === 'set')})`
    }
    case 'not_in': {
      const items = parseMultiValues(value)
      if (items.length === 0) return null
      const quoted = field.type === 'set'
      return `(not ${f} in ${formatSetValues(items, quoted)})`
    }
    default:
      return null
  }
}

export function defaultOperatorForField(field: FieldDef): OperatorId {
  switch (field.type) {
    case 'number': return 'eq'
    case 'set': return 'eq'
    case 'ip': return 'eq'
    case 'bool': return 'eq'
    default: return 'wildcard'
  }
}

const ACTION_LABELS: Record<string, string> = {
  block: '阻止',
  challenge: '质询',
  js_challenge: 'JS 质询',
  managed_challenge: '托管质询',
  allow: '允许',
  log: '记录',
  bypass: '绕过',
}

export function firewallActionLabel(action?: string) {
  if (!action) return '—'
  return ACTION_LABELS[action.toLowerCase()] || action
}

/** 将 Wirefilter 表达式转为接近 CF 控制台的可读匹配条件 */
export function humanizeExpression(expr?: string): string {
  if (!expr?.trim()) return '—'
  let s = expr.trim().replace(/^\(+|\)+$/g, '').trim()
  const rules: Array<[RegExp, string | ((...args: string[]) => string)]> = [
    [/http\.host\s+eq\s+"([^"]+)"/gi, (_, v) => `主机名 等于 ${v}`],
    [/http\.host\s+ne\s+"([^"]+)"/gi, (_, v) => `主机名 不等于 ${v}`],
    [/http\.request\.uri\.path\s+contains\s+"([^"]+)"/gi, (_, v) => `URI 路径 包含 ${v}`],
    [/not\s+http\.request\.uri\.path\s+contains\s+"([^"]+)"/gi, (_, v) => `URI 路径 不包含 ${v}`],
    [/http\.request\.uri\.path\s+eq\s+"([^"]+)"/gi, (_, v) => `URI 路径 等于 ${v}`],
    [/starts_with\(http\.request\.uri\.path,\s*"([^"]+)"\)/gi, (_, v) => `URI 路径 开头为 ${v}`],
    [/not\s+starts_with\(http\.request\.uri\.path,\s*"([^"]+)"\)/gi, (_, v) => `URI 路径 开头不是 ${v}`],
    [/ends_with\(http\.request\.uri\.path,\s*"([^"]+)"\)/gi, (_, v) => `URI 路径 结尾为 ${v}`],
    [/ip\.src\.country\s+eq\s+"([^"]+)"/gi, (_, v) => `国家/地区 等于 ${v}`],
    [/ip\.src\.country\s+in\s+\{([^}]+)\}/gi, (_, v) => `国家/地区 包含 ${v.replace(/"/g, '').trim()}`],
    [/ip\.src\s+eq\s+([^\s)]+)/gi, (_, v) => `IP 源地址 等于 ${v}`],
    [/http\.request\.method\s+eq\s+"([^"]+)"/gi, (_, v) => `请求方法 等于 ${v}`],
    [/\(ssl\)/gi, 'SSL/HTTPS 已启用'],
    [/\(not ssl\)/gi, 'SSL/HTTPS 未启用'],
    [/\band\b/gi, '且'],
    [/\bor\b/gi, '或'],
  ]
  for (const [re, rep] of rules) {
    s = s.replace(re, rep as (substring: string, ...args: string[]) => string)
  }
  return s
}

export function ruleDisplayName(description?: string, expression?: string) {
  if (description?.trim()) return description.trim()
  if (expression?.trim()) {
    const h = humanizeExpression(expression)
    return h.length > 48 ? `${h.slice(0, 48)}…` : h
  }
  return '未命名规则'
}

export interface VisualClauseForm {
  fieldId: string
  operator: OperatorId
  value: string
  boolValue: boolean
}

const FIELDS_BY_EXPR_LEN = [...FIREWALL_FIELDS].sort((a, b) => b.expr.length - a.expr.length)

/** CF 控制台/历史规则中的字段别名 → 本面板 canonical expr */
const FIELD_EXPR_ALIASES: Record<string, string> = {
  'ip.geoip.asnum': 'ip.src.asnum',
}

function findFieldByExpr(exprKey: string): FieldDef | undefined {
  const canonical = FIELD_EXPR_ALIASES[exprKey] ?? exprKey
  return FIELDS_BY_EXPR_LEN.find(f => f.expr === canonical)
}

function unescapeQuoted(s: string): string {
  return s.replace(/\\"/g, '"').replace(/\\\\/g, '\\')
}

function stripBalancedOuterParens(s: string): string {
  let cur = s.trim()
  while (cur.startsWith('(') && cur.endsWith(')')) {
    let depth = 0
    let canStrip = true
    for (let i = 0; i < cur.length - 1; i++) {
      if (cur[i] === '(') depth++
      else if (cur[i] === ')') depth--
      if (depth === 0 && i > 0) {
        canStrip = false
        break
      }
    }
    if (!canStrip) break
    cur = cur.slice(1, -1).trim()
  }
  return cur
}

/** 是否含顶层 and/or */
export function isCompoundFirewallExpression(raw: string): boolean {
  const s = stripBalancedOuterParens(raw.trim())
  return /\s+and\s+|\s+or\s+/i.test(s)
}

export type FirewallJoin = 'and' | 'or'

export type VisualRuleItem =
  | { type: 'clause'; clause: VisualClauseForm }
  | { type: 'group'; join: 'and'; clauses: VisualClauseForm[] }

export interface VisualRuleForm {
  join: FirewallJoin
  items: VisualRuleItem[]
}

function splitTopLevel(raw: string, join: FirewallJoin): string[] {
  const s = stripBalancedOuterParens(raw.trim())
  const keyword = join === 'and' ? ' and ' : ' or '
  const parts: string[] = []
  let depth = 0
  let inStr = false
  let escape = false
  let buf = ''

  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (escape) {
      buf += ch
      escape = false
      continue
    }
    if (ch === '\\' && inStr) {
      buf += ch
      escape = true
      continue
    }
    if (ch === '"') {
      inStr = !inStr
      buf += ch
      continue
    }
    if (!inStr) {
      if (ch === '(') depth++
      else if (ch === ')') depth--
      else if (depth === 0 && s.slice(i, i + keyword.length).toLowerCase() === keyword) {
        if (buf.trim()) parts.push(buf.trim())
        buf = ''
        i += keyword.length - 1
        continue
      }
    }
    buf += ch
  }
  if (buf.trim()) parts.push(buf.trim())
  return parts
}

function detectTopLevelJoin(raw: string): FirewallJoin | null {
  const s = stripBalancedOuterParens(raw.trim())
  const andParts = splitTopLevel(s, 'and')
  const orParts = splitTopLevel(s, 'or')
  const hasAnd = andParts.length > 1
  const hasOr = orParts.length > 1
  if (hasAnd && hasOr) return null
  if (hasAnd) return 'and'
  if (hasOr) return 'or'
  return 'and'
}

/** 解析单条子表达式为可视化字段 */
export function parseSingleFirewallClause(raw: string): VisualClauseForm | null {
  if (!raw?.trim()) return null

  const s = stripBalancedOuterParens(raw.trim())
  const STR = '"((?:[^"\\\\]|\\\\.)*)"'
  const RAW = 'r"((?:[^"\\\\]|\\\\.)*)"'
  const FIELD = '([\\w.]+)'

  const base = (field: FieldDef, operator: OperatorId, value: string): VisualClauseForm => ({
    fieldId: field.id,
    operator,
    value,
    boolValue: field.type === 'bool' ? value === 'true' : true,
  })

  if (/^not\s+ssl$/i.test(s)) {
    const field = findFieldByExpr('ssl')
    return field ? { fieldId: field.id, operator: 'eq', value: 'false', boolValue: false } : null
  }
  if (/^ssl$/i.test(s)) {
    const field = findFieldByExpr('ssl')
    return field ? { fieldId: field.id, operator: 'eq', value: 'true', boolValue: true } : null
  }

  type Matcher = { re: RegExp; apply: (field: FieldDef, ...caps: string[]) => VisualClauseForm | null }
  const matchers: Matcher[] = [
    { re: new RegExp(`^not\\s+starts_with\\(${FIELD},\\s*${STR}\\)$`, 'i'), apply: (f, v) => base(f, 'not_starts_with', unescapeQuoted(v)) },
    { re: new RegExp(`^starts_with\\(${FIELD},\\s*${STR}\\)$`, 'i'), apply: (f, v) => base(f, 'starts_with', unescapeQuoted(v)) },
    { re: new RegExp(`^not\\s+ends_with\\(${FIELD},\\s*${STR}\\)$`, 'i'), apply: (f, v) => base(f, 'not_ends_with', unescapeQuoted(v)) },
    { re: new RegExp(`^ends_with\\(${FIELD},\\s*${STR}\\)$`, 'i'), apply: (f, v) => base(f, 'ends_with', unescapeQuoted(v)) },
    { re: new RegExp(`^not\\s+${FIELD}\\s+contains\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'not_contains', unescapeQuoted(v)) },
    { re: new RegExp(`^not\\s+${FIELD}\\s+matches\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'not_matches', unescapeQuoted(v)) },
    { re: new RegExp(`^not\\s+${FIELD}\\s+in\\s+\\{([^}]*)\\}$`, 'i'), apply: (f, v) => base(f, 'not_in', v.replace(/"/g, '').trim()) },
    { re: new RegExp(`^${FIELD}\\s+wildcard\\s+${RAW}$`, 'i'), apply: (f, v) => base(f, 'wildcard', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+strict\\s+wildcard\\s+${RAW}$`, 'i'), apply: (f, v) => base(f, 'strict_wildcard', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+contains\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'contains', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+matches\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'matches', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+eq\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'eq', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+ne\\s+${STR}$`, 'i'), apply: (f, v) => base(f, 'ne', unescapeQuoted(v)) },
    { re: new RegExp(`^${FIELD}\\s+eq\\s+(\\S+)$`, 'i'), apply: (f, v) => base(f, 'eq', v) },
    { re: new RegExp(`^${FIELD}\\s+ne\\s+(\\S+)$`, 'i'), apply: (f, v) => base(f, 'ne', v) },
    { re: new RegExp(`^${FIELD}\\s+(gt|lt|ge|le)\\s+(\\S+)$`, 'i'), apply: (f, op, v) => base(f, op as OperatorId, v) },
    { re: new RegExp(`^${FIELD}\\s+in\\s+\\{([^}]*)\\}$`, 'i'), apply: (f, v) => base(f, 'in', v.replace(/"/g, '').trim()) },
  ]

  for (const { re, apply } of matchers) {
    const m = s.match(re)
    if (!m) continue
    const field = findFieldByExpr(m[1])
    if (!field) continue
    return apply(field, ...m.slice(2))
  }
  return null
}

/** @deprecated 使用 parseFirewallVisualForm */
export function parseFirewallExpression(raw: string): VisualClauseForm | null {
  return parseSingleFirewallClause(raw)
}

/** 解析含 AND/OR 的表达式为可视化表单；同层混合 and+or 或无法识别时返回 null */
export function parseFirewallVisualForm(raw: string): VisualRuleForm | null {
  if (!raw?.trim()) return null
  const join = detectTopLevelJoin(raw)
  if (!join) return null
  const segments = splitTopLevel(raw, join)
  const items: VisualRuleItem[] = []
  for (const seg of segments) {
    const innerJoin = detectTopLevelJoin(seg)
    if (join === 'or' && innerJoin === 'and') {
      const innerSegs = splitTopLevel(seg, 'and')
      const innerClauses: VisualClauseForm[] = []
      for (const inner of innerSegs) {
        const c = parseSingleFirewallClause(inner)
        if (!c) return null
        innerClauses.push(c)
      }
      if (innerClauses.length === 0) return null
      if (innerClauses.length === 1) {
        items.push({ type: 'clause', clause: innerClauses[0] })
      } else {
        items.push({ type: 'group', join: 'and', clauses: innerClauses })
      }
      continue
    }
    if (join === 'and' && innerJoin === 'or') return null
    const clause = parseSingleFirewallClause(seg)
    if (!clause) return null
    items.push({ type: 'clause', clause })
  }
  if (items.length === 0) return null
  return { join, items }
}

function compileClauseList(clauses: VisualClauseForm[]): string | null {
  if (!clauses.length) return null
  const parts: string[] = []
  for (const clause of clauses) {
    const field = FIREWALL_FIELDS.find(f => f.id === clause.fieldId)
    if (!field) return null
    const raw = field.type === 'bool' ? (clause.boolValue ? 'true' : 'false') : clause.value
    const compiled = compileFirewallClause(field, clause.operator, raw)
    if (!compiled) return null
    parts.push(compiled)
  }
  if (parts.length === 1) return parts[0]
  return `(${parts.join(' and ')})`
}

/** 将可视化表单（含 OR 下的 AND 组）编译为 Wirefilter 表达式 */
export function compileFirewallVisualForm(form: VisualRuleForm): string | null {
  if (!form.items.length) return null
  const parts: string[] = []
  for (const item of form.items) {
    const compiled =
      item.type === 'clause'
        ? compileClauseList([item.clause])
        : compileClauseList(item.clauses)
    if (!compiled) return null
    parts.push(compiled)
  }
  if (parts.length === 1) return parts[0]
  const sep = ` ${form.join} `
  return `(${parts.join(sep)})`
}

/** 将可视化多条条件编译为 Wirefilter 表达式 */
export function compileFirewallExpression(join: FirewallJoin, clauses: VisualClauseForm[]): string | null {
  return compileFirewallVisualForm({
    join,
    items: clauses.map(clause => ({ type: 'clause', clause })),
  })
}
