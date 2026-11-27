import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
export interface AnnouncementDetail {
  id: string
  title: string
  content: string
  status: 'PUBLISHED' | 'WITHDRAWN'
  audience?: 'ALL' | 'TARGETED'
  publishedBy: string
  publishedAt: string
  withdrawnBy: string | null
  withdrawnAt: string | null
  replayed: boolean
}
export type AnnouncementStatus = 'ALL' | 'PUBLISHED' | 'WITHDRAWN'
export type AnnouncementAudienceFilter = 'ANY' | 'ALL' | 'TARGETED'
export interface AdminAnnouncementRecord extends AnnouncementDetail {
  recipientCount: number
  recipientPreview: HistoricalRecipient[]
}
export interface AnnouncementPage {
  records: AdminAnnouncementRecord[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}
export interface AnnouncementInput {
  clientRequestId: string
  title: string
  content: string
  recipientUserIds?: readonly string[]
  rewardRegistration?: { type: ManualRewardType; note?: string }
}
export const manualRewardLabels = {
  FIRST_REVIEW: '首次点评',
  REGISTRATION_INVITE: '注册邀请',
  OTHER: '其他',
} as const
export type ManualRewardType = keyof typeof manualRewardLabels
export interface AnnouncementRecipient {
  id: string
  username: string
}
interface HistoricalRecipient {
  id: string
  username: string | null
}
export interface AdminAnnouncementDetail extends AnnouncementDetail {
  recipients?: HistoricalRecipient[]
}
export interface AnnouncementRecipientPage {
  records: AnnouncementRecipient[]
  hasNext: boolean
}
export const ANNOUNCEMENT_RECIPIENT_LIMIT = 100
export const announcementId = (id: unknown): id is string =>
  typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
export const normalizeAnnouncementText = (s: string) => s.replace(/\r\n?/g, '\n').trim()
const date = (s: unknown): s is string => typeof s === 'string' && Number.isFinite(Date.parse(s))
const count = (n: unknown): n is number => Number.isSafeInteger(n) && (n as number) >= 0
const invalid = () => new Error('公告资料无法确认，请重新加载。')
function recipientsValid(recipients: HistoricalRecipient[], length: number) {
  return (
    Array.isArray(recipients) &&
    recipients.length === length &&
    recipients.every(
      (recipient) =>
        announcementId(recipient?.id) &&
        (recipient.username === null ||
          (typeof recipient.username === 'string' && !!recipient.username.trim())),
    ) &&
    new Set(recipients.map(({ id }) => id)).size === recipients.length
  )
}
/** 只信任白名单字段和完整生命周期，正文始终作为纯文本交给视图。 */
function detail(row: AnnouncementDetail): AnnouncementDetail {
  if (
    !row ||
    !announcementId(row.id) ||
    !announcementId(row.publishedBy) ||
    !date(row.publishedAt) ||
    typeof row.title !== 'string' ||
    !row.title.trim() ||
    Array.from(row.title).length > 120 ||
    typeof row.content !== 'string' ||
    !row.content.trim() ||
    Array.from(row.content).length > 10000 ||
    typeof row.replayed !== 'boolean' ||
    (row.audience !== undefined && row.audience !== 'ALL' && row.audience !== 'TARGETED')
  )
    throw invalid()
  if (
    row.status === 'PUBLISHED'
      ? row.withdrawnBy !== null || row.withdrawnAt !== null
      : row.status !== 'WITHDRAWN' ||
        !announcementId(row.withdrawnBy) ||
        !date(row.withdrawnAt) ||
        Date.parse(row.withdrawnAt) < Date.parse(row.publishedAt)
  )
    throw invalid()
  return {
    id: row.id,
    title: row.title,
    content: row.content,
    status: row.status,
    ...(row.audience === undefined ? {} : { audience: row.audience }),
    publishedBy: row.publishedBy,
    publishedAt: row.publishedAt,
    withdrawnBy: row.withdrawnBy,
    withdrawnAt: row.withdrawnAt,
    replayed: row.replayed,
  }
}
const options = (token: string, signal: AbortSignal) => ({
  signal,
  headers: { Authorization: `Bearer ${token}` },
})
const admin = '/api/admin/announcements'
export async function searchAnnouncementRecipients(
  keyword: string,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<AnnouncementRecipientPage> {
  const { data } = await httpClient.get<ApiResponse<AnnouncementRecipientPage>>(
    `${admin}/recipients`,
    { ...options(token, signal), params: { keyword, page } },
  )
  const value = data.data
  if (
    !data.success ||
    !value ||
    !Array.isArray(value.records) ||
    typeof value.hasNext !== 'boolean' ||
    value.records.some(
      (row) => !row || !announcementId(row.id) || typeof row.username !== 'string' || !row.username,
    )
  )
    throw new Error('用户列表无法读取，请重试。')
  return value
}
/** 列表绑定发起时凭证；失败不能伪装为空列表。 */
export async function fetchAdminAnnouncements(
  filters: {
    status: AnnouncementStatus
    audience: AnnouncementAudienceFilter
    page: number
    pageSize: number
  },
  token: string,
  signal: AbortSignal,
): Promise<AnnouncementPage> {
  const { status, audience, page, pageSize } = filters
  const response = await httpClient.get<ApiResponse<AnnouncementPage>>(admin, {
    ...options(token, signal),
    params: filters,
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.page !== page ||
    value.pageSize !== pageSize ||
    !count(value.total) ||
    !count(value.totalPages) ||
    value.totalPages !== Math.ceil(value.total / pageSize) ||
    !Array.isArray(value.records) ||
    value.records.length > pageSize ||
    value.records.length > value.total
  )
    throw invalid()
  const records = value.records.map((row) => {
    const record = detail(row)
    const { recipientCount, recipientPreview } = row
    const targeted = record.audience === 'TARGETED'
    if (
      (status !== 'ALL' && record.status !== status) ||
      (audience !== 'ANY' && record.audience !== audience) ||
      !count(recipientCount) ||
      (targeted
        ? recipientCount < 1 || recipientCount > ANNOUNCEMENT_RECIPIENT_LIMIT
        : recipientCount !== 0) ||
      !recipientsValid(recipientPreview, Math.min(recipientCount, 2))
    )
      throw invalid()
    return {
      ...record,
      recipientCount,
      recipientPreview: recipientPreview.map(({ id, username }) => ({ id, username })),
    }
  })
  if (new Set(records.map((r) => r.id)).size !== records.length) throw invalid()
  return { records, total: value.total, totalPages: value.totalPages, page, pageSize }
}
/** 一次发布尝试的UUID及正文必须保持一致；未知结果由调用方用原请求重试。 */
export async function publishAnnouncement(
  input: AnnouncementInput,
  token: string,
  signal: AbortSignal,
) {
  const targeted = input.recipientUserIds !== undefined
  if (targeted && !input.recipientUserIds?.length) throw invalid()
  const response = await httpClient.post<ApiResponse<AnnouncementDetail>>(
    targeted ? `${admin}/targeted` : admin,
    input,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (
    result.title !== normalizeAnnouncementText(input.title) ||
    result.content !== normalizeAnnouncementText(input.content) ||
    (result.audience ?? 'ALL') !== (targeted ? 'TARGETED' : 'ALL')
  )
    throw invalid()
  return result
}
export async function withdrawAnnouncement(id: string, token: string, signal: AbortSignal) {
  if (!announcementId(id)) throw invalid()
  const response = await httpClient.post<ApiResponse<AnnouncementDetail>>(
    `${admin}/${id}/withdraw`,
    null,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (result.id !== id || result.status !== 'WITHDRAWN') throw invalid()
  return result
}
/** 用户详情与管理员历史分开固定路径，用户请求不使用管理接口绕过撤回/删除。 */
async function fetchDetail(
  id: string,
  token: string,
  signal: AbortSignal,
  management: boolean,
): Promise<AdminAnnouncementDetail> {
  if (!announcementId(id)) throw invalid()
  const response = await httpClient.get<ApiResponse<AdminAnnouncementDetail>>(
    `${management ? admin : '/api/me/announcements'}/${id}`,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (result.id !== id || (!management && result.status !== 'PUBLISHED')) throw invalid()
  const recipients = response.data.data.recipients
  if (!management || recipients === undefined) return result
  if (
    !Array.isArray(recipients) ||
    (result.audience === 'TARGETED' &&
      (recipients.length < 1 || recipients.length > ANNOUNCEMENT_RECIPIENT_LIMIT)) ||
    !recipientsValid(recipients, result.audience === 'TARGETED' ? recipients.length : 0)
  )
    throw invalid()
  return { ...result, recipients: recipients.map(({ id, username }) => ({ id, username })) }
}
export const fetchAnnouncement = (
  id: string,
  token: string,
  signal: AbortSignal,
): Promise<AnnouncementDetail> => fetchDetail(id, token, signal, false)
export const fetchAdminAnnouncement = (id: string, token: string, signal: AbortSignal) =>
  fetchDetail(id, token, signal, true)
