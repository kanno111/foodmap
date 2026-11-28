import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import type { ManualRewardType } from '@/modules/announcement'

export interface AdminUser {
  id: string
  username: string
  hasAvatar: boolean
  reviewCount: number
  approvedSubmissionCount: number
  invitedRegistrationCount?: number
  rewardSummary?: { types: ManualRewardType[]; hasMore: boolean }
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

export interface AdminUserProfile extends AdminUser {
  createdAt: string
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED'
  registrationSource: {
    kind: 'DIRECT' | 'REFERRAL' | 'ADMIN_INVITE'
    inviterName: string | null
  }
}

export interface AdminUserReview {
  id: string
  restaurantName: string
  body: string
  overallScore: number
  recommendation: 'RECOMMENDED' | 'NEUTRAL' | 'NOT_RECOMMENDED'
  scoringStatus: 'current' | 'history'
  createdAt: string
  scores: { taste: number; value: number; service: number; ambience: number }
  photos: { id: string; url: string }[]
}

export interface AdminUserInvitee {
  id: string
  username: string
  hasAvatar: boolean
  registeredAt: string
  profileAvailable?: boolean
}

export interface AdminUserReward {
  id: string
  rewardType: ManualRewardType
  rewardNote: string | null
  sourceAnnouncementId: string
  sourceAnnouncementTitle: string | null
  sourceWithdrawn: boolean
  recordedAt: string
}

export interface AdminUserDetailPage<T> {
  records: T[]
  totalPages: number
}

async function readUserData<T>(
  id: string,
  path: string,
  token: string,
  signal: AbortSignal,
  params?: { page: number; pageSize: number },
): Promise<T> {
  const { data } = await httpClient.get<ApiResponse<T>>(
    `/api/admin/users/${encodeURIComponent(id)}${path}`,
    { headers: { Authorization: `Bearer ${token}` }, signal, params },
  )
  if (!data.success || data.data == null) throw new Error('用户资料暂时无法读取。')
  return data.data
}

export function fetchAdminUserProfile(id: string, token: string, signal: AbortSignal) {
  return readUserData<AdminUserProfile>(id, '', token, signal)
}

export function fetchAdminUserReviews(
  id: string,
  page: number,
  token: string,
  signal: AbortSignal,
) {
  return readUserData<AdminUserDetailPage<AdminUserReview>>(id, '/reviews', token, signal, {
    page,
    pageSize: 10,
  })
}

export function fetchAdminUserInvitees(
  id: string,
  page: number,
  token: string,
  signal: AbortSignal,
) {
  return readUserData<AdminUserDetailPage<AdminUserInvitee>>(id, '/invitees', token, signal, {
    page,
    pageSize: 10,
  })
}

export function fetchAdminUserRewards(
  id: string,
  page: number,
  token: string,
  signal: AbortSignal,
) {
  return readUserData<AdminUserDetailPage<AdminUserReward>>(id, '/rewards', token, signal, {
    page,
    pageSize: 10,
  })
}
