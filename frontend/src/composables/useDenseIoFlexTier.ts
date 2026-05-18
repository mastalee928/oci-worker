import { computed } from 'vue'
import {
  denseIoFlexTierKey,
  formatDenseIoTierLabel,
  getDenseIoFlexTiers,
} from '../constants/ociBmShapeSpecs'

/** 开机表单：DenseIO Flex 档位下拉（与 createForm / editForm / quickTaskForm 共用） */
export function useDenseIoFlexTier(form: { architecture?: string; ocpus: number; memory: number }) {
  const tiers = computed(() => getDenseIoFlexTiers(form.architecture))

  const tierKey = computed({
    get() {
      const list = tiers.value
      if (!list?.length) return undefined
      const hit = list.find((t) => t.ocpus === form.ocpus && t.memory === form.memory)
      return denseIoFlexTierKey(hit ?? list[0])
    },
    set(key: string) {
      const list = tiers.value
      if (!list?.length) return
      const t = list.find((x) => denseIoFlexTierKey(x) === key)
      if (t) {
        form.ocpus = t.ocpus
        form.memory = t.memory
      }
    },
  })

  return { tiers, tierKey, formatDenseIoTierLabel, denseIoFlexTierKey }
}
