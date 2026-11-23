import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

export interface AdminUser {
  id: string
  username: string
  hasAvatar: boolean
  reviewCount: number
  approvedSubmissionCount: number
  mutedAt: string | null
  canModerate: boolean
}

export async function setAdminUserMuted(
  id: string,
  muted: boolean,
  token: string,
  signal: AbortSignal,
): Promise<string | null> {
  const { data } = await httpClient.put<ApiResponse<{ id: string; mutedAt: string | null }>>(
    `/api/admin/users/${encodeURIComponent(id)}/mute`,
    { muted },
    { headers: { Authorization: `Bearer ${token}` }, signal },
  )
  const result = data.data
  if (
    !data.success ||
    result?.id !== id ||
    (muted
      ? typeof result.mutedAt !== 'string' || !Number.isFinite(Date.parse(result.mutedAt))
      : result.mutedAt !== null)
  )
    throw new Error('禁言状态无法确认，请重试。')
  return result.mutedAt
}

export interface AdminUserPage {
  records: AdminUser[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

export async function fetchAdminUsers(
  keyword: string,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<AdminUserPage> {
  const response = await httpClient.get<ApiResponse<AdminUserPage>>('/api/admin/users', {
    params: { keyword, page, pageSize: 10 },
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  return response.data.data
}
