import request from '../utils/request'

export function getGlance() {
  return request.get('/sys/glance')
}
