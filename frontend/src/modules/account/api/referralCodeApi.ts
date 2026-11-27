import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

export async function requestReferralCode(
  action: 'read' | 'generate',
  token: string,
  signal: AbortSignal,
): Promise<string | null> {
  const response = await httpClient.request<ApiResponse<{ code: string | null }>>({
    url: '/api/me/invite-code',
    method: action === 'read' ? 'GET' : 'PUT',
    headers: { Authorization: `Bearer ${token}` },
    signal,
  })
  const code = response.data.data?.code
  if (
    response.data.success &&
    ((action === 'read' && code === null) ||
      (typeof code === 'string' && /^[0-9A-HJKMNP-TV-Z]{16}$/.test(code)))
  ) {
    return code
  }
  throw new Error('无法确认邀请码，请重试。')
}
