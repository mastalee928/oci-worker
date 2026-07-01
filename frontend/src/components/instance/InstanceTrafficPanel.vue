<template>
  <div class="traffic-panel">
    <div class="traffic-toolbar">
      <div class="traffic-range-tools">
        <a-segmented v-model:value="trafficRangeMode" :options="trafficWindowOptions" @change="onTrafficWindowChange" />
        <a-range-picker
          v-model:value="trafficDateRange"
          :class="['traffic-date-range', { 'traffic-date-range-active': trafficUseCustomRange }]"
          format="YYYY-MM-DD"
          :placeholder="['开始日期', '结束日期']"
          :disabled-date="disabledTrafficDate"
          :input-read-only="true"
          allow-clear
          @change="onTrafficDateRangeChange"
        />
      </div>
      <a-button @click="loadTraffic" :loading="trafficLoading">
        <template #icon><ReloadOutlined /></template>刷新
      </a-button>
    </div>
    <a-empty v-if="!trafficData && !trafficLoading" description="点击刷新加载流量数据" />
    <a-spin v-else :spinning="trafficLoading">
      <template v-if="trafficData">
        <div class="traffic-summary-grid">
          <div class="traffic-summary-card inbound">
            <span>入站</span>
            <strong>{{ formatBytes(trafficData.inbound) }}</strong>
          </div>
          <div class="traffic-summary-card outbound">
            <span>出站</span>
            <strong>{{ formatBytes(trafficData.outbound) }}</strong>
          </div>
          <div class="traffic-summary-card total">
            <span>总量</span>
            <strong>{{ formatBytes(trafficData.total) }}</strong>
          </div>
        </div>
        <div class="traffic-chart-card">
          <div class="traffic-chart-head">
            <span>趋势</span>
            <span>{{ trafficRangeLabel }} · 每 {{ trafficData.interval || '—' }}</span>
          </div>
          <div class="traffic-chart-canvas">
            <div v-if="trafficChart.hasData" class="traffic-chart-plot">
              <div class="traffic-y-scale">
                <span>{{ formatBytes(trafficChart.max) }}</span>
                <span>0 B</span>
              </div>
              <div class="traffic-plot-wrap">
                <div class="traffic-svg-wrap" @mouseleave="hideTrafficTooltip">
                  <svg class="traffic-chart-svg" viewBox="0 0 640 220" preserveAspectRatio="none" role="img">
                    <line v-for="y in trafficChartGrid" :key="y" x1="0" x2="640" :y1="y" :y2="y" class="traffic-grid-line" />
                    <polyline
                      v-if="trafficVisibleSeries.inbound"
                      :points="trafficChart.inbound"
                      :class="['traffic-line', 'traffic-line-inbound', { 'traffic-line-dim': trafficHoverPoint?.series === 'outbound' }]"
                    />
                    <polyline
                      v-if="trafficVisibleSeries.outbound"
                      :points="trafficChart.outbound"
                      :class="['traffic-line', 'traffic-line-outbound', { 'traffic-line-dim': trafficHoverPoint?.series === 'inbound' }]"
                    />
                    <g v-for="point in trafficChart.points" :key="'in-' + point.timestamp">
                      <circle
                        v-if="trafficVisibleSeries.inbound && trafficChart.showPoints"
                        :cx="point.x"
                        :cy="point.inboundY"
                        r="4"
                        class="traffic-point traffic-point-inbound"
                        @mouseenter="showTrafficTooltip(point, 'inbound', 'small')"
                      />
                      <circle
                        v-if="trafficVisibleSeries.outbound && trafficChart.showPoints"
                        :cx="point.x"
                        :cy="point.outboundY"
                        r="4"
                        class="traffic-point traffic-point-outbound"
                        @mouseenter="showTrafficTooltip(point, 'outbound', 'small')"
                      />
                      <circle
                        v-if="trafficVisibleSeries.inbound"
                        :cx="point.x"
                        :cy="point.inboundY"
                        r="9"
                        class="traffic-hit-point"
                        @mouseenter="showTrafficTooltip(point, 'inbound', 'small')"
                      />
                      <circle
                        v-if="trafficVisibleSeries.outbound"
                        :cx="point.x"
                        :cy="point.outboundY"
                        r="9"
                        class="traffic-hit-point"
                        @mouseenter="showTrafficTooltip(point, 'outbound', 'small')"
                      />
                    </g>
                    <g v-if="trafficHoverPoint?.scope === 'small'">
                      <circle
                        v-if="trafficVisibleSeries.inbound"
                        :cx="trafficHoverPoint.point.x"
                        :cy="trafficHoverPoint.point.inboundY"
                        r="5"
                        class="traffic-active-point traffic-point-inbound"
                      />
                      <circle
                        v-if="trafficVisibleSeries.outbound"
                        :cx="trafficHoverPoint.point.x"
                        :cy="trafficHoverPoint.point.outboundY"
                        r="5"
                        class="traffic-active-point traffic-point-outbound"
                      />
                    </g>
                  </svg>
                  <div
                    v-if="trafficHoverPoint?.scope === 'small'"
                    class="traffic-hover-card"
                    :class="`traffic-hover-card-${trafficHoverPoint.placement}`"
                    :style="{ left: `${trafficHoverPoint.left}%`, top: `${trafficHoverPoint.top}%` }"
                  >
                    <div class="traffic-hover-title">{{ trafficHoverPoint.point.label }}</div>
                    <div class="traffic-hover-row"><span>入站</span><strong>{{ formatBytes(trafficHoverPoint.point.inbound) }}</strong></div>
                    <div class="traffic-hover-row"><span>出站</span><strong>{{ formatBytes(trafficHoverPoint.point.outbound) }}</strong></div>
                    <div class="traffic-hover-row total"><span>总量</span><strong>{{ formatBytes(trafficHoverPoint.point.total) }}</strong></div>
                  </div>
                </div>
                <div class="traffic-x-scale">
                  <span>{{ trafficStartLabel }}</span>
                  <span class="traffic-x-axis-label">时间</span>
                  <span>{{ trafficEndLabel }}</span>
                </div>
              </div>
            </div>
            <a-empty v-else description="暂无趋势数据" />
          </div>
          <div class="traffic-legend">
            <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.inbound }]" @click="toggleTrafficSeries('inbound')"><i class="traffic-dot inbound"></i>入站</span>
            <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.outbound }]" @click="toggleTrafficSeries('outbound')"><i class="traffic-dot outbound"></i>出站</span>
            <span>峰值 {{ formatBytes(trafficChart.max) }}</span>
            <a-button size="small" class="traffic-expand-btn" @click="openTrafficChartModal">
              <template #icon><FullscreenOutlined /></template>{{ isMobile ? '' : '大图' }}
            </a-button>
          </div>
        </div>
        <a-collapse v-if="trafficVnics.length > 1" class="traffic-vnic-collapse">
          <a-collapse-panel key="vnics" :header="'网卡明细 · ' + trafficVnics.length">
            <a-table
              v-if="!isMobile"
              :columns="trafficVnicColumns"
              :data-source="trafficVnics"
              :pagination="false"
              row-key="vnicId"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'vnic'">
                  <div class="traffic-vnic-name">{{ record.displayName || shortOcId(record.vnicId) }}</div>
                  <div class="traffic-vnic-id">{{ shortOcId(record.vnicId) }}</div>
                </template>
                <template v-if="column.key === 'inbound'">{{ formatBytes(record.inbound) }}</template>
                <template v-if="column.key === 'outbound'">{{ formatBytes(record.outbound) }}</template>
                <template v-if="column.key === 'total'">{{ formatBytes(record.total) }}</template>
              </template>
            </a-table>
            <div v-else class="traffic-vnic-mobile-list">
              <div v-for="vnic in trafficVnics" :key="vnic.vnicId" class="mobile-card">
                <div class="mobile-card-header">
                  <span class="mobile-card-title">{{ vnic.displayName || shortOcId(vnic.vnicId) }}</span>
                </div>
                <div class="mobile-card-body">
                  <div class="mobile-card-row"><span class="label">私有 IP</span><span class="value">{{ vnic.privateIp || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">公网 IP</span><span class="value">{{ vnic.publicIp || '—' }}</span></div>
                  <div class="mobile-card-row"><span class="label">入站</span><span class="value">{{ formatBytes(vnic.inbound) }}</span></div>
                  <div class="mobile-card-row"><span class="label">出站</span><span class="value">{{ formatBytes(vnic.outbound) }}</span></div>
                  <div class="mobile-card-row"><span class="label">总量</span><span class="value">{{ formatBytes(vnic.total) }}</span></div>
                </div>
              </div>
            </div>
          </a-collapse-panel>
        </a-collapse>
      </template>
    </a-spin>
  </div>

  <a-modal
    v-model:open="trafficChartModalOpen"
    title="流量趋势"
    :footer="null"
    :width="isMobile ? '100%' : '82vw'"
    :keyboard="false"
    :mask-closable="true"
    wrap-class-name="traffic-chart-modal-wrap"
    :body-style="{ padding: isMobile ? '10px' : '16px' }"
    :mask-style="{ backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)' }"
    @cancel="hideTrafficTooltip"
  >
    <template v-if="isMobile">
      <div class="traffic-mobile-analyzer">
        <div class="traffic-mobile-head">
          <span>趋势</span>
          <span>{{ trafficRangeLabel }} · 每 {{ trafficData?.interval || '—' }}</span>
        </div>
        <div class="traffic-mobile-chart">
          <div v-if="trafficMobileChart.hasData" class="traffic-chart-plot traffic-mobile-plot">
            <div class="traffic-y-scale">
              <span>{{ formatBytes(trafficMobileChart.max) }}</span>
              <span>0 B</span>
            </div>
            <div class="traffic-plot-wrap">
              <div class="traffic-svg-wrap traffic-mobile-svg-wrap">
                <svg class="traffic-chart-svg" viewBox="0 0 640 220" preserveAspectRatio="none" role="img">
                  <line v-for="y in trafficChartGrid" :key="'mobile-grid-' + y" x1="0" x2="640" :y1="y" :y2="y" class="traffic-grid-line" />
                  <polyline
                    v-if="trafficVisibleSeries.inbound"
                    :points="trafficMobileChart.inbound"
                    class="traffic-line traffic-line-inbound"
                  />
                  <polyline
                    v-if="trafficVisibleSeries.outbound"
                    :points="trafficMobileChart.outbound"
                    class="traffic-line traffic-line-outbound"
                  />
                  <g v-for="point in trafficMobileChart.points" :key="'mobile-' + point.timestamp">
                    <circle
                      v-if="trafficVisibleSeries.inbound && trafficMobileChart.showPoints"
                      :cx="point.x"
                      :cy="point.inboundY"
                      r="3.5"
                      class="traffic-point traffic-point-inbound"
                    />
                    <circle
                      v-if="trafficVisibleSeries.outbound && trafficMobileChart.showPoints"
                      :cx="point.x"
                      :cy="point.outboundY"
                      r="3.5"
                      class="traffic-point traffic-point-outbound"
                    />
                    <circle
                      v-if="trafficVisibleSeries.inbound"
                      :cx="point.x"
                      :cy="point.inboundY"
                      r="13"
                      class="traffic-hit-point"
                      @click="selectTrafficMobilePoint(point)"
                      @touchstart.passive="selectTrafficMobilePoint(point)"
                    />
                    <circle
                      v-if="trafficVisibleSeries.outbound"
                      :cx="point.x"
                      :cy="point.outboundY"
                      r="13"
                      class="traffic-hit-point"
                      @click="selectTrafficMobilePoint(point)"
                      @touchstart.passive="selectTrafficMobilePoint(point)"
                    />
                  </g>
                  <g v-if="trafficMobileSelectedPoint">
                    <circle
                      v-if="trafficVisibleSeries.inbound"
                      :cx="trafficMobileSelectedPoint.x"
                      :cy="trafficMobileSelectedPoint.inboundY"
                      r="5.5"
                      class="traffic-active-point traffic-point-inbound"
                    />
                    <circle
                      v-if="trafficVisibleSeries.outbound"
                      :cx="trafficMobileSelectedPoint.x"
                      :cy="trafficMobileSelectedPoint.outboundY"
                      r="5.5"
                      class="traffic-active-point traffic-point-outbound"
                    />
                  </g>
                </svg>
              </div>
              <div class="traffic-x-scale">
                <span>{{ trafficMobileWindowStartLabel }}</span>
                <span class="traffic-x-axis-label">时间</span>
                <span>{{ trafficMobileWindowEndLabel }}</span>
              </div>
            </div>
          </div>
          <a-empty v-else description="暂无趋势数据" />
        </div>
        <div v-if="trafficMobileChart.hasData" class="traffic-mobile-brush">
          <div class="traffic-mobile-brush-head">
            <span>时间窗口</span>
            <span>{{ trafficMobileWindowPositionLabel }}</span>
          </div>
          <a-slider
            v-if="trafficMobileCanSlide"
            v-model:value="trafficMobileWindowStart"
            :min="0"
            :max="trafficMobileWindowMaxStart"
            :step="1"
          />
          <div v-else class="traffic-mobile-brush-static">当前周期数据已全部显示</div>
          <div class="traffic-mobile-brush-range">
            <span>{{ trafficStartLabel }}</span>
            <span>{{ trafficEndLabel }}</span>
          </div>
        </div>
        <div v-if="trafficMobileSelectedPoint" class="traffic-mobile-detail">
          <div class="traffic-mobile-detail-title">
            <span>{{ trafficMobileSelectedPoint.label }}</span>
            <span>{{ trafficRangeLabel }}</span>
          </div>
          <div class="traffic-mobile-detail-grid">
            <div>
              <span>入站</span>
              <strong>{{ formatBytes(trafficMobileSelectedPoint.inbound) }}</strong>
            </div>
            <div>
              <span>出站</span>
              <strong>{{ formatBytes(trafficMobileSelectedPoint.outbound) }}</strong>
            </div>
            <div>
              <span>总量</span>
              <strong>{{ formatBytes(trafficMobileSelectedPoint.total) }}</strong>
            </div>
          </div>
        </div>
        <div class="traffic-legend traffic-mobile-legend">
          <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.inbound }]" @click="toggleTrafficSeries('inbound')"><i class="traffic-dot inbound"></i>入站</span>
          <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.outbound }]" @click="toggleTrafficSeries('outbound')"><i class="traffic-dot outbound"></i>出站</span>
          <span>峰值 {{ formatBytes(trafficMobileChart.max) }}</span>
        </div>
      </div>
    </template>
    <div v-else class="traffic-chart-card traffic-chart-card-large">
      <div class="traffic-chart-head">
        <span>趋势</span>
        <span>{{ trafficRangeLabel }} · 每 {{ trafficData?.interval || '—' }}</span>
      </div>
      <div class="traffic-chart-canvas traffic-chart-canvas-large">
        <div v-if="trafficLargeChart.hasData" class="traffic-chart-plot">
          <div class="traffic-y-scale">
            <span>{{ formatBytes(trafficLargeChart.max) }}</span>
            <span>0 B</span>
          </div>
          <div class="traffic-plot-wrap">
            <div class="traffic-svg-wrap" @mouseleave="hideTrafficTooltip">
              <svg class="traffic-chart-svg" viewBox="0 0 640 220" preserveAspectRatio="none" role="img">
                <line v-for="y in trafficChartGrid" :key="y" x1="0" x2="640" :y1="y" :y2="y" class="traffic-grid-line" />
                <polyline
                  v-if="trafficVisibleSeries.inbound"
                  :points="trafficLargeChart.inbound"
                  :class="['traffic-line', 'traffic-line-inbound', { 'traffic-line-dim': trafficHoverPoint?.series === 'outbound' }]"
                />
                <polyline
                  v-if="trafficVisibleSeries.outbound"
                  :points="trafficLargeChart.outbound"
                  :class="['traffic-line', 'traffic-line-outbound', { 'traffic-line-dim': trafficHoverPoint?.series === 'inbound' }]"
                />
                <g v-for="point in trafficLargeChart.points" :key="'large-' + point.timestamp">
                  <circle
                    v-if="trafficVisibleSeries.inbound && trafficLargeChart.showPoints"
                    :cx="point.x"
                    :cy="point.inboundY"
                    r="3.5"
                    class="traffic-point traffic-point-inbound"
                    @mouseenter="showTrafficTooltip(point, 'inbound', 'large')"
                  />
                  <circle
                    v-if="trafficVisibleSeries.outbound && trafficLargeChart.showPoints"
                    :cx="point.x"
                    :cy="point.outboundY"
                    r="3.5"
                    class="traffic-point traffic-point-outbound"
                    @mouseenter="showTrafficTooltip(point, 'outbound', 'large')"
                  />
                  <circle
                    v-if="trafficVisibleSeries.inbound"
                    :cx="point.x"
                    :cy="point.inboundY"
                    r="8"
                    class="traffic-hit-point"
                    @mouseenter="showTrafficTooltip(point, 'inbound', 'large')"
                  />
                  <circle
                    v-if="trafficVisibleSeries.outbound"
                    :cx="point.x"
                    :cy="point.outboundY"
                    r="8"
                    class="traffic-hit-point"
                    @mouseenter="showTrafficTooltip(point, 'outbound', 'large')"
                  />
                </g>
                <g v-if="trafficHoverPoint?.scope === 'large'">
                  <circle
                    v-if="trafficVisibleSeries.inbound"
                    :cx="trafficHoverPoint.point.x"
                    :cy="trafficHoverPoint.point.inboundY"
                    r="5"
                    class="traffic-active-point traffic-point-inbound"
                  />
                  <circle
                    v-if="trafficVisibleSeries.outbound"
                    :cx="trafficHoverPoint.point.x"
                    :cy="trafficHoverPoint.point.outboundY"
                    r="5"
                    class="traffic-active-point traffic-point-outbound"
                  />
                </g>
              </svg>
              <div
                v-if="trafficHoverPoint?.scope === 'large'"
                class="traffic-hover-card"
                :class="`traffic-hover-card-${trafficHoverPoint.placement}`"
                :style="{ left: `${trafficHoverPoint.left}%`, top: `${trafficHoverPoint.top}%` }"
              >
                <div class="traffic-hover-title">{{ trafficHoverPoint.point.label }}</div>
                <div class="traffic-hover-row"><span>入站</span><strong>{{ formatBytes(trafficHoverPoint.point.inbound) }}</strong></div>
                <div class="traffic-hover-row"><span>出站</span><strong>{{ formatBytes(trafficHoverPoint.point.outbound) }}</strong></div>
                <div class="traffic-hover-row total"><span>总量</span><strong>{{ formatBytes(trafficHoverPoint.point.total) }}</strong></div>
              </div>
            </div>
            <div class="traffic-x-scale">
              <span>{{ trafficStartLabel }}</span>
              <span class="traffic-x-axis-label">时间</span>
              <span>{{ trafficEndLabel }}</span>
            </div>
          </div>
        </div>
        <a-empty v-else description="暂无趋势数据" />
      </div>
      <div class="traffic-legend">
        <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.inbound }]" @click="toggleTrafficSeries('inbound')"><i class="traffic-dot inbound"></i>入站</span>
        <span :class="['traffic-legend-item', { disabled: !trafficVisibleSeries.outbound }]" @click="toggleTrafficSeries('outbound')"><i class="traffic-dot outbound"></i>出站</span>
        <span>峰值 {{ formatBytes(trafficLargeChart.max) }}</span>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { FullscreenOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { getTrafficData } from '../../api/instance'

type TrafficSeries = 'inbound' | 'outbound'
type TrafficRangeMode = number | 'custom'
type TrafficChartScope = 'small' | 'large'

interface TrafficRawPoint {
  timestamp: string
  label: string
  inbound: number
  outbound: number
  total: number
}

interface TrafficChartPoint {
  timestamp: string
  label: string
  x: number
  inboundY: number
  outboundY: number
  inbound: number
  outbound: number
  total: number
}

interface TrafficHoverPoint {
  left: number
  top: number
  placement: 'above' | 'below'
  series: TrafficSeries
  scope: TrafficChartScope
  point: TrafficChartPoint
}

interface TrafficChartModel {
  hasData: boolean
  inbound: string
  outbound: string
  max: number
  points: TrafficChartPoint[]
  showPoints: boolean
}

const props = defineProps<{
  tenant: any | null
  instance: any | null
  isMobile: boolean
  active: boolean
  region?: string
}>()

const emit = defineEmits<{
  (e: 'overlay-active-change', active: boolean): void
}>()

const trafficWindowOptions = [
  { label: '1h', value: 60 },
  { label: '24h', value: 1440 },
  { label: '7d', value: 10080 },
  { label: '30d', value: 43200 },
]

const trafficVnicColumns = [
  { title: '网卡', key: 'vnic', width: 260 },
  { title: '私有 IP', dataIndex: 'privateIp', key: 'privateIp', width: 140 },
  { title: '公网 IP', dataIndex: 'publicIp', key: 'publicIp', width: 140 },
  { title: '入站', key: 'inbound', width: 120 },
  { title: '出站', key: 'outbound', width: 120 },
  { title: '总量', key: 'total', width: 120 },
]

const trafficLoading = ref(false)
const trafficMinutes = ref(60)
const trafficRangeMode = ref<TrafficRangeMode>(60)
const trafficData = ref<any>(null)
const trafficDateRange = ref<[Dayjs, Dayjs] | null>(null)
const trafficHoverPoint = ref<TrafficHoverPoint | null>(null)
const trafficChartModalOpen = ref(false)
const trafficMobileWindowStart = ref(0)
const trafficMobileSelectedTimestamp = ref('')
const trafficVisibleSeries = reactive<Record<TrafficSeries, boolean>>({ inbound: true, outbound: true })
const trafficWindowLabel = computed(() => trafficWindowOptions.find(item => item.value === trafficMinutes.value)?.label || '1h')
const trafficUseCustomRange = computed(() => {
  const range = trafficDateRange.value
  return Array.isArray(range) && !!range[0] && !!range[1]
})
const trafficRangeLabel = computed(() => {
  if (!trafficUseCustomRange.value || !trafficDateRange.value) return trafficWindowLabel.value
  return `${trafficDateRange.value[0].format('YYYY-MM-DD')} - ${trafficDateRange.value[1].format('YYYY-MM-DD')}`
})
const trafficEffectiveMinutes = computed(() => Number(trafficData.value?.minutes) || trafficMinutes.value)
const trafficStartLabel = computed(() => formatTrafficAxisTime(trafficData.value?.startTime, trafficEffectiveMinutes.value))
const trafficEndLabel = computed(() => formatTrafficAxisTime(trafficData.value?.endTime, trafficEffectiveMinutes.value))
const trafficVnics = computed(() => Array.isArray(trafficData.value?.vnics) ? trafficData.value.vnics : [])
const trafficChartGrid = [24, 67, 110, 153, 196]
const trafficRawPoints = computed<TrafficRawPoint[]>(() => normalizeTrafficRows(Array.isArray(trafficData.value?.points) ? trafficData.value.points : []))
const trafficChart = computed(() => buildTrafficChart(40))
const trafficLargeChart = computed(() => buildTrafficChart('all'))
const trafficMobileWindowSize = computed(() => {
  const total = trafficRawPoints.value.length
  if (total <= 40) return Math.max(total, 1)
  const minutes = trafficEffectiveMinutes.value
  if (minutes <= 1440) return Math.min(total, 48)
  if (minutes <= 10080) return Math.min(total, 56)
  return Math.min(total, 45)
})
const trafficMobileWindowMaxStart = computed(() => Math.max(0, trafficRawPoints.value.length - trafficMobileWindowSize.value))
const trafficMobileCanSlide = computed(() => trafficMobileWindowMaxStart.value > 0)
const trafficMobileWindowRows = computed(() => {
  const start = Math.min(Math.max(0, trafficMobileWindowStart.value), trafficMobileWindowMaxStart.value)
  return trafficRawPoints.value.slice(start, start + trafficMobileWindowSize.value)
})
const trafficMobileChart = computed(() => buildTrafficChartFromPoints(trafficMobileWindowRows.value, 'all'))
const trafficMobileSelectedPoint = computed<TrafficChartPoint | null>(() => {
  const points = trafficMobileChart.value.points
  if (!points.length) return null
  return points.find(point => point.timestamp === trafficMobileSelectedTimestamp.value) || points[Math.floor(points.length / 2)]
})
const trafficMobileWindowPositionLabel = computed(() => {
  const total = trafficRawPoints.value.length
  if (!total) return '0 / 0'
  const start = Math.min(Math.max(0, trafficMobileWindowStart.value), trafficMobileWindowMaxStart.value)
  const end = Math.min(total, start + trafficMobileWindowSize.value)
  return `${start + 1}-${end} / ${total}`
})
const trafficMobileWindowStartLabel = computed(() => trafficMobileChart.value.points[0]?.label || trafficStartLabel.value)
const trafficMobileWindowEndLabel = computed(() => {
  const points = trafficMobileChart.value.points
  return points[points.length - 1]?.label || trafficEndLabel.value
})
const hasOpenModal = computed(() => trafficChartModalOpen.value)
let trafficLoadSeq = 0

function regionParam(): { region?: string } {
  const r =
    (props.region && String(props.region).trim()) ||
    (props.instance?.region && String(props.instance.region).trim()) ||
    (props.tenant?.ociRegion && String(props.tenant.ociRegion).trim()) ||
    ''
  return r ? { region: r } : {}
}

function formatBytes(value: unknown) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, i)).toFixed(2)} ${units[i]}`
}

function shortOcId(value?: string) {
  if (!value) return '—'
  return value.length > 18 ? `${value.slice(0, 10)}...${value.slice(-6)}` : value
}

function buildTrafficChart(pointLimit: number | 'all'): TrafficChartModel {
  return buildTrafficChartFromPoints(trafficRawPoints.value, pointLimit)
}

function normalizeTrafficRows(rows: any[]): TrafficRawPoint[] {
  return rows.map((point: any) => ({
    timestamp: String(point?.timestamp || ''),
    label: formatTrafficPointTime(point?.timestamp, trafficEffectiveMinutes.value),
    inbound: Number(point?.inbound) || 0,
    outbound: Number(point?.outbound) || 0,
    total: Number(point?.total) || ((Number(point?.inbound) || 0) + (Number(point?.outbound) || 0)),
  }))
}

function buildTrafficChartFromPoints(points: TrafficRawPoint[], pointLimit: number | 'all'): TrafficChartModel {
  const max = Math.max(0, ...points.map(point => Math.max(point.inbound, point.outbound)))
  if (!points.length || max <= 0) {
    return { hasData: false, inbound: '', outbound: '', max: 0, points: [], showPoints: false }
  }

  const width = 640
  const height = 220
  const padX = 8
  const padY = 18
  const plotW = width - padX * 2
  const plotH = height - padY * 2
  const toCoord = (value: number, index: number) => {
    const x = padX + (points.length === 1 ? plotW / 2 : (index * plotW) / (points.length - 1))
    const y = padY + plotH - (value / max) * plotH
    return { x, y }
  }
  const chartPoints: TrafficChartPoint[] = points.map((point, index) => {
    const inboundCoord = toCoord(point.inbound, index)
    const outboundCoord = toCoord(point.outbound, index)
    return {
      timestamp: point.timestamp,
      label: point.label,
      x: inboundCoord.x,
      inboundY: inboundCoord.y,
      outboundY: outboundCoord.y,
      inbound: point.inbound,
      outbound: point.outbound,
      total: point.total,
    }
  })
  const toPolyline = (key: TrafficSeries) => chartPoints.map(point => {
    const y = key === 'inbound' ? point.inboundY : point.outboundY
    return `${point.x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')

  return {
    hasData: true,
    inbound: toPolyline('inbound'),
    outbound: toPolyline('outbound'),
    max,
    points: chartPoints,
    showPoints: pointLimit === 'all' || chartPoints.length <= pointLimit,
  }
}

