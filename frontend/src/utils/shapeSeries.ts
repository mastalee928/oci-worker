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

export const SHAPE_SERIES_OPTIONS: ShapePickOption[] = [
  { value: SHAPE_SERIES_ARM, label: SHAPE_SERIES_ARM },
  { value: SHAPE_SERIES_AMD, label: SHAPE_SERIES_AMD },
  { value: SHAPE_SERIES_INTEL, label: SHAPE_SERIES_INTEL },
  { value: SHAPE_SERIES_SPECIALTY, label: SHAPE_SERIES_SPECIALTY },
  { value: SHAPE_SERIES_BARE_METAL, label: SHAPE_SERIES_BARE_METAL },
  { value: SHAPE_SERIES_OTHER, label: SHAPE_SERIES_OTHER },
]

const KNOWN_SERIES = new Set(SHAPE_SERIES_OPTIONS.map((o) => o.value))

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

/** 任务短码：与下拉重复项，API 列表中会过滤对应 Flex/Micro */
const SHORT_CODES_BY_SERIES: Record<string, ShapePickOption[]> = {
  [SHAPE_SERIES_ARM]: [{ value: 'ARM', label: 'VM.Standard.A1.Flex' }],
  [SHAPE_SERIES_SPECIALTY]: [{ value: 'AMD', label: 'VM.Standard.E2.1.Micro' }],
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
): ShapePickOption[] {
  const norm = normalizeShapeSeries(series)
  const out: ShapePickOption[] = []
  const shorts = SHORT_CODES_BY_SERIES[norm] ?? []
  for (const sc of shorts) out.push({ ...sc })
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
): string {
  const opts = shapeOptionsForSeries(series, apiShapes)
  if (prev && opts.some((o) => o.value === prev)) return prev
  return opts[0]?.value ?? 'ARM'
}
