import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { message } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './assets/global.css'
import App from './App.vue'
import router from './router'

message.config({ maxCount: 3 })

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')

document.addEventListener('click', (e) => {
  const target = e.target as HTMLElement
  if (!target.closest('.ant-message')) {
    message.destroy()
  }
})
