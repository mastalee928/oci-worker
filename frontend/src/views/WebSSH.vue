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
  height: 100%;
}

.webssh-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

</style>
