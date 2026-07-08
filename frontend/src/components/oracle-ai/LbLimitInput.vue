<template>
  <a-input-number
    :value="normalizedValue"
    :min="min"
    :max="max"
    :precision="0"
    :controls="false"
    :placeholder="placeholder"
    style="width: 100%"
    @update:value="handleUpdate"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value?: number | null
  min?: number
  max?: number
  placeholder?: string
}>(), {
  value: null,
  min: 1,
  max: undefined,
  placeholder: '',
})

const emit = defineEmits<{
  (e: 'update:value', value: number | null): void
}>()

const normalizedValue = computed(() => normalizeLimitValue(props.value))

function handleUpdate(value: string | number | null | undefined) {
  emit('update:value', normalizeLimitValue(value))
}

function normalizeLimitValue(value: unknown) {
  if (value === null || value === undefined || value === '') return null
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) return null
  return Math.trunc(numeric)
}
</script>
