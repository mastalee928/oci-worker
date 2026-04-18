import request from '../utils/request'

export function listAllVolumes(data: { id: string }) {
  return request.post('/oci/volume/list', data)
}

export function deleteVolume(data: { id: string; type: string; volumeId: string; verifyCode: string }) {
  return request.post('/oci/volume/delete', data)
}
