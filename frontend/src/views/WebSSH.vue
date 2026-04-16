<template>
  <div class="webssh-container">
    <iframe
      src="/webssh/index.html"
      class="webssh-iframe"
      allow="clipboard-read; clipboard-write"
    ></iframe>
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.webssh-container {
  height: calc(100vh - 64px);
  margin: -24px;
}

.webssh-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

@media (max-width: 768px) {
  .webssh-container {
    height: calc(100vh - 56px);
    margin: -16px;
  }
}
</style>
