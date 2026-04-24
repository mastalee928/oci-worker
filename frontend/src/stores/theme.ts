import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<'dark' | 'light'>('dark')
  const wallpaperUrl = ref('')
  const wallpaperActive = ref(false)

  const isDark = computed(() => mode.value === 'dark')

  function init() {
    const saved = localStorage.getItem('theme')
    if (saved === 'light' || saved === 'dark') {
      mode.value = saved
    } else if (window.matchMedia?.('(prefers-color-scheme: light)').matches) {
      mode.value = 'light'
    }
    applyTheme()

    const wpOn = localStorage.getItem('wallpaper') === 'on'
    const wpUrl = localStorage.getItem('wallpaperUrl')
    if (wpOn && wpUrl) {
      wallpaperUrl.value = wpUrl
      wallpaperActive.value = true
      applyWallpaper()
    }
  }

  function toggleTheme() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem('theme', mode.value)
    applyTheme()
  }

  function applyTheme() {
    document.documentElement.setAttribute('data-theme', mode.value)
    document.body.setAttribute('data-theme', mode.value)
  }

  function toggleWallpaper() {
    if (wallpaperActive.value) {
      wallpaperActive.value = false
      wallpaperUrl.value = ''
      localStorage.removeItem('wallpaper')
      localStorage.removeItem('wallpaperUrl')
      applyWallpaper()
    } else {
      loadRandomWallpaper()
    }
  }

  function loadRandomWallpaper() {
    const ts = Date.now()
    const url = `https://picsum.photos/1920/1080?random=${ts}`
    const img = new Image()
    img.onload = () => {
      wallpaperUrl.value = img.src
      wallpaperActive.value = true
      localStorage.setItem('wallpaper', 'on')
      localStorage.setItem('wallpaperUrl', img.src)
      applyWallpaper()
    }
    img.src = url
  }

  function applyWallpaper() {
    const layer = document.getElementById('wallpaperLayer')
    if (!layer) return
    if (wallpaperActive.value && wallpaperUrl.value) {
      layer.style.backgroundImage = `url(${wallpaperUrl.value})`
      layer.classList.add('active')
      document.body.classList.add('wallpaper-active')
    } else {
      layer.classList.remove('active')
      document.body.classList.remove('wallpaper-active')
      layer.style.backgroundImage = ''
    }
  }

  return { mode, isDark, wallpaperActive, wallpaperUrl, init, toggleTheme, toggleWallpaper }
})
