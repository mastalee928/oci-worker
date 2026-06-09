<script setup lang="ts">
withDefaults(
  defineProps<{
    size?: number | string
  }>(),
  { size: 40 },
)
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="52 46 96 100"
    :width="size"
    :height="size"
    class="oci-logo"
    role="img"
    aria-label="OCI Worker"
  >
    <circle class="oci-logo-ring" cx="100" cy="90" r="40" fill="none" stroke-width="3" />
    <path
      class="oci-logo-mark"
      d="M 75,140 L 100,105 L 125,140"
      fill="none"
      stroke-width="3"
      stroke-linecap="square"
      stroke-linejoin="miter"
    />
    <circle class="oci-logo-dot" cx="100" cy="90" r="4" />
  </svg>
</template>

<style scoped>
.oci-logo {
  display: block;
  flex-shrink: 0;
}
.oci-logo-ring {
  stroke: #ffffff;
}
.oci-logo-mark {
  stroke: #7c4dff;
}
.oci-logo-dot {
  fill: #7c4dff;
}
:global([data-theme='light']) .oci-logo-ring {
  stroke: #c74634;
}
</style>
