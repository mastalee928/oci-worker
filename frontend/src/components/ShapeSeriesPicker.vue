<template>
  <div>
    <a-row :gutter="12">
      <a-col :xs="24" :sm="10">
        <a-form-item label="架构" :style="{ marginBottom: isMobile ? '12px' : undefined }">
          <a-select
            v-model:value="seriesModel"
            :options="seriesOptions"
            placeholder="选择架构系列"
            @change="onSeriesChange"
          />
        </a-form-item>
      </a-col>
      <a-col :xs="24" :sm="14">
        <a-form-item label="Shape">
          <a-select
            v-model:value="architectureModel"
            placeholder="选择 Shape"
            show-search
            :loading="loading"
            :options="shapeOptions"
            :filter-option="filterShapeOption"
            :disabled="!shapeOptions.length"
            @change="onShapeChange"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <div v-if="apiShapes.length && hint" style="color: var(--text-sub); font-size: 12px; margin-top: -8px">
      {{ hint }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  SHAPE_SERIES_ARM,
  type ShapeListRow,
  filterApiShapesForPicker,
  normalizeShapeSeries,
  pickDefaultArchitectureForSeries,
  seriesFromArchitecture,
  seriesOptionsForPicker,
  shapeOptionsForSeries,
} from '../utils/shapeSeries'

const props = withDefaults(
  defineProps<{
    architecture?: string
    shapes?: ShapeListRow[]
    loading?: boolean
    hint?: string
    isMobile?: boolean
  }>(),
  {
    architecture: 'ARM',
    shapes: () => [],
    loading: false,
    hint: '',
    isMobile: false,
  },
)

const emit = defineEmits<{
  'update:architecture': [value: string]
  change: [value: string]
}>()

const rawShapes = computed(() => props.shapes ?? [])
const apiShapes = computed(() => filterApiShapesForPicker(rawShapes.value))

const seriesModel = ref(SHAPE_SERIES_ARM)

const seriesOptions = computed(() => seriesOptionsForPicker(props.shapes ?? []))

const architectureModel = computed({
  get: () => props.architecture || 'ARM',
  set: (v: string) => {
    emit('update:architecture', v)
    emit('change', v)
  },
})

const shapeOptions = computed(() =>
  shapeOptionsForSeries(seriesModel.value, apiShapes.value, rawShapes.value),
)

function filterShapeOption(input: string, option: { label?: string }) {
  return String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
}

function syncSeriesFromArchitecture() {
  seriesModel.value = seriesFromArchitecture(architectureModel.value, apiShapes.value)
}

function onSeriesChange(series: string) {
  const next = pickDefaultArchitectureForSeries(series, apiShapes.value, null, rawShapes.value)
  architectureModel.value = next
}

function onShapeChange() {
  syncSeriesFromArchitecture()
}

watch(
  () => props.architecture,
  () => syncSeriesFromArchitecture(),
  { immediate: true },
)

function ensureSeriesStillVisible() {
  const list = seriesOptions.value
  if (!list.length) return
  if (!list.some((o) => o.value === seriesModel.value)) {
    seriesModel.value = SHAPE_SERIES_ARM
    architectureModel.value = pickDefaultArchitectureForSeries(
      SHAPE_SERIES_ARM,
      apiShapes.value,
      'ARM',
      rawShapes.value,
    )
  }
}

watch(apiShapes, () => {
  ensureSeriesStillVisible()
  const opts = shapeOptions.value
  if (!opts.length) {
    architectureModel.value = pickDefaultArchitectureForSeries(
      seriesModel.value,
      apiShapes.value,
      architectureModel.value,
      rawShapes.value,
    )
    return
  }
  if (!opts.some((o) => o.value === architectureModel.value)) {
    architectureModel.value = pickDefaultArchitectureForSeries(
      seriesModel.value,
      apiShapes.value,
      null,
      rawShapes.value,
    )
  } else {
    syncSeriesFromArchitecture()
  }
})

watch(seriesOptions, () => ensureSeriesStillVisible())
</script>