function formatTrafficAxisTime(value: unknown, minutes: number) {
  if (!value) return '—'
  const time = dayjs(String(value))
  if (!time.isValid()) return '—'
  return minutes <= 1440 ? time.format('HH:mm') : time.format('MM-DD')
}

function formatTrafficPointTime(value: unknown, minutes: number) {
  if (!value) return '—'
  const time = dayjs(String(value))
  if (!time.isValid()) return '—'
  if (minutes <= 1440) return time.format('YYYY-MM-DD HH:mm')
  if (minutes <= 10080) return time.format('YYYY-MM-DD HH:00')
  return time.format('YYYY-MM-DD')
}

function disabledTrafficDate(current: Dayjs) {
  return !!current && current.isAfter(dayjs().endOf('day'))
}

function validateTrafficDateRange(showMessage = true) {
  if (!trafficUseCustomRange.value || !trafficDateRange.value) return true
  const start = trafficDateRange.value[0].startOf('day')
  const end = trafficDateRange.value[1].endOf('day')
  if (!start.isValid() || !end.isValid() || !start.isBefore(end)) {
    if (showMessage) message.error('请选择有效的日期范围')
    return false
  }
  if (end.diff(start, 'day', true) > 90) {
    if (showMessage) message.error('流量自定义查询最多支持 90 天')
    return false
  }
  return true
}

