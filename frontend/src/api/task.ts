import request from '../utils/request'

export function getTaskList(params: any) {
  return request.post('/oci/task/list', params)
}

export function createTask(data: any) {
  return request.post('/oci/task/create', data)
}

export function createBatchTask(data: any) {
  return request.post('/oci/task/createBatch', data)
}

export function stopTask(data: { taskId: string; userId: string }) {
  return request.post('/oci/task/stop', data)
}
