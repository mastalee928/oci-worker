import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import { message } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './assets/global.css'
import App from './App.vue'
import router from './router'

message.config({ maxCount: 3 })

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(Antd)
app.mount('#app')

document.addEventListener('click', (e) => {
  const target = e.target as HTMLElement
  if (!target.closest('.ant-message')) {
    message.destroy()
  }
})
