/**
 * 裸金属 (BM.*) 固定规格 — 摘自 Oracle Compute Shapes 文档
 * https://docs.oracle.com/en-us/iaas/Content/Compute/References/computeshapes.htm
 * GPU 系列使用表中 CPU Memory (GB) 列。
 */
export const OCI_BM_SHAPE_SPECS: Record<string, { ocpus: number; memory: number }> = {
  // Standard (current)
  'BM.Standard3.64': { ocpus: 64, memory: 1024 },
  'BM.Standard4.Ax.120': { ocpus: 120, memory: 1152 },
  'BM.Standard.E4.128': { ocpus: 128, memory: 2048 },
  'BM.Standard.E5.192': { ocpus: 192, memory: 2304 },
  'BM.Standard.E6.256': { ocpus: 256, memory: 3072 },
  'BM.Standard.E6.Ax.192': { ocpus: 192, memory: 1536 },
  'BM.Standard.A1.160': { ocpus: 160, memory: 1024 },
  'BM.Standard.A4.48': { ocpus: 48, memory: 768 },
  'BM.Standard.A4.Ax.48': { ocpus: 48, memory: 768 },
  // Dense I/O (current)
  'BM.DenseIO.A4.Ax.72': { ocpus: 72, memory: 1152 },
  'BM.DenseIO.E4.128': { ocpus: 128, memory: 2048 },
  'BM.DenseIO.E5.128': { ocpus: 128, memory: 1536 },
  'BM.DenseIO.E5.1281': { ocpus: 128, memory: 1536 },
  'BM.DenseIO.E6.Ax.192': { ocpus: 192, memory: 2304 },
  'BM.DenseIO.E6.Ax.1921': { ocpus: 192, memory: 2304 },
  // GPU (CPU Memory GB)
  'BM.GPU2.2': { ocpus: 28, memory: 256 },
  'BM.GPU3.8': { ocpus: 52, memory: 768 },
  'BM.GPU4.8': { ocpus: 64, memory: 2048 },
  'BM.GPU.B4.8': { ocpus: 128, memory: 2048 },
  'BM.GPU.A10.4': { ocpus: 64, memory: 1024 },
  'BM.GPU.A100-v2.8': { ocpus: 128, memory: 2048 },
  'BM.GPU.MI300X.8': { ocpus: 112, memory: 2048 },
  'BM.GPU.MI355X.8': { ocpus: 128, memory: 3072 },
  'BM.GPU.L40S.4': { ocpus: 112, memory: 1024 },
  'BM.GPU.H100.8': { ocpus: 112, memory: 2048 },
  'BM.GPU.H200.8': { ocpus: 112, memory: 3072 },
  'BM.GPU.B200.8': { ocpus: 128, memory: 4096 },
  'BM.GPU.GB200.4': { ocpus: 144, memory: 960 },
  'BM.GPU.GB300.4': { ocpus: 144, memory: 960 },
  // HPC / Optimized
  'BM.Optimized3.36': { ocpus: 36, memory: 512 },
  'BM.HPC.E5.144': { ocpus: 144, memory: 768 },
  'BM.HPC.E5.1441': { ocpus: 144, memory: 768 },
  // Previous generation
  'BM.Standard1.36': { ocpus: 36, memory: 256 },
  'BM.Standard.B1.44': { ocpus: 44, memory: 512 },
  'BM.Standard2.52': { ocpus: 52, memory: 768 },
  'BM.Standard.E2.64': { ocpus: 64, memory: 512 },
  'BM.Standard.E3.128': { ocpus: 128, memory: 2048 },
  'BM.DenseIO1.36': { ocpus: 36, memory: 512 },
  'BM.DenseIO2.52': { ocpus: 52, memory: 768 },
  'BM.HPC2.36': { ocpus: 36, memory: 384 },
}

/** Flex Shape：控制台默认 OCPU/内存与最大值（用户提供截图） */
export type FlexShapeSpec = {
  ocpus: number
  memory: number
  maxOcpus: number
  maxMemoryGb: number
}

