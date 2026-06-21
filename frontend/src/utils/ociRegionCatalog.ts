import { ref, shallowRef } from 'vue'
import { listOciRegionOptions } from '../api/system'
import { OCI_REGION_MAP, OCI_REGION_CODES } from '../constants/ociRegions'

/** 下拉选项 { value: regionId, label } */
export const ociRegionSelectOptions = shallowRef<{ value: string; label: string }[]>([])

/** regionId -> 中文名（无则回退 regionId） */
export const ociRegionZhById = ref<Record<string, string>>({ ...OCI_REGION_MAP })

let regionCatalogRequestSeq = 0

function bootstrapFromConstants() {
  const opts = OCI_REGION_CODES.map((v) => ({
    value: v,
    label: OCI_REGION_MAP[v] ? `${OCI_REGION_MAP[v]}（${v}）` : v,
  }))
  ociRegionSelectOptions.value = opts
  ociRegionZhById.value = { ...OCI_REGION_MAP }
}

bootstrapFromConstants()

/**
 * 加载区域下拉。
 * @param userId 租户配置 id：有则调 OCI Identity listRegionSubscriptions，仅返回已订阅区；无则返回 SDK 枚举全集（新增配置用）。
 */
export async function loadOciRegionCatalog(userId?: string): Promise<void> {
  const requestSeq = ++regionCatalogRequestSeq
  try {
    const res: any = await listOciRegionOptions(userId)
    if (requestSeq !== regionCatalogRequestSeq) return
    const rows: any[] = res.data || []
    if (!rows.length) {
      if (userId) {
        ociRegionSelectOptions.value = []
        ociRegionZhById.value = { ...OCI_REGION_MAP }
      } else {
        bootstrapFromConstants()
      }
      return
    }
    ociRegionSelectOptions.value = rows.map((r) => ({
      value: r.regionId,
      label: r.label || r.regionId,
    }))
    const m: Record<string, string> = { ...OCI_REGION_MAP }
    for (const r of rows) {
      const id = r.regionId as string
      if (id && r.labelZh) m[id] = r.labelZh
    }
    ociRegionZhById.value = m
  } catch {
    if (requestSeq !== regionCatalogRequestSeq) return
    bootstrapFromConstants()
  }
}

export function getOciRegionDisplayName(code: string): string {
  if (!code) return ''
  return ociRegionZhById.value[code] || OCI_REGION_MAP[code] || code
}

export function filterOciRegionSelectOption(input: string, option: any) {
  const kw = input.toLowerCase()
  return (
    String(option?.label ?? '')
      .toLowerCase()
      .includes(kw) || String(option?.value ?? '').toLowerCase().includes(kw)
  )
}
