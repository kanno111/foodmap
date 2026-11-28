import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

export const inviteStatusLabels = {
  AVAILABLE: '可用',
  USED: '已使用',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
} as const

export type InviteStatus = keyof typeof inviteStatusLabels

export interface AdminInviteRecord {
  id: string
  status: InviteStatus
  boundEmail: string | null
  registeredUsername: string | null
  registeredUserId: string | null
  createdAt: string
  expiresAt: string
}

interface AdminInvitePage {
  records: AdminInviteRecord[]
  hasNext: boolean
}

export interface CreatedAdminInvite {
  id: string
  code: string
  expiresAt: string
}

const endpoint = '/api/admin/invite-codes'

export async function fetchAdminInvites(
  page: number,
  status: InviteStatus | '',
  token: string,
  signal: AbortSignal,
): Promise<AdminInvitePage> {
  const response = await httpClient.get<ApiResponse<AdminInvitePage>>(endpoint, {
    params: { page, status: status || undefined },
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  return response.data.data
}

export async function createAdminInvite(
  validDays: number,
  token: string,
  signal: AbortSignal,
): Promise<CreatedAdminInvite> {
  const response = await httpClient.post<ApiResponse<CreatedAdminInvite>>(
    endpoint,
    { validDays },
    { headers: { Authorization: `Bearer ${token}` }, signal },
  )
  return response.data.data
}

export async function revokeAdminInvite(
  id: string,
  token: string,
  signal: AbortSignal,
): Promise<void> {
  await httpClient.put(`${endpoint}/${id}/revocation`, undefined, {
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
}
