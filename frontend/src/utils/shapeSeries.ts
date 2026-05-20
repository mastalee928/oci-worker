/** 与后端 ShapeSeriesUtil 控制台系列划分一致 */

export type ShapeListRow = {
  shape: string
  processorDescription?: string | null
  ocpus?: number
  memoryInGBs?: number
}

export type ShapePickOption = { value: string; label: string }

export const SHAPE_SERIES_ARM = 'ARM（Ampere）'
export const SHAPE_SERIES_AMD = 'AMD'
export const SHAPE_SERIES_INTEL = 'Intel'
export const SHAPE_SERIES_SPECIALTY = '专业和上一代'
export const SHAPE_SERIES_BARE_METAL = '裸金属机'
export const SHAPE_SERIES_OTHER = '其它'

/** 架构下拉顺序（与 OCI 控制台习惯一致） */
export const SHAPE_SERIES_ORDER: readonly string[] = [
  SHAPE_SERIES_ARM,
  SHAPE_SERIES_SPECIALTY,
  SHAPE_SERIES_AMD,
  SHAPE_SERIES_INTEL,
  SHAPE_SERIES_BARE_METAL,
  SHAPE_SERIES_OTHER,
]

const KNOWN_SERIES = new Set(SHAPE_SERIES_ORDER)

const FIXED_VM_SHAPE_SERIES: Record<string, string> = (() => {
  const m: Record<string, string> = {}
  const reg = (series: string, shapes: string[]) => {
    for (const shape of shapes) m[shape.toUpperCase()] = series
  }
  reg(SHAPE_SERIES_ARM, [
    'VM.Standard.A1.Flex',
    'VM.Standard.A2.Flex',
    'VM.Standard.A4.Flex',
  ])
  reg(SHAPE_SERIES_AMD, [
    'VM.Standard.E4.Flex',
    'VM.Standard.E5.Flex',
    'VM.Standard.E6.Flex',
    'VM.Standard.E6.Ax.Flex',
  ])
  reg(SHAPE_SERIES_INTEL, [
    'VM.Standard3.Flex',
    'VM.Optimized3.Flex',
    'VM.Standard4.Ax.Flex',
  ])
  reg(SHAPE_SERIES_SPECIALTY, [
    'VM.Standard.E2.1.Micro',
    'VM.Standard.E3.Flex',
    'VM.DenseIO.E5.Flex',
    'VM.DenseIO.E4.Flex',
    'VM.DenseIO2.8',
    'VM.DenseIO2.16',
    'VM.DenseIO2.24',
    'VM.GPU.A10.1',
    'VM.GPU.A10.2',
    'VM.GPU2.1',
    'VM.GPU3.1',
    'VM.GPU3.2',
    'VM.GPU3.4',
    'VM.Standard.B1.1',
    'VM.Standard.B1.2',
    'VM.Standard.B1.4',
    'VM.Standard.B1.8',
    'VM.Standard.B1.16',
    'VM.Standard.E2.1',
    'VM.Standard.E2.2',
    'VM.Standard.E2.4',
    'VM.Standard.E2.8',
    'VM.Standard1.1',
    'VM.Standard1.2',
    'VM.Standard1.4',
    'VM.Standard1.8',
    'VM.Standard1.16',
    'VM.Standard2.1',
    'VM.Standard2.2',
    'VM.Standard2.4',
    'VM.Standard2.8',
    'VM.Standard2.16',
    'VM.Standard2.24',
  ])
  return m
})()

/** 开机任务默认 ARM Shape（完整名，不再用短码 ARM 作选项 value） */
export const TASK_ARM_SHAPE = 'VM.Standard.A1.Flex'
const SHAPE_AMD_SHORT = 'VM.Standard.E2.1.Micro'
export const SHAPE_AMD_SHORT_LABEL = 'VM.Standard.E2.1.Micro (Always Free)'

/** 旧任务短码 ARM → 完整 Shape；其它原样 */
export function normalizeTaskArchitecture(architecture: string | null | undefined): string {
  if (!architecture?.trim()) return TASK_ARM_SHAPE
  const a = architecture.trim()
  if (a === 'ARM' || a.toUpperCase() === 'AMPERE') return TASK_ARM_SHAPE
  return a
}

export function isTaskArmArchitecture(architecture: string | null | undefined): boolean {
  const a = architecture?.trim()
  return a === 'ARM' || a === TASK_ARM_SHAPE
}

/** 任务列表/详情展示用（存库可为 ARM / AMD / 完整 Shape 名） */
export function formatTaskArchitectureLabel(architecture: string | null | undefined): string {
  if (!architecture?.trim()) return '—'
  const a = architecture.trim()
  if (a === 'AMD') return SHAPE_AMD_SHORT_LABEL
  if (a === 'ARM') return TASK_ARM_SHAPE
  return a
}