function onTrafficWindowChange() {
  if (trafficRangeMode.value === 'custom') return
  trafficMinutes.value = Number(trafficRangeMode.value)
  trafficDateRange.value = null
  trafficHoverPoint.value = null
  void loadTraffic()
}

function onTrafficDateRangeChange() {
  trafficHoverPoint.value = null
  if (!trafficUseCustomRange.value) {
    trafficRangeMode.value = trafficMinutes.value
    void loadTraffic()
    return
  }
  trafficRangeMode.value = 'custom'
  if (validateTrafficDateRange()) void loadTraffic()
}

function showTrafficTooltip(point: TrafficChartPoint, series: TrafficSeries, scope: TrafficChartScope) {
  const y = series === 'inbound' ? point.inboundY : point.outboundY
  const xPercent = (point.x / 640) * 100
  const yPercent = (y / 220) * 100
  const minLeft = props.isMobile ? 30 : 14
  const maxLeft = props.isMobile ? 70 : 86
  trafficHoverPoint.value = {
    left: Math.min(maxLeft, Math.max(minLeft, xPercent)),
    top: Math.min(84, Math.max(10, yPercent)),
    placement: yPercent < 34 ? 'below' : 'above',
    series,
    scope,
    point,
  }
}

function hideTrafficTooltip() {
  trafficHoverPoint.value = null
}

