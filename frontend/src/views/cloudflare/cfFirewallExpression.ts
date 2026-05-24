/** Cloudflare Wirefilter 字段 / 运算符 → 表达式编译 */

export type FieldType = 'string' | 'number' | 'ip' | 'set' | 'bool'

export type OperatorId =
  | 'wildcard'
  | 'strict_wildcard'
  | 'not_wildcard'
  | 'not_strict_wildcard'
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
  { id: 'not_wildcard', label: '非通配符', wire: 'not wildcard' },
  { id: 'not_strict_wildcard', label: '非严格通配符', wire: 'not strict wildcard' },
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
      if (!value) return null
      return `(${f} wildcard ${quoteRaw(value)})`
    case 'strict_wildcard':
      if (!value) return null
      return `(${f} strict wildcard ${quoteRaw(value)})`
    case 'not_wildcard':
      if (!value) return null
      return `(not (${f} wildcard ${quoteRaw(value)}))`
    case 'not_strict_wildcard':
      if (!value) return null
      return `(not (${f} strict wildcard ${quoteRaw(value)}))`
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

function humanizeClauseForm(clause: VisualClauseForm): string {
  const field = FIREWALL_FIELDS.find(f => f.id === clause.fieldId)
  if (!field) return ''
  if (field.type === 'bool') {
    return clause.boolValue ? 'SSL/HTTPS 已启用' : 'SSL/HTTPS 未启用'
  }
  const value = clause.value.trim()
  if (!value) return ''
  const opDef = operatorsForField(field).find(o => o.id === clause.operator)
  const opLabel = opDef?.label ?? '等于'
  if (clause.operator === 'starts_with') return `${field.label} 开头为 ${value}`
  if (clause.operator === 'not_starts_with') return `${field.label} 开头不是 ${value}`
  if (clause.operator === 'ends_with') return `${field.label} 结尾为 ${value}`
  if (clause.operator === 'not_ends_with') return `${field.label} 结尾不是 ${value}`
  if (clause.operator === 'contains') return `${field.label} 包含 ${value}`
  if (clause.operator === 'not_contains') return `${field.label} 不包含 ${value}`
  if (clause.operator === 'wildcard') return `${field.label} 通配符 ${value}`
  return `${field.label} ${opLabel} ${value}`
}

/** 格式化 Or 分支：单条件直接展示，And 组内用 and 连接 */
function humanizeOrBranch(ast: ExprAst): string {
  if (ast.kind === 'and') {
    return ast.children.map(humanizeAstNode).filter(Boolean).join(' and ')
  }
  return humanizeAstNode(ast)
}

function humanizeAstNode(ast: ExprAst): string {
  if (ast.kind === 'clause') {
    return humanizeClauseForm(ast.clause)
  }
  if (ast.kind === 'not') {
    const inner = humanizeAstNode(ast.child)
    return inner ? `not (${inner})` : 'not (…)'
  }
  if (ast.kind === 'and') {
    return ast.children.map(humanizeAstNode).filter(Boolean).join(' and ')
  }
  if (ast.kind === 'or') {
    return ast.children.map(humanizeOrBranch).filter(Boolean).join(', ')
  }
  return ''
}

function humanizeExpressionFallback(expr: string): string {
  let s = expr.trim()
  const rules: Array<[RegExp, string | ((...args: string[]) => string)]> = [
    [/ip\.src\.asnum\s+eq\s+(\d+)/gi, (_, v) => `ASN 等于 ${v}`],
    [/http\.host\s+eq\s+"([^"]+)"/gi, (_, v) => `主机名 等于 ${v}`],
    [/http\.request\.uri\.path\s+contains\s+"([^"]+)"/gi, (_, v) => `URI 路径 包含 ${v}`],
    [/starts_with\(http\.request\.uri\.path,\s*"([^"]+)"\)/gi, (_, v) => `URI 路径 开头为 ${v}`],
    [/ip\.src\.country\s+eq\s+"([^"]+)"/gi, (_, v) => `国家/地区 等于 ${v}`],
    [/ip\.src\s+eq\s+([^\s)]+)/gi, (_, v) => `IP 源地址 等于 ${v}`],
    [/\(ssl\)/gi, 'SSL/HTTPS 已启用'],
    [/\(not ssl\)/gi, 'SSL/HTTPS 未启用'],
  ]
  for (const [re, rep] of rules) {
    s = s.replace(re, rep as (substring: string, ...args: string[]) => string)
  }
  return s.replace(/\s+/g, ' ').trim()
}

