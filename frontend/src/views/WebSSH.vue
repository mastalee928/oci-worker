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

const themeStore = useThemeStore()
const webSshFrame = ref<HTMLIFrameElement | null>(null)
const webSshSrc = ref(`/webssh/index.html?appTheme=${encodeURIComponent(themeStore.mode)}`)

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
