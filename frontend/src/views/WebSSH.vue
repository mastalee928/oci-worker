<template>
  <div class="webssh-container">
    <iframe
      ref="webSshFrame"
      :src="webSshSrc"
      class="webssh-iframe"
      allow="clipboard-read; clipboard-write"
      @load="syncWebSshTheme"
    ></iframe>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useThemeStore } from '../stores/theme'
import { getPanelToken, setWebSshTokenCookie } from '../utils/session'

const themeStore = useThemeStore()
const webSshFrame = ref<HTMLIFrameElement | null>(null)

function buildWebSshSrc() {
  const params = new URLSearchParams()
  params.set('appTheme', themeStore.mode)
  setWebSshTokenCookie(getPanelToken())
  return `/webssh/index.html?${params.toString()}`
}

const webSshSrc = ref(buildWebSshSrc())

function syncWebSshTheme() {
  webSshFrame.value?.contentWindow?.postMessage(
    { type: 'ociworker-theme', theme: themeStore.mode },
    window.location.origin,
  )
}

watch(
  () => themeStore.mode,
  () => {
    syncWebSshTheme()
  },
)
</script>

<style scoped>
.webssh-container {
  height: 100%;
}

.webssh-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

</style>
