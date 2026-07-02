import { defineAsyncComponent, h, type Component } from 'vue'

const ASYNC_COMPONENT_RELOAD_KEY = 'ociworker:async-component-reload-at'
const ASYNC_COMPONENT_RELOAD_COOLDOWN_MS = 60_000

export function isStaleChunkError(error: unknown) {
  const message = String((error as any)?.message || error || '')
  return /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk|error loading dynamically imported module|Unable to preload CSS/i
    .test(message)
}

export function reloadOnceForUpdatedAssets() {
  if (typeof window === 'undefined') return false
  const now = Date.now()
  const last = Number(window.sessionStorage.getItem(ASYNC_COMPONENT_RELOAD_KEY) || 0)
  if (Number.isFinite(last) && now - last < ASYNC_COMPONENT_RELOAD_COOLDOWN_MS) return false
  window.sessionStorage.setItem(ASYNC_COMPONENT_RELOAD_KEY, String(now))
  window.location.reload()
  return true
}

export function defineAppAsyncComponent<T extends Component>(
  loader: () => Promise<T | { default: T }>,
) {
  return defineAsyncComponent({
    loader,
    delay: 120,
    timeout: 30_000,
    errorComponent: {
      setup() {
        return () => h('div', { class: 'async-component-error' }, '组件资源加载失败，请刷新页面后重试')
      },
    },
    onError(error, retry, fail, attempts) {
      if (attempts <= 2) {
        window.setTimeout(retry, attempts * 600)
        return
      }
      if (isStaleChunkError(error) && reloadOnceForUpdatedAssets()) return
      fail()
    },
  })
}
