import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

interface AdminUser {
  id: string
  username: string
  hasAvatar: boolean
  reviewCount: number
  approvedSubmissionCount: number
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
