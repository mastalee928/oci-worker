import 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 为 true 时，响应错误不弹全局 message（如一键更新轮询 /sys/glance） */
    skipErrorMessage?: boolean
  }
}
