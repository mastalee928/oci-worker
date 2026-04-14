<template>
  <div>
    <a-row :gutter="[16, 16]">
      <a-col :xs="24" :md="12">
        <a-card title="备份">
          <a-form layout="vertical">
            <a-form-item label="加密密码">
              <a-input-password v-model:value="backupPassword" placeholder="设置备份加密密码" />
            </a-form-item>
            <a-button type="primary" @click="handleBackup" :loading="backupLoading">
              创建加密备份
            </a-button>
          </a-form>
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12">
        <a-card title="恢复">
          <a-form layout="vertical">
            <a-form-item label="备份文件">
              <a-upload :before-upload="handleFileSelect" :max-count="1" accept=".zip">
                <a-button><UploadOutlined />选择备份文件</a-button>
              </a-upload>
            </a-form-item>
            <a-form-item label="解密密码">
              <a-input-password v-model:value="restorePassword" placeholder="输入备份加密密码" />
            </a-form-item>
            <a-button type="primary" danger @click="handleRestore" :loading="restoreLoading">
              恢复备份
            </a-button>
          </a-form>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { UploadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'

const backupPassword = ref('')
const restorePassword = ref('')
const backupLoading = ref(false)
const restoreLoading = ref(false)

function handleBackup() { message.info('备份功能将在后续版本实现') }
function handleFileSelect() { return false }
function handleRestore() { message.info('恢复功能将在后续版本实现') }
</script>
