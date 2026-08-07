import request from '../utils/request'
import type { OciRequestConfig } from '../utils/request'

export type BastionLoginMode = 'PASSWORD' | 'SSH_PUBLIC_KEY'

export interface BastionCredentialAvailability {
  loginMode: BastionLoginMode
  username: string
  passwordAvailable: boolean
  publicKeyAvailable: boolean
  privateKeyRequired: boolean
}

export interface BastionPrepareRequest {
  id: string
  instanceId: string
  region?: string
  compartmentId?: string
  loginMode?: BastionLoginMode
  username?: string
  password?: string
  privateKey?: string
  passphrase?: string
}

export interface BastionPrepareResponse {
  token: string
  expiresAt: number
  targetPrivateIp?: string
  targetUsername?: string
  bastionName?: string
  sessionTtlInSeconds?: number
  sessionType?: 'MANAGED_SSH' | 'PORT_FORWARDING'
}

const BASTION_PREPARE_TIMEOUT_MS = 165_000

export function getBastionCredentialAvailability(data: { id: string; instanceId: string }) {
  return request.post<BastionCredentialAvailability>('/oci/bastion/credentials', data)
}

export function prepareBastionSession(data: BastionPrepareRequest) {
  return request.post<BastionPrepareResponse>('/oci/bastion/prepare', data, {
    timeout: BASTION_PREPARE_TIMEOUT_MS,
    // The composable renders one actionable error, including configuration guidance.
    skipBusinessMessage: true,
    skipErrorMessage: true,
  } as OciRequestConfig)
}