function resetTrafficMobileWindow() {
  trafficMobileWindowStart.value = trafficMobileWindowMaxStart.value
  trafficMobileSelectedTimestamp.value = ''
}

function selectTrafficMobilePoint(point: TrafficChartPoint) {
  trafficMobileSelectedTimestamp.value = point.timestamp
  trafficHoverPoint.value = null
}

function toggleTrafficSeries(series: TrafficSeries) {
  const other: TrafficSeries = series === 'inbound' ? 'outbound' : 'inbound'
  if (trafficVisibleSeries[series] && !trafficVisibleSeries[other]) return
  trafficVisibleSeries[series] = !trafficVisibleSeries[series]
  trafficHoverPoint.value = null
}

function openTrafficChartModal() {
  if (!trafficChart.value.hasData) return
  trafficHoverPoint.value = null
  if (props.isMobile) resetTrafficMobileWindow()
  trafficChartModalOpen.value = true
}

function reset() {
  trafficLoadSeq += 1
  trafficLoading.value = false
  trafficData.value = null
  trafficDateRange.value = null
  trafficRangeMode.value = trafficMinutes.value
  trafficHoverPoint.value = null
  trafficMobileWindowStart.value = 0
  trafficMobileSelectedTimestamp.value = ''
  trafficChartModalOpen.value = false
}