export const OCI_FLEX_SHAPE_SPECS: Record<string, FlexShapeSpec> = {
  'VM.Standard.E6.Flex': { ocpus: 1, memory: 11, maxOcpus: 126, maxMemoryGb: 1454 },
  'VM.Standard.E6.Ax.Flex': { ocpus: 1, memory: 7, maxOcpus: 94, maxMemoryGb: 712 },
  'VM.Standard.E5.Flex': { ocpus: 1, memory: 12, maxOcpus: 126, maxMemoryGb: 2098 },
  'VM.Standard.E4.Flex': { ocpus: 1, memory: 16, maxOcpus: 114, maxMemoryGb: 1760 },
  'VM.Standard3.Flex': { ocpus: 1, memory: 16, maxOcpus: 56, maxMemoryGb: 896 },
  'VM.Optimized3.Flex': { ocpus: 1, memory: 14, maxOcpus: 18, maxMemoryGb: 256 },
  'VM.Standard4.Ax.Flex': { ocpus: 1, memory: 9, maxOcpus: 39, maxMemoryGb: 360 },
  'VM.Standard.A1.Flex': { ocpus: 1, memory: 6, maxOcpus: 80, maxMemoryGb: 512 },
  'VM.Standard.A2.Flex': { ocpus: 1, memory: 6, maxOcpus: 78, maxMemoryGb: 946 },
  'VM.Standard.A4.Flex': { ocpus: 1, memory: 7, maxOcpus: 45, maxMemoryGb: 700 },
  'VM.Standard.E3.Flex': { ocpus: 1, memory: 16, maxOcpus: 114, maxMemoryGb: 1776 },
}

const ARM_TASK_FLEX_SHAPE = 'VM.Standard.A1.Flex'

/** 开机任务短码 ARM → A1.Flex 上限 */
export function getFlexShapeSpec(arch?: string | null): FlexShapeSpec | null {
  if (!arch) return null
  if (arch === 'ARM') return OCI_FLEX_SHAPE_SPECS[ARM_TASK_FLEX_SHAPE] ?? null
  return OCI_FLEX_SHAPE_SPECS[arch] ?? null
}

/** 部分 Flex 切换时预选 OCPU/内存（可编辑， unlike BM / 固定 VM 锁定） */
export const OCI_FLEX_SHAPE_DEFAULTS: Record<string, { ocpus: number; memory: number }> = Object.fromEntries(
  Object.entries(OCI_FLEX_SHAPE_SPECS).map(([k, v]) => [k, { ocpus: v.ocpus, memory: v.memory }]),
)

export type TaskShapeLimits = {
  minOcpus: number
  maxOcpus: number
  minMemory: number
  maxMemory: number
}

/** 开机任务 / 快捷开机：按 Shape 解析 OCPU、内存 min/max */
export function resolveTaskShapeLimits(
  arch?: string | null,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): TaskShapeLimits {
  if (!arch) return { minOcpus: 1, maxOcpus: 80, minMemory: 1, maxMemory: 512 }
  if (isFixedTaskShapeSpec(arch)) {
    const spec = resolveFixedTaskShapeSpec(arch, shapesFromApi)
    const o = spec?.ocpus ?? 1
    const m = spec?.memory ?? 1
    return { minOcpus: o, maxOcpus: o, minMemory: m, maxMemory: m }
  }
  const dense = getDenseIoFlexTiers(arch)
  if (dense?.length) {
    return {
      minOcpus: Math.min(...dense.map((t) => t.ocpus)),
      maxOcpus: Math.max(...dense.map((t) => t.ocpus)),
      minMemory: Math.min(...dense.map((t) => t.memory)),
      maxMemory: Math.max(...dense.map((t) => t.memory)),
    }
  }
  const flex = getFlexShapeSpec(arch)
  if (flex) {
    return { minOcpus: 1, maxOcpus: flex.maxOcpus, minMemory: 1, maxMemory: flex.maxMemoryGb }
  }
  return { minOcpus: 1, maxOcpus: 512, minMemory: 1, maxMemory: 4096 }
}

export function clampTaskShapeResources(
  form: { architecture?: string; ocpus: number; memory: number },
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): void {
  const lim = resolveTaskShapeLimits(form.architecture, shapesFromApi)
  if (form.ocpus < lim.minOcpus) form.ocpus = lim.minOcpus
  if (form.ocpus > lim.maxOcpus) form.ocpus = lim.maxOcpus
  if (form.memory < lim.minMemory) form.memory = lim.minMemory
  if (form.memory > lim.maxMemory) form.memory = lim.maxMemory
}

export function taskOcpuFieldLabel(
  arch?: string | null,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): string {
  const lim = resolveTaskShapeLimits(arch, shapesFromApi)
  return `OCPU（${lim.minOcpus}–${lim.maxOcpus}）`
}

export function taskMemoryFieldLabel(
  arch?: string | null,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): string {
  const lim = resolveTaskShapeLimits(arch, shapesFromApi)
  return `内存 GB（${lim.minMemory}–${lim.maxMemory}）`
}

/** InputNumber 直接键入时 :max 不一定即时生效，须在变更时钳制 */
export function applyTaskOcpusInput(
  form: { architecture?: string; ocpus: number; memory: number },
  value: number | null | undefined,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): void {
  form.ocpus = value ?? 1
  clampTaskShapeResources(form, shapesFromApi)
}

