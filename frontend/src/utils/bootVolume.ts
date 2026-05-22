export const BOOT_VOLUME_VPUS_DEFAULT = 10
export const BOOT_VOLUME_VPUS_MIN = 10
export const BOOT_VOLUME_VPUS_MAX = 120
export const BOOT_VOLUME_VPUS_STEP = 10

/** 与后端 BootVolumeVpusUtil 一致：10～120，步进 10 */
export function snapBootVpusPerGb(n: number | null | undefined): number {
  if (n == null || Number.isNaN(n) || n <= 0) return BOOT_VOLUME_VPUS_DEFAULT
  let v = Math.round(n)
  if (v < BOOT_VOLUME_VPUS_MIN) v = BOOT_VOLUME_VPUS_MIN
  if (v > BOOT_VOLUME_VPUS_MAX) v = BOOT_VOLUME_VPUS_MAX
  const rem = v % BOOT_VOLUME_VPUS_STEP
  if (rem === 0) return v
  const down = v - rem
  return down < BOOT_VOLUME_VPUS_MIN ? BOOT_VOLUME_VPUS_MIN : down
}

export function formatTaskConfigDisk(diskGb: number, vpus?: number | null): string {
  const d = diskGb ?? 50
  const v = snapBootVpusPerGb(vpus)
  return `${d}GB(${v}VPUs)`
}