async function loadTraffic() {
  if (!props.instance || !props.tenant) return
  if (!validateTrafficDateRange()) return
  const requestId = ++trafficLoadSeq
  const tenantId = props.tenant.id
  const instanceId = props.instance.instanceId
  const region = regionParam()
  trafficHoverPoint.value = null
  trafficLoading.value = true
  try {
    const params: {
      id: string
      instanceId: string
      minutes?: number
      startTime?: string
      endTime?: string
      region?: string
    } = {
      id: tenantId,
      instanceId,
      minutes: trafficMinutes.value,
      ...region,
    }
    if (trafficUseCustomRange.value && trafficDateRange.value) {
      params.startTime = trafficDateRange.value[0].startOf('day').toISOString()
      params.endTime = trafficDateRange.value[1].endOf('day').toISOString()
    }
    const res = await getTrafficData(params)
    if (requestId !== trafficLoadSeq || props.tenant?.id !== tenantId || props.instance?.instanceId !== instanceId) return
    trafficData.value = res.data || { inbound: 0, outbound: 0, total: 0, points: [], vnics: [] }
    resetTrafficMobileWindow()
  } catch (e: any) {
    if (requestId === trafficLoadSeq) message.error(e?.message || '加载流量数据失败')
  } finally {
    if (requestId === trafficLoadSeq) trafficLoading.value = false
  }
}