export function applyTaskMemoryInput(
  form: { architecture?: string; ocpus: number; memory: number },
  value: number | null | undefined,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): void {
  form.memory = value ?? 1
  clampTaskShapeResources(form, shapesFromApi)
}

/** 实例改 Shape：已知 Flex 以固定表上限为准，其余用 API */
export function resolveShapeEditFlexLimits(
  shapeName: string,
  apiMeta?: {
    ocpuMin?: number | null
    ocpuMax?: number | null
    memoryMinInGBs?: number | null
    memoryMaxInGBs?: number | null
  } | null,
): TaskShapeLimits {
  const flex = getFlexShapeSpec(shapeName)
  if (flex) {
    return {
      minOcpus: apiMeta?.ocpuMin ?? 1,
      maxOcpus: flex.maxOcpus,
      minMemory: apiMeta?.memoryMinInGBs ?? 1,
      maxMemory: flex.maxMemoryGb,
    }
  }
  return {
    minOcpus: apiMeta?.ocpuMin ?? 1,
    maxOcpus: apiMeta?.ocpuMax ?? 80,
    minMemory: apiMeta?.memoryMinInGBs ?? 1,
    maxMemory: apiMeta?.memoryMaxInGBs ?? 512,
  }
}

/** 固定规格 VM（非 Flex），OCPU/内存不可改 */
export const OCI_FIXED_VM_SHAPE_SPECS: Record<string, { ocpus: number; memory: number }> = {
  'VM.Standard.E2.1': { ocpus: 1, memory: 8 },
  'VM.Standard.E2.2': { ocpus: 2, memory: 16 },
  'VM.Standard.E2.4': { ocpus: 4, memory: 32 },
  'VM.Standard.E2.8': { ocpus: 8, memory: 64 },
  'VM.Standard2.1': { ocpus: 1, memory: 15 },
  'VM.Standard2.2': { ocpus: 2, memory: 30 },
  'VM.Standard2.4': { ocpus: 4, memory: 60 },
  'VM.Standard2.8': { ocpus: 8, memory: 120 },
  'VM.Standard2.16': { ocpus: 16, memory: 240 },
  'VM.Standard2.24': { ocpus: 24, memory: 320 },
  'VM.DenseIO2.8': { ocpus: 8, memory: 120 },
  'VM.DenseIO2.16': { ocpus: 16, memory: 240 },
  'VM.DenseIO2.24': { ocpus: 24, memory: 320 },
  'VM.GPU.A10.1': { ocpus: 15, memory: 240 },
  'VM.GPU.A10.2': { ocpus: 30, memory: 480 },
  'VM.GPU2.1': { ocpus: 12, memory: 72 },
  'VM.GPU3.1': { ocpus: 6, memory: 90 },
  'VM.GPU3.2': { ocpus: 12, memory: 180 },
  'VM.GPU3.4': { ocpus: 24, memory: 360 },
  'VM.Standard.B1.1': { ocpus: 1, memory: 12 },
  'VM.Standard.B1.2': { ocpus: 2, memory: 24 },
  'VM.Standard.B1.4': { ocpus: 4, memory: 48 },
  'VM.Standard.B1.8': { ocpus: 8, memory: 96 },
  'VM.Standard.B1.16': { ocpus: 16, memory: 192 },
  'VM.Standard1.1': { ocpus: 1, memory: 7 },
  'VM.Standard1.2': { ocpus: 2, memory: 14 },
  'VM.Standard1.4': { ocpus: 4, memory: 28 },
}

/** DenseIO Flex：按 OCI 控制台档位同步 OCPU/内存（本地 NVMe 随档位由 OCI 配置） */
export type DenseIoFlexTier = { ocpus: number; memory: number; nvmeLabel: string }

export const OCI_DENSEIO_FLEX_TIERS: Record<string, DenseIoFlexTier[]> = {
  'VM.DenseIO.E5.Flex': [
    { ocpus: 8, memory: 96, nvmeLabel: '1×6.8 TB NVMe · 8 Gbps' },
    { ocpus: 16, memory: 192, nvmeLabel: '2×13.6 TB NVMe · 16 Gbps' },
    { ocpus: 24, memory: 288, nvmeLabel: '3×20.4 TB NVMe · 24 Gbps' },
    { ocpus: 32, memory: 384, nvmeLabel: '4×27.2 TB NVMe · 32 Gbps' },
    { ocpus: 40, memory: 480, nvmeLabel: '5×34 TB NVMe · 40 Gbps' },
    { ocpus: 48, memory: 576, nvmeLabel: '6×40.8 TB NVMe · 48 Gbps' },
  ],
  'VM.DenseIO.E4.Flex': [
    { ocpus: 8, memory: 128, nvmeLabel: '1×6.8 TB NVMe · 8 Gbps' },
    { ocpus: 16, memory: 256, nvmeLabel: '2×13.6 TB NVMe · 16 Gbps' },
    { ocpus: 32, memory: 512, nvmeLabel: '4×27.2 TB NVMe · 32 Gbps' },
  ],
}

