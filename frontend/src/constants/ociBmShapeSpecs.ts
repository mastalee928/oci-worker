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
  'BM.DenseIO.E4.128': { ocpus: 128, memory: 2048 },
  'BM.DenseIO.E5.128': { ocpus: 128, memory: 1536 },
  'BM.DenseIO.E5.1281': { ocpus: 128, memory: 1536 },
  'BM.DenseIO.E6.Ax.192': { ocpus: 192, memory: 2304 },
  'BM.DenseIO.E6.Ax.1921': { ocpus: 192, memory: 2304 },
  // GPU (CPU Memory GB)
  'BM.GPU2.2': { ocpus: 28, memory: 192 },
  'BM.GPU3.8': { ocpus: 52, memory: 768 },
  'BM.GPU4.8': { ocpus: 64, memory: 2048 },
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

/** 部分 Flex 切换时预选 OCPU/内存（可编辑， unlike BM 锁定） */
export const OCI_FLEX_SHAPE_DEFAULTS: Record<string, { ocpus: number; memory: number }> = {
  'VM.Standard3.Flex': { ocpus: 1, memory: 16 },
  'VM.Standard.E5.Flex': { ocpus: 1, memory: 12 },
}

export function isBmArchitecture(arch?: string | null): boolean {
  return !!arch && arch.startsWith('BM.')
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

export function applyTaskShapeDefaults(
  form: { architecture?: string; ocpus: number; memory: number },
  shapesFromApi?: Array<{ shape: string; ocpus?: number; memoryInGBs?: number }>,
): boolean {
  const arch = form.architecture
  if (!arch) return false
  if (isBmArchitecture(arch)) {
    const spec = resolveBmShapeSpec(arch, shapesFromApi)
    if (spec) {
      form.ocpus = spec.ocpus
      form.memory = spec.memory
      return true
    }
    return false
  }
  const flexDefault = OCI_FLEX_SHAPE_DEFAULTS[arch]
  if (flexDefault) {
    form.ocpus = flexDefault.ocpus
    form.memory = flexDefault.memory
    return false
  }
  form.memory = defaultMemoryGbForShape(arch)
  return false
}
