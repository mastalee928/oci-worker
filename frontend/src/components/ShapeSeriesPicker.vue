<template>
  <div>
    <a-row :gutter="12">
      <a-col :xs="24" :sm="10">
        <a-form-item label="架构" :style="{ marginBottom: isMobile ? '12px' : undefined }">
          <a-select
            v-model:value="seriesModel"
            :options="seriesOptions"
            placeholder="选择架构系列"
            :get-popup-container="getPopupContainer"
            @change="onSeriesChange"
          />
        </a-form-item>
      </a-col>
      <a-col :xs="24" :sm="14">
        <a-form-item label="Shape">
          <a-select
            v-model:value="architectureModel"
            placeholder="选择 Shape"
            :show-search="false"
            :loading="loading"
            :options="shapeOptions"
            :disabled="!shapeOptions.length"
            :get-popup-container="getPopupContainer"
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
  TASK_ARM_SHAPE,
  type ShapeListRow,
  filterApiShapesForPicker,
  normalizeTaskArchitecture,
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
    getPopupContainer?: (triggerNode?: HTMLElement) => HTMLElement
  }>(),
  {
    architecture: TASK_ARM_SHAPE,
    shapes: () => [],
    loading: false,
    hint: '',
    isMobile: false,
    getPopupContainer: undefined,
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
  get: () => normalizeTaskArchitecture(props.architecture),
  set: (v: string) => {
    const next = normalizeTaskArchitecture(v)
    emit('update:architecture', next)
    emit('change', next)
  },
})

const shapeOptions = computed(() =>
  shapeOptionsForSeries(seriesModel.value, apiShapes.value, rawShapes.value),
)

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
  (arch) => {
    const norm = normalizeTaskArchitecture(arch)
    if (arch?.trim() && norm !== arch.trim()) {
      emit('update:architecture', norm)
    }
    syncSeriesFromArchitecture()
  },
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
      TASK_ARM_SHAPE,
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