watch(trafficMobileWindowMaxStart, (maxStart) => {
  if (trafficMobileWindowStart.value > maxStart) trafficMobileWindowStart.value = maxStart
})

watch(trafficMobileWindowStart, () => {
  trafficMobileSelectedTimestamp.value = ''
})

watch(
  () => [props.tenant?.id, props.instance?.instanceId, props.region],
  () => {
    reset()
    if (props.active) void loadTraffic()
  },
)

watch(
  () => props.active,
  (active) => {
    if (active && !trafficData.value) void loadTraffic()
  },
  { immediate: true },
)

watch(hasOpenModal, (active) => emit('overlay-active-change', active), { immediate: true })

defineExpose({
  loadTraffic,
  reset,
  hasOpenModal,
})
</script>

<style scoped>
.traffic-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.traffic-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.traffic-range-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.traffic-date-range {
  width: 260px;
  max-width: 100%;
}
.traffic-date-range :deep(input) {
  cursor: pointer;
  caret-color: transparent;
}
.traffic-date-range-active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.22);
  border-radius: 6px;
}
.traffic-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.traffic-summary-card {
  min-height: 86px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 8px;
}
.traffic-summary-card span {
  color: var(--text-sub);
  font-size: 12px;
}
.traffic-summary-card strong {
  color: var(--text-main);
  font-size: 20px;
  line-height: 1.2;
  word-break: break-word;
}
.traffic-summary-card.inbound {
  border-left: 3px solid #1677ff;
}
.traffic-summary-card.outbound {
  border-left: 3px solid #52c41a;
}
.traffic-summary-card.total {
  border-left: 3px solid #faad14;
}
.traffic-chart-card {
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}
.traffic-chart-card-large {
  background: transparent;
  border: 0;
  padding: 0;
}
.traffic-chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--text-main);
  font-weight: 600;
  margin-bottom: 10px;
}
.traffic-chart-head span:last-child {
  color: var(--text-sub);
  font-size: 12px;
  font-weight: 400;
}
.traffic-chart-canvas {
  height: 260px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.04);
  overflow: visible;
  display: flex;
  align-items: center;
  justify-content: center;
}
.traffic-chart-plot {
  width: 100%;
  height: 100%;
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 6px;
  padding: 10px 12px 8px 8px;
  box-sizing: border-box;
}
.traffic-y-scale {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: flex-end;
  color: var(--text-sub);
  font-size: 12px;
  padding: 2px 0 28px;
  white-space: nowrap;
}
.traffic-plot-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.traffic-svg-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: visible;
}
.traffic-chart-svg {
  width: 100%;
  height: 100%;
  flex: 1;
  min-height: 0;
}
.traffic-point {
  cursor: pointer;
  stroke: var(--bg-sidebar);
  stroke-width: 2;
  opacity: 0.92;
  transform-box: fill-box;
  transform-origin: center;
  transition: transform 0.16s ease, opacity 0.16s ease;
}
.traffic-point:hover {
  transform: scale(1.45);
  opacity: 1;
}
.traffic-point-inbound {
  fill: #1677ff;
}
.traffic-point-outbound {
  fill: #52c41a;
}
.traffic-hover-card {
  position: absolute;
  z-index: 3;
  min-width: 150px;
  max-width: min(210px, calc(100% - 16px));
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  pointer-events: none;
}
.traffic-hover-card-above {
  transform: translate(-50%, calc(-100% - 10px));
}
.traffic-hover-card-below {
  transform: translate(-50%, 10px);
}
.traffic-hover-title {
  color: var(--text-main);
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 6px;
  white-space: nowrap;
}
.traffic-hover-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-sub);
  font-size: 12px;
  line-height: 1.8;
}
.traffic-hover-row strong {
  color: var(--text-main);
  font-weight: 700;
}
.traffic-hover-row.total {
  margin-top: 3px;
  padding-top: 3px;
  border-top: 1px solid var(--border);
}
.traffic-x-scale {
  height: 22px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 8px;
  color: var(--text-sub);
  font-size: 12px;
  white-space: nowrap;
}
.traffic-x-scale span:last-child {
  text-align: right;
}
.traffic-x-axis-label {
  color: var(--text-main);
  font-weight: 600;
}
.traffic-grid-line {
  stroke: rgba(127, 127, 127, 0.22);
  stroke-width: 1;
}
.traffic-line {
  fill: none;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-linejoin: round;
  opacity: 0.88;
  transition: opacity 0.16s ease, stroke-width 0.16s ease;
}
.traffic-line-dim {
  opacity: 0.34;
}
.traffic-line-inbound {
  stroke: #1677ff;
}
.traffic-line-outbound {
  stroke: #52c41a;
}
.traffic-hit-point {
  fill: transparent;
  stroke: transparent;
  cursor: pointer;
  pointer-events: all;
}
.traffic-active-point {
  stroke: var(--bg-card);
  stroke-width: 2.5;
  pointer-events: none;
}
.traffic-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 10px;
  color: var(--text-sub);
  font-size: 12px;
}
.traffic-legend-item {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  user-select: none;
  transition: opacity 0.16s ease;
}
.traffic-legend-item.disabled {
  opacity: 0.42;
}
.traffic-expand-btn {
  margin-left: auto;
}
.traffic-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.traffic-dot.inbound {
  background: #1677ff;
}
.traffic-dot.outbound {
  background: #52c41a;
}
.traffic-chart-canvas-large {
  height: min(70vh, 620px);
}
.traffic-mobile-analyzer {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  height: calc(100vh - 112px);
  overflow-y: auto;
  padding-bottom: env(safe-area-inset-bottom);
}
.traffic-mobile-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  color: var(--text-main);
  font-weight: 700;
}
.traffic-mobile-head span:last-child {
  color: var(--text-sub);
  font-size: 12px;
  font-weight: 400;
  text-align: right;
}
.traffic-mobile-chart {
  flex: 0 0 auto;
  height: clamp(260px, 42vh, 380px);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(127, 127, 127, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: visible;
}
.traffic-mobile-plot {
  grid-template-columns: 56px minmax(0, 1fr);
  padding: 10px 10px 8px 8px;
}
.traffic-mobile-svg-wrap {
  touch-action: manipulation;
}
.traffic-mobile-brush {
  flex: 0 0 auto;
  padding: 10px 12px 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sidebar);
}
.traffic-mobile-brush-head,
.traffic-mobile-brush-range {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--text-sub);
  font-size: 12px;
}
.traffic-mobile-brush-head span:first-child {
  color: var(--text-main);
  font-weight: 600;
}
.traffic-mobile-brush :deep(.ant-slider) {
  margin: 10px 4px 8px;
}
.traffic-mobile-brush :deep(.ant-slider-rail) {
  background: rgba(127, 127, 127, 0.18);
}
.traffic-mobile-brush-static {
  margin: 10px 0 8px;
  color: var(--text-sub);
  font-size: 12px;
}
.traffic-mobile-detail {
  position: sticky;
  bottom: 0;
  z-index: 2;
  flex: 0 0 auto;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
}
.traffic-mobile-detail-title {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  color: var(--text-main);
  font-weight: 700;
}
.traffic-mobile-detail-title span {
  min-width: 0;
}
.traffic-mobile-detail-title span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.traffic-mobile-detail-title span:last-child {
  color: var(--text-sub);
  font-size: 12px;
  font-weight: 400;
  text-align: right;
}
.traffic-mobile-detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.traffic-mobile-detail-grid div {
  min-width: 0;
  padding: 8px;
  border-radius: 6px;
  background: var(--bg-sidebar);
}
.traffic-mobile-detail-grid span {
  display: block;
  color: var(--text-sub);
  font-size: 12px;
  margin-bottom: 4px;
}
.traffic-mobile-detail-grid strong {
  display: block;
  color: var(--text-main);
  font-size: 14px;
  line-height: 1.25;
  word-break: break-word;
}
.traffic-mobile-legend {
  flex: 0 0 auto;
  margin-top: 0;
  padding-bottom: 2px;
}
:global(.traffic-chart-modal-wrap .ant-modal) {
  max-width: calc(100vw - 32px);
}
:global(.traffic-chart-modal-wrap .ant-modal-content) {
  background: var(--bg-card);
  border: 1px solid var(--border);
}
.traffic-vnic-collapse {
  background: transparent;
}
.traffic-vnic-name {
  color: var(--text-main);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.traffic-vnic-id {
  color: var(--text-sub);
  font-size: 12px;
  margin-top: 2px;
}
.traffic-vnic-mobile-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mobile-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
}
.mobile-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.mobile-card-title {
  min-width: 0;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 700;
  word-break: break-all;
}
.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mobile-card-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.mobile-card-row .label {
  color: var(--text-sub);
  white-space: nowrap;
}
.mobile-card-row .value {
  min-width: 0;
  color: var(--text-main);
  text-align: right;
  word-break: break-all;
}

