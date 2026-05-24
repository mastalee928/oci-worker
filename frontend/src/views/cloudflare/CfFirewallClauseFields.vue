<template>
  <div>
    <a-row :gutter="8" align="middle">
      <a-col :xs="24" :sm="7">
        <div class="cf-mini-label">字段</div>
        <a-select
          v-model:value="clause.fieldId"
          :options="fieldOptions"
          style="width: 100%"
          @change="onFieldChange"
        />
      </a-col>
      <a-col :xs="24" :sm="7">
        <div class="cf-mini-label">运算符</div>
        <a-select
          v-model:value="clause.operator"
          :options="operatorOptions"
          style="width: 100%"
        />
      </a-col>
      <a-col :xs="24" :sm="field?.type === 'bool' ? 7 : 10">
        <div class="cf-mini-label">值</div>
        <a-switch
          v-if="field?.type === 'bool'"
          v-model:checked="clause.boolValue"
          checked-children="是 (HTTPS)"
          un-checked-children="否"
        />
        <a-input
          v-else
          v-model:value="clause.value"
          :placeholder="field?.placeholder || '输入匹配值'"
          allow-clear
        />
      </a-col>
      <a-col v-if="removable" :xs="24" :sm="3" class="cf-clause-del">
        <a-button type="link" danger size="small" @click="emit('remove')">删除</a-button>
      </a-col>
    </a-row>
    <p v-if="field?.valueHint" class="cf-value-hint">{{ field.valueHint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  FIREWALL_FIELDS,
  defaultOperatorForField,
  operatorsForField,
  type OperatorId,
  type VisualClauseForm,
} from './cfFirewallExpression'

const props = defineProps<{
  clause: VisualClauseForm
  removable?: boolean
}>()

const emit = defineEmits<{ remove: [] }>()

const fieldOptions = FIREWALL_FIELDS.map(f => ({ value: f.id, label: f.label }))

const field = computed(() => FIREWALL_FIELDS.find(f => f.id === props.clause.fieldId))

const operatorOptions = computed(() =>
  operatorsForField(field.value).map(o => ({ value: o.id, label: o.label })),
)

function onFieldChange() {
  const f = field.value
  if (!f) return
  props.clause.operator = defaultOperatorForField(f)
  props.clause.value = ''
  props.clause.boolValue = true
}
</script>

<style scoped>
.cf-mini-label {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 4px;
}
.cf-value-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-sub);
}
.cf-clause-del {
  display: flex;
  align-items: flex-end;
  padding-bottom: 4px;
}
</style>
