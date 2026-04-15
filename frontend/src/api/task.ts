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

export function hasRunningTask(data: { userId: string }) {
  return request.post('/oci/task/hasRunning', data)
}

export function resumeTask(data: { taskId: string }) {
  return request.post('/oci/task/resume', data)
}

export function deleteTask(data: { taskId: string }) {
  return request.post('/oci/task/delete', data)
}

export function batchStopTask(data: { taskIds: string[] }) {
  return request.post('/oci/task/batchStop', data)
}

export function batchResumeTask(data: { taskIds: string[] }) {
  return request.post('/oci/task/batchResume', data)
}