@media (max-width: 767px) {
  .traffic-toolbar {
    align-items: stretch;
  }
  .traffic-range-tools {
    width: 100%;
    flex-direction: column;
    align-items: stretch;
  }
  .traffic-toolbar :deep(.ant-segmented) {
    width: 100%;
  }
  .traffic-date-range {
    width: 100%;
  }
  .traffic-expand-btn {
    margin-left: 0;
    width: 36px;
    padding-inline: 0;
  }
  .traffic-summary-grid {
    grid-template-columns: 1fr;
  }
  .traffic-summary-card {
    min-height: 76px;
  }
  .traffic-chart-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .traffic-chart-canvas {
    height: 230px;
  }
  .traffic-chart-plot {
    grid-template-columns: 52px minmax(0, 1fr);
    gap: 4px;
    padding: 8px;
  }
  .traffic-y-scale,
  .traffic-x-scale {
    font-size: 11px;
  }
  .traffic-hover-card {
    min-width: 132px;
    padding: 8px 10px;
  }
  .traffic-chart-canvas-large {
    height: calc(100vh - 168px);
    min-height: 360px;
  }
  :global(.traffic-chart-modal-wrap .ant-modal) {
    top: 0;
    margin: 0;
    max-width: 100vw;
    padding-bottom: 0;
  }
  :global(.traffic-chart-modal-wrap .ant-modal-content) {
    min-height: 100vh;
    border-radius: 0;
  }
  :global(.traffic-chart-modal-wrap .ant-modal-body) {
    max-height: calc(100vh - 56px);
    overflow: hidden;
  }
  .traffic-mobile-analyzer {
    height: calc(100vh - 86px);
  }
  .traffic-mobile-chart {
    height: clamp(250px, 40vh, 360px);
  }
}
</style>