function shortCodesForSeries(series: string, allShapes: ShapeListRow[]): ShapePickOption[] {
  const names = new Set(allShapes.map((s) => s.shape))
  if (series === SHAPE_SERIES_ARM && names.has(TASK_ARM_SHAPE)) {
    return [{ value: TASK_ARM_SHAPE, label: TASK_ARM_SHAPE }]
  }
  if (series === SHAPE_SERIES_SPECIALTY && names.has(SHAPE_AMD_SHORT)) {
    return [{ value: 'AMD', label: SHAPE_AMD_SHORT_LABEL }]
  }
  return []
}

export function resolveShapeSeries(shapeOrArchitecture: string | null | undefined): string {
  if (!shapeOrArchitecture?.trim()) return SHAPE_SERIES_ARM
  const raw = shapeOrArchitecture.trim()
  const key = raw.toUpperCase()
  if (key.startsWith('BM.')) return SHAPE_SERIES_BARE_METAL
  const fixed = FIXED_VM_SHAPE_SERIES[key]
  if (fixed) return fixed
  if (raw === 'ARM' || key === 'AMPERE') return SHAPE_SERIES_ARM
  if (raw === 'Intel' || key === 'INTEL') return SHAPE_SERIES_INTEL
  if (raw === 'AMD') return SHAPE_SERIES_SPECIALTY
  return SHAPE_SERIES_OTHER
}

export function normalizeShapeSeries(series: string | null | undefined): string {
  if (!series?.trim()) return SHAPE_SERIES_ARM
  const s = series.trim()
  return KNOWN_SERIES.has(s) ? s : SHAPE_SERIES_OTHER
}

export function seriesFromArchitecture(
  architecture: string | null | undefined,
  apiShapes: ShapeListRow[],
): string {
  const s = resolveShapeSeries(architecture)
  if (s === SHAPE_SERIES_OTHER && apiShapes.length) {
    const hit = apiShapes.find((x) => x.shape === architecture)
    if (hit) return resolveShapeSeries(hit.shape)
  }
  return normalizeShapeSeries(s)
}

function formatShapeOption(row: ShapeListRow): ShapePickOption {
  const desc = row.processorDescription?.trim()
  return {
    value: row.shape,
    label: desc ? `${row.shape}（${desc}）` : row.shape,
  }
}

/** API 列表已排除与短码重复的 A1.Flex / E2.1.Micro */
export function filterApiShapesForPicker(rows: ShapeListRow[]): ShapeListRow[] {
  return rows.filter(
    (s) => s.shape !== 'VM.Standard.A1.Flex' && s.shape !== 'VM.Standard.E2.1.Micro',
  )
}

export function shapeOptionsForSeries(
  series: string,
  apiShapes: ShapeListRow[],
  allShapes?: ShapeListRow[],
): ShapePickOption[] {
  const norm = normalizeShapeSeries(series)
  const out: ShapePickOption[] = []
  const catalog = allShapes?.length ? allShapes : apiShapes
  for (const sc of shortCodesForSeries(norm, catalog)) out.push({ ...sc })
  for (const row of apiShapes) {
    if (resolveShapeSeries(row.shape) === norm) {
      out.push(formatShapeOption(row))
    }
  }
  if (norm === SHAPE_SERIES_OTHER) {
    for (const row of apiShapes) {
      if (resolveShapeSeries(row.shape) === SHAPE_SERIES_OTHER) {
        out.push(formatShapeOption(row))
      }
    }
  }
  return out
}

export function pickDefaultArchitectureForSeries(
  series: string,
  apiShapes: ShapeListRow[],
  prev?: string | null,
  allShapes?: ShapeListRow[],
): string {
  const opts = shapeOptionsForSeries(series, apiShapes, allShapes)
  const normPrev = prev ? normalizeTaskArchitecture(prev) : null
  if (normPrev && opts.some((o) => o.value === normPrev)) return normPrev
  if (prev && opts.some((o) => o.value === prev)) return prev
  return opts[0]?.value ?? TASK_ARM_SHAPE
}

/** 仅展示当前区域有 Shape 的架构；「其它」无项时不出现 */
export function seriesOptionsForPicker(allShapes: ShapeListRow[]): ShapePickOption[] {
  const filtered = filterApiShapesForPicker(allShapes)
  return SHAPE_SERIES_ORDER.filter(
    (series) => shapeOptionsForSeries(series, filtered, allShapes).length > 0,
  ).map((value) => ({ value, label: value }))
}