export function getDenseIoFlexTiers(arch?: string | null): DenseIoFlexTier[] | null {
  if (!arch) return null
  return OCI_DENSEIO_FLEX_TIERS[arch] ?? null
}

export function isDenseIoFlexTiered(arch?: string | null): boolean {
  return !!getDenseIoFlexTiers(arch)?.length
}

export function denseIoFlexTierKey(t: DenseIoFlexTier): string {
  return `${t.ocpus}-${t.memory}`
}

export function formatDenseIoTierLabel(t: DenseIoFlexTier): string {
  return `${t.ocpus} OCPU · ${t.memory} GB · ${t.nvmeLabel}`
}

export function validateDenseIoFlexTier(
  arch: string | undefined,
  ocpus: number,
  memory: number,
): string | null {
  const tiers = getDenseIoFlexTiers(arch)
  if (!tiers) return null
  if (tiers.some((t) => t.ocpus === ocpus && t.memory === memory)) return null
  return '请选择有效的 DenseIO 档位'
}

export function isBmArchitecture(arch?: string | null): boolean {
  return !!arch && arch.startsWith('BM.')
}

export function isFixedVmShapeSpec(arch?: string | null): boolean {
  return !!arch && arch in OCI_FIXED_VM_SHAPE_SPECS
}

/** BM 或固定规格 VM：OCPU/内存锁定 */
export function isFixedTaskShapeSpec(arch?: string | null): boolean {
  return isBmArchitecture(arch) || isFixedVmShapeSpec(arch)
}

export function resolveBmShapeSpec(
  arch: string,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): { ocpus: number; memory: number } | null {
  if (OCI_BM_SHAPE_SPECS[arch]) return OCI_BM_SHAPE_SPECS[arch]
  const fromApi = shapesFromApi?.find((s) => s.shape === arch)
  if (fromApi?.ocpus != null && fromApi?.memoryInGBs != null) {
    return { ocpus: fromApi.ocpus, memory: fromApi.memoryInGBs }
  }
  return null
}

/** 非 BM：随 Shape 切换默认内存 */
export function defaultMemoryGbForShape(architecture: string) {
  if (architecture === 'AMD') return 1
  if (architecture === 'ARM') return 6
  if (architecture && (architecture.includes('.Micro') || architecture.endsWith('Micro'))) return 1
  if (architecture && architecture.includes('Flex')) return 6
  return 6
}

export function resolveFixedTaskShapeSpec(
  arch: string,
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): { ocpus: number; memory: number } | null {
  if (isBmArchitecture(arch)) return resolveBmShapeSpec(arch, shapesFromApi)
  const fixed = OCI_FIXED_VM_SHAPE_SPECS[arch]
  if (fixed) return fixed
  return null
}

export function applyTaskShapeDefaults(
  form: { architecture?: string; ocpus: number; memory: number },
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): boolean {
  const arch = form.architecture
  if (!arch) return false
  const denseTiers = getDenseIoFlexTiers(arch)
  if (denseTiers?.length) {
    const hit = denseTiers.find((t) => t.ocpus === form.ocpus && t.memory === form.memory)
    const t = hit ?? denseTiers[0]
    form.ocpus = t.ocpus
    form.memory = t.memory
    clampTaskShapeResources(form, shapesFromApi)
    return false
  }
  if (isFixedTaskShapeSpec(arch)) {
    const spec = resolveFixedTaskShapeSpec(arch, shapesFromApi)
    if (spec) {
      form.ocpus = spec.ocpus
      form.memory = spec.memory
      return true
    }
    return false
  }
  const flex = getFlexShapeSpec(arch)
  if (flex) {
    form.ocpus = flex.ocpus
    form.memory = flex.memory
    clampTaskShapeResources(form, shapesFromApi)
    return false
  }
  // 离开 BM/固定 VM 后须恢复 OCPU；此前只改 memory，会残留大规格 OCPU
  form.ocpus = 1
  form.memory = defaultMemoryGbForShape(arch)
  clampTaskShapeResources(form, shapesFromApi)
  return false
}
