import { ref, onMounted, onUnmounted } from 'vue'

const MOBILE_MAX = 767

export function useIsMobile(breakpoint = MOBILE_MAX) {
  const isMobile = ref(typeof window !== 'undefined' && window.innerWidth <= breakpoint)
  function checkMobile() {
    isMobile.value = window.innerWidth <= breakpoint
  }
  onMounted(() => window.addEventListener('resize', checkMobile))
  onUnmounted(() => window.removeEventListener('resize', checkMobile))
  return { isMobile, checkMobile }
}