/** 将 Wirefilter 表达式转为接近 CF 控制台的可读匹配条件（Or 组用逗号，And 组用 and） */
export function humanizeExpression(expr?: string): string {
  if (!expr?.trim()) return '—'
  const ast = parseFirewallAst(expr.trim())
  if (ast) {
    const normalized = normalizeAstForVisual(ast)
    const humanized = humanizeAstNode(normalized)
    if (humanized) return humanized
  }
  const fallback = humanizeExpressionFallback(expr)
  return fallback || expr.trim()
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
  | { type: 'not_group'; innerJoin: FirewallJoin; clauses: VisualClauseForm[] }
  | {
      type: 'branch'
      parts: Array<
        | { type: 'clause'; clause: VisualClauseForm }
        | { type: 'not_group'; innerJoin: FirewallJoin; clauses: VisualClauseForm[] }
      >
    }

export interface VisualRuleForm {
  join: FirewallJoin
  items: VisualRuleItem[]
}

/** Wirefilter 表达式 AST（与 CF 控制台逻辑结构一致） */
type ExprAst =
  | { kind: 'clause'; clause: VisualClauseForm }
  | { kind: 'and'; children: ExprAst[] }
  | { kind: 'or'; children: ExprAst[] }
  | { kind: 'not'; child: ExprAst }

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

function normalizeExpression(raw: string): string {
  return raw.replace(/\s+/g, ' ').trim()
}

/** 提取 `not (...)` 括号内内容；非此形态返回 null */
function extractNotParenContent(raw: string): string | null {
  const s = stripBalancedOuterParens(raw.trim())
  const m = /^not\s+\(/i.exec(s)
  if (!m) return null
  let depth = 0
  let inStr = false
  let escape = false
  const start = m.index! + m[0].length
  for (let i = start; i < s.length; i++) {
    const ch = s[i]
    if (escape) {
      escape = false
      continue
    }
    if (ch === '\\' && inStr) {
      escape = true
      continue
    }
    if (ch === '"') {
      inStr = !inStr
      continue
    }
    if (!inStr) {
      if (ch === '(') depth++
      else if (ch === ')') {
        if (depth === 0) {
          if (i !== s.length - 1) return null
          return s.slice(start, i).trim()
        }
        depth--
      }
    }
  }
  return null
}

const NEGATE_OPERATOR: Partial<Record<OperatorId, OperatorId>> = {
  wildcard: 'not_wildcard',
  not_wildcard: 'wildcard',
  strict_wildcard: 'not_strict_wildcard',
  not_strict_wildcard: 'strict_wildcard',
  eq: 'ne',
  ne: 'eq',
  contains: 'not_contains',
  not_contains: 'contains',
  matches: 'not_matches',
  not_matches: 'matches',
  starts_with: 'not_starts_with',
  not_starts_with: 'starts_with',
  ends_with: 'not_ends_with',
  not_ends_with: 'ends_with',
  in: 'not_in',
  not_in: 'in',
  gt: 'le',
  lt: 'ge',
  ge: 'lt',
  le: 'gt',
}

function negateClause(clause: VisualClauseForm): VisualClauseForm | null {
  const field = FIREWALL_FIELDS.find(f => f.id === clause.fieldId)
  if (!field) return null
  if (field.type === 'bool') {
    return {
      ...clause,
      boolValue: !clause.boolValue,
      value: clause.boolValue ? 'false' : 'true',
    }
  }
  const negated = NEGATE_OPERATOR[clause.operator]
  if (!negated) return null
  return { ...clause, operator: negated }
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
    { re: new RegExp(`^not\\s+\\(${FIELD}\\s+wildcard\\s+${RAW}\\)$`, 'i'), apply: (f, v) => base(f, 'not_wildcard', unescapeQuoted(v)) },
    { re: new RegExp(`^not\\s+\\(${FIELD}\\s+strict\\s+wildcard\\s+${RAW}\\)$`, 'i'), apply: (f, v) => base(f, 'not_strict_wildcard', unescapeQuoted(v)) },
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

function parseFirewallAst(raw: string): ExprAst | null {
  return parseOrExpr(normalizeExpression(raw))
}

function parseOrExpr(s: string): ExprAst | null {
  const body = stripBalancedOuterParens(s.trim())
  const parts = splitTopLevel(body, 'or')
  if (parts.length > 1) {
    const children: ExprAst[] = []
    for (const part of parts) {
      const child = parseAndExpr(part.trim())
      if (!child) return null
      children.push(child)
    }
    return { kind: 'or', children }
  }
  return parseAndExpr(body)
}

function parseAndExpr(s: string): ExprAst | null {
  const body = stripBalancedOuterParens(s.trim())
  const parts = splitTopLevel(body, 'and')
  if (parts.length > 1) {
    const children: ExprAst[] = []
    for (const part of parts) {
      const child = parseUnaryExpr(part.trim())
      if (!child) return null
      children.push(child)
    }
    return { kind: 'and', children }
  }
  return parseUnaryExpr(body)
}

function parseUnaryExpr(s: string): ExprAst | null {
  const trimmed = stripBalancedOuterParens(s.trim())
  const notInner = extractNotParenContent(trimmed)
  if (notInner !== null) {
    const child = parseOrExpr(notInner)
    if (!child) return null
    if (child.kind === 'not') {
      return simplifyDoubleNegation(child.child)
    }
    return { kind: 'not', child }
  }
  return parsePrimary(trimmed)
}

function simplifyDoubleNegation(ast: ExprAst): ExprAst {
  if (ast.kind === 'not') return simplifyDoubleNegation(ast.child)
  return ast
}

function parsePrimary(s: string): ExprAst | null {
  const body = stripBalancedOuterParens(s.trim())
  const clause = parseSingleFirewallClause(body)
  if (clause) return { kind: 'clause', clause }
  if (splitTopLevel(body, 'or').length > 1) return parseOrExpr(body)
  if (splitTopLevel(body, 'and').length > 1) return parseAndExpr(body)
  return null
}

/** 将 AND 内的 OR 因子分配为顶层 OR（与 CF 可视化结构一致） */
function distributeAndWithOr(ast: ExprAst): ExprAst {
  if (ast.kind === 'not') {
    return { kind: 'not', child: distributeAndWithOr(ast.child) }
  }
  if (ast.kind === 'or') {
    return { kind: 'or', children: ast.children.map(distributeAndWithOr) }
  }
  if (ast.kind !== 'and') return ast

  const orIdx = ast.children.findIndex(c => c.kind === 'or')
  if (orIdx < 0) {
    return { kind: 'and', children: ast.children.map(distributeAndWithOr) }
  }

  const prefix = ast.children.slice(0, orIdx).map(distributeAndWithOr)
  const orNode = ast.children[orIdx] as Extract<ExprAst, { kind: 'or' }>
  const suffix = ast.children.slice(orIdx + 1).map(distributeAndWithOr)
  const branches: ExprAst[] = orNode.children.map(branch => ({
    kind: 'and',
    children: [...prefix, branch, ...suffix],
  }))
  return distributeAndWithOr({ kind: 'or', children: branches })
}

function normalizeAstForVisual(ast: ExprAst): ExprAst {
  return distributeAndWithOr(ast)
}

function collectNotGroupClauses(node: ExprAst): VisualClauseForm[] | null {
  if (node.kind === 'clause') return [node.clause]
  if (node.kind === 'or' || node.kind === 'and') {
    const clauses: VisualClauseForm[] = []
    for (const child of node.children) {
      if (child.kind !== 'clause') return null
      clauses.push(child.clause)
    }
    return clauses
  }
  return null
}

function notAstToItem(node: Extract<ExprAst, { kind: 'not' }>): VisualRuleItem | null {
  const { child } = node
  if (child.kind === 'clause') {
    return { type: 'not_group', innerJoin: 'and', clauses: [child.clause] }
  }
  if (child.kind === 'or' || child.kind === 'and') {
    const clauses = collectNotGroupClauses(child)
    if (!clauses?.length) return null
    return { type: 'not_group', innerJoin: child.kind, clauses }
  }
  return null
}

function andAstChildToItem(node: ExprAst): VisualRuleItem | null {
  if (node.kind === 'clause') return { type: 'clause', clause: node.clause }
  if (node.kind === 'not') return notAstToItem(node)
  if (node.kind === 'and') {
    const clauses: VisualClauseForm[] = []
    for (const child of node.children) {
      if (child.kind !== 'clause') return null
      clauses.push(child.clause)
    }
    return andClausesToItem(clauses)
  }
  return null
}

function andClausesToItem(clauses: VisualClauseForm[]): VisualRuleItem {
  if (clauses.length === 1) return { type: 'clause', clause: clauses[0] }
  return { type: 'group', join: 'and', clauses }
}

function andBranchToOrItem(node: ExprAst): VisualRuleItem | null {
  if (node.kind === 'and') {
    const parts: Array<
      | { type: 'clause'; clause: VisualClauseForm }
      | { type: 'not_group'; innerJoin: FirewallJoin; clauses: VisualClauseForm[] }
    > = []
    for (const child of node.children) {
      if (child.kind === 'clause') {
        parts.push({ type: 'clause', clause: child.clause })
      } else if (child.kind === 'not') {
        const item = notAstToItem(child)
        if (!item || item.type !== 'not_group') return null
        parts.push(item)
      } else {
        return null
      }
    }
    if (!parts.length) return null
    if (parts.length === 1) return parts[0]
    if (parts.every(p => p.type === 'clause')) {
      return { type: 'group', join: 'and', clauses: parts.map(p => p.clause) }
    }
    return { type: 'branch', parts }
  }
  return andAstChildToItem(node)
}

function astToVisualForm(ast: ExprAst): VisualRuleForm | null {
  if (ast.kind === 'or') {
    const items: VisualRuleItem[] = []
    for (const child of ast.children) {
      const item = andBranchToOrItem(child)
      if (!item) return null
      items.push(item)
    }
    if (!items.length) return null
    if (items.length === 1) {
      const only = items[0]
      if (only.type === 'group') {
        return { join: 'and', items: only.clauses.map(c => ({ type: 'clause', clause: c })) }
      }
      return { join: 'and', items: [only] }
    }
    return { join: 'or', items }
  }

  if (ast.kind === 'and') {
    const items: VisualRuleItem[] = []
    for (const child of ast.children) {
      const item = andAstChildToItem(child)
      if (!item) return null
      items.push(item)
    }
    if (!items.length) return null
    return { join: 'and', items }
  }

  if (ast.kind === 'not') {
    const item = notAstToItem(ast)
    return item ? { join: 'and', items: [item] } : null
  }

  if (ast.kind === 'clause') {
    return { join: 'and', items: [{ type: 'clause', clause: ast.clause }] }
  }

  return null
}

/** 解析含 AND/OR/NOT 的表达式为可视化表单 */
export function parseFirewallVisualForm(raw: string): VisualRuleForm | null {
  if (!raw?.trim()) return null
  const ast = parseFirewallAst(raw)
  if (!ast) return null
  const normalized = normalizeAstForVisual(ast)
  return astToVisualForm(normalized)
}

function compileClauseInner(clause: VisualClauseForm): string | null {
  const field = FIREWALL_FIELDS.find(f => f.id === clause.fieldId)
  if (!field) return null
  const raw = field.type === 'bool' ? (clause.boolValue ? 'true' : 'false') : clause.value
  const compiled = compileFirewallClause(field, clause.operator, raw)
  if (!compiled) return null
  return stripBalancedOuterParens(compiled)
}

function compileClauseList(clauses: VisualClauseForm[], join: FirewallJoin = 'and'): string | null {
  if (!clauses.length) return null
  const parts: string[] = []
  for (const clause of clauses) {
    const inner = compileClauseInner(clause)
    if (!inner) return null
    parts.push(inner)
  }
  if (parts.length === 1) return `(${parts[0]})`
  return `(${parts.join(` ${join} `)})`
}

function compileNotGroup(innerJoin: FirewallJoin, clauses: VisualClauseForm[]): string | null {
  if (!clauses.length) return null
  const parts: string[] = []
  for (const clause of clauses) {
    const inner = compileClauseInner(clause)
    if (!inner) return null
    parts.push(inner)
  }
  const body = parts.length === 1 ? parts[0] : parts.join(` ${innerJoin} `)
  return `(not (${body}))`
}

function compileVisualItem(item: VisualRuleItem): string | null {
  if (item.type === 'clause') return compileClauseList([item.clause])
  if (item.type === 'group') return compileClauseList(item.clauses, 'and')
  if (item.type === 'not_group') return compileNotGroup(item.innerJoin, item.clauses)
  const parts: string[] = []
  for (const part of item.parts) {
    const compiled = compileVisualItem(part)
    if (!compiled) return null
    parts.push(compiled)
  }
  if (!parts.length) return null
  if (parts.length === 1) return parts[0]
  return `(${parts.map(p => stripBalancedOuterParens(p)).join(' and ')})`
}

/** 将可视化表单（含 OR 下的 AND 组、NOT 组）编译为 Wirefilter 表达式 */
export function compileFirewallVisualForm(form: VisualRuleForm): string | null {
  if (!form.items.length) return null
  const parts: string[] = []
  for (const item of form.items) {
    const compiled = compileVisualItem(item)
    if (!compiled) continue
    parts.push(compiled)
  }
  if (!parts.length) return null
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

/** CF 控制台风格：逐行 And/Or 链（首行无连接符） */
export interface FlatClauseRow {
  clause: VisualClauseForm
  prevJoin?: FirewallJoin
}

function tryFlattenAst(ast: ExprAst): FlatClauseRow[] | null {
  if (ast.kind === 'not') return null
  if (ast.kind === 'clause') return [{ clause: ast.clause }]

  if (ast.kind === 'and' || ast.kind === 'or') {
    const join = ast.kind
    if (ast.children.every(c => c.kind === 'clause')) {
      return ast.children.map((c, i) => ({
        clause: (c as Extract<ExprAst, { kind: 'clause' }>).clause,
        prevJoin: i === 0 ? undefined : join,
      }))
    }
    if (ast.children.length === 2) {
      const [left, right] = ast.children
      if (right.kind === 'clause') {
        const leftRows = tryFlattenAst(left)
        if (leftRows) {
          return [...leftRows, { clause: right.clause, prevJoin: join }]
        }
      }
    }
  }
  return null
}

/** CF 控制台：And 组 + Or 分隔（与可视化构建器一致） */
export function visualFormToAndGroups(form: VisualRuleForm): VisualClauseForm[][] | null {
  if (form.join === 'or') {
    const groups: VisualClauseForm[][] = []
    for (const item of form.items) {
      const rows = visualItemToAndRows(item)
      if (!rows?.length) return null
      groups.push(rows)
    }
    return groups.length ? groups : null
  }
  if (form.join === 'and') {
    const rows: VisualClauseForm[] = []
    for (const item of form.items) {
      if (item.type === 'clause') rows.push(item.clause)
      else if (item.type === 'group' && item.join === 'and') rows.push(...item.clauses)
      else return null
    }
    return rows.length ? [rows] : null
  }
  return null
}

function visualItemToAndRows(item: VisualRuleItem): VisualClauseForm[] | null {
  if (item.type === 'clause') return [item.clause]
  if (item.type === 'group' && item.join === 'and') return item.clauses.length ? item.clauses : null
  return null
}

export function andGroupsToVisualForm(groups: VisualClauseForm[][]): VisualRuleForm {
  if (groups.length === 1) {
    return {
      join: 'and',
      items: groups[0].map(clause => ({ type: 'clause', clause })),
    }
  }
  return {
    join: 'or',
    items: groups.map(rows => {
      if (rows.length === 1) return { type: 'clause', clause: rows[0] }
      return { type: 'group', join: 'and', clauses: rows }
    }),
  }
}

/** 解析为 CF And 组 + Or 分隔结构；含 NOT/复杂嵌套时返回 null */
export function parseFirewallAndGroups(raw: string): VisualClauseForm[][] | null {
  const form = parseFirewallVisualForm(raw)
  if (!form) return null
  return visualFormToAndGroups(form)
}

/** 编译 CF And 组 + Or 分隔结构 */
export function compileFirewallAndGroups(groups: VisualClauseForm[][]): string | null {
  if (!groups.length) return null
  const filtered = groups.filter(g => g.length > 0)
  if (!filtered.length) return null
  return compileFirewallVisualForm(andGroupsToVisualForm(filtered))
}

/** @deprecated 使用 {@link parseFirewallAndGroups} */
export function parseFirewallFlatRows(raw: string): FlatClauseRow[] | null {
  const groups = parseFirewallAndGroups(raw)
  if (!groups) return null
  const rows: FlatClauseRow[] = []
  for (let gi = 0; gi < groups.length; gi++) {
    for (let ri = 0; ri < groups[gi].length; ri++) {
      rows.push({
        clause: groups[gi][ri],
        prevJoin: rows.length === 0 ? undefined : (ri === 0 ? 'or' : 'and'),
      })
    }
  }
  return rows.length ? rows : null
}

/** @deprecated 使用 {@link compileFirewallAndGroups} */
export function compileFirewallFlatRows(rows: FlatClauseRow[]): string | null {
  if (!rows.length) return null
  const groups: VisualClauseForm[][] = []
  let current: VisualClauseForm[] = []
  for (let i = 0; i < rows.length; i++) {
    if (i > 0 && rows[i].prevJoin === 'or') {
      if (current.length) groups.push(current)
      current = []
    }
    current.push(rows[i].clause)
  }
  if (current.length) groups.push(current)
  return compileFirewallAndGroups(groups)
}

function formatExprAstPretty(ast: ExprAst, depth = 0): string {
  const pad = (n: number) => '  '.repeat(n)

  if (ast.kind === 'clause') {
    return compileClauseInner(ast.clause) ?? ''
  }

  if (ast.kind === 'not') {
    const child = ast.child
    if (child.kind === 'or' || child.kind === 'and') {
      const inner = formatExprAstPretty(child, depth + 1)
      return `not (\n${inner}\n${pad(depth)})`
    }
    return `not (${formatExprAstPretty(child, depth)})`
  }

  if (ast.kind === 'or' || ast.kind === 'and') {
    const join = ast.kind
    const lines: string[] = []
    for (let i = 0; i < ast.children.length; i++) {
      const part = formatExprAstPretty(ast.children[i], depth + (join === 'or' ? 1 : 1))
      if (i === 0) {
        lines.push(part)
      } else if (join === 'or') {
        lines.push(`${pad(depth)}or\n${part}`)
      } else {
        lines.push(`${pad(depth + 1)}${join} ${part.trim()}`)
      }
    }
    return lines.join('\n')
  }

  return ''
}

/** 格式化为 CF 控制台风格的多行表达式（展示用） */
export function prettyPrintFirewallExpression(raw: string): string {
  const trimmed = raw?.trim()
  if (!trimmed) return ''
  const ast = parseFirewallAst(trimmed)
  if (!ast) return trimmed
  const body = formatExprAstPretty(ast, 0)
  if (ast.kind === 'and' || ast.kind === 'or') {
    return `(${body}\n)`
  }
  return body
}

/** 提交前压缩空白为单行 Wirefilter */
export function normalizeFirewallExpression(raw: string): string {
  return normalizeExpression(raw)
}
