<template>
  <a-modal
    :open="open"
    :width="460"
    :footer="null"
    :destroy-on-close="true"
    @cancel="close"
  >
    <template #title>
      <span class="ssh-method-title">
        <ThunderboltOutlined />
        <span>SSH 连接</span>
      </span>
    </template>

    <div class="ssh-method-body">
      <div class="ssh-method-target">
        <strong>{{ instanceLabel }}</strong>
        <span>{{ region || '未指定区域' }} · {{ publicIp || '无公网 IP' }}</span>
      </div>

      <button
        type="button"
        class="ssh-method-option"
        :class="{ disabled: !publicIp }"
        :disabled="!publicIp"
        @click="select('DIRECT')"
      >
        <span class="ssh-method-icon direct"><GlobalOutlined /></span>
        <span class="ssh-method-copy">
          <strong>WebSSH 直连</strong>
          <small v-if="publicIp">通过实例公网 IP 连接，打开 WebSSH 登录页</small>
          <small v-else>该实例没有公网 IP，无法使用直连</small>
        </span>
        <RightOutlined class="ssh-method-arrow" />
      </button>

      <button type="button" class="ssh-method-option" @click="select('BASTION')">
        <span class="ssh-method-icon bastion"><SafetyCertificateOutlined /></span>
        <span class="ssh-method-copy">
          <strong>堡垒机连接</strong>
          <small>通过 OCI Bastion 访问实例私网 SSH</small>
        </span>
        <RightOutlined class="ssh-method-arrow" />
      </button>

      <div class="ssh-method-foot">
        <a-button @click="close">取消</a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  GlobalOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'

const props = defineProps<{
  open: boolean
  instance: any | null
  region: string
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'select', mode: 'DIRECT' | 'BASTION'): void
}>()

const instanceLabel = computed(() =>
  String(props.instance?.displayName || props.instance?.name || props.instance?.instanceId || '实例'),
)

const publicIp = computed(() => String(props.instance?.publicIp || '').trim())

function close() {
  emit('update:open', false)
}

function select(mode: 'DIRECT' | 'BASTION') {
  if (mode === 'DIRECT' && !publicIp.value) return
  emit('select', mode)
  emit('update:open', false)
}
</script>

<style scoped>
.ssh-method-title {
  align-items: center;
  display: inline-flex;
  gap: 8px;
}

.ssh-method-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ssh-method-target {
  border-bottom: 1px solid var(--border, #e5e7eb);
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 0 0 14px;
}

.ssh-method-target strong {
  color: var(--text-main, #111827);
  font-size: 15px;
}

.ssh-method-target span {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.ssh-method-option {
  align-items: center;
  background: var(--panel, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  color: inherit;
  cursor: pointer;
  display: flex;
  gap: 11px;
  min-height: 70px;
  padding: 12px 13px;
  text-align: left;
  transition: border-color 0.15s ease, background 0.15s ease;
  width: 100%;
}

.ssh-method-option:hover:not(:disabled) {
  background: var(--panel-hover, rgba(99, 102, 241, 0.06));
  border-color: var(--primary, #6366f1);
}

.ssh-method-option.disabled,
.ssh-method-option:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.ssh-method-icon {
  align-items: center;
  border-radius: 7px;
  display: inline-flex;
  flex: 0 0 32px;
  height: 32px;
  justify-content: center;
  width: 32px;
}

.ssh-method-icon.direct {
  background: rgba(22, 119, 255, 0.12);
  color: #1677ff;
}

.ssh-method-icon.bastion {
  background: rgba(19, 194, 194, 0.12);
  color: #13c2c2;
}

.ssh-method-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ssh-method-copy strong {
  color: var(--text-main, #111827);
  font-size: 14px;
}

.ssh-method-copy small {
  color: var(--text-sub, #6b7280);
  font-size: 12px;
  line-height: 1.45;
}

.ssh-method-arrow {
  color: var(--text-sub, #6b7280);
  flex: 0 0 auto;
}

.ssh-method-foot {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}
</style>
