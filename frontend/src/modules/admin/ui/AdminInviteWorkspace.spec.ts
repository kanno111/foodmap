import { createApp, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import {
  createAdminInvite,
  fetchAdminInvites,
  revokeAdminInvite,
  type AdminInviteRecord,
} from '../api/adminInvitesApi'
import AdminInviteWorkspace from './AdminInviteWorkspace.vue'

vi.mock('../api/adminInvitesApi', async (original) => ({
  ...(await original<typeof import('../api/adminInvitesApi')>()),
  createAdminInvite: vi.fn(),
  fetchAdminInvites: vi.fn(),
  revokeAdminInvite: vi.fn(),
}))
vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))

const invite: AdminInviteRecord = {
  id: '11',
  status: 'AVAILABLE',
  boundEmail: null,
  registeredUsername: null,
  createdAt: '2026-11-27T00:00:00Z',
  expiresAt: '2026-12-04T00:00:00Z',
}
const rawCode = 'ABCDEFGHJKMNPQRSTVWXYZ2345'

describe('校友邀请管理', () => {
  let app: App
  let host: HTMLDivElement
  let restoreDialog: () => void

  beforeEach(async () => {
    vi.clearAllMocks()
    restoreDialog = mockDialog()
    setAccessToken('admin-test-token')
    vi.mocked(fetchAdminInvites).mockResolvedValue({ records: [invite], hasNext: false })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin/invites')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(AdminInviteWorkspace, {
      username: '管理员',
      loggingOut: false,
      sessionError: null,
    }).use(router)
    app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('tbody')?.textContent).toContain('#11'))
  })

  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
    restoreDialog()
  })

  function submitDialog() {
    host
      .querySelector('dialog form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  }

  it('默认七天直接生成，关闭弹窗后清除邀请码原文', async () => {
    vi.mocked(createAdminInvite).mockResolvedValue({
      id: '11',
      code: rawCode,
      expiresAt: invite.expiresAt,
    })
    host.querySelector<HTMLButtonElement>('.invite-heading button')!.click()
    await nextTick()
    expect(host.querySelector('dialog input[type=email]')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('#invite-days')?.value).toBe('7')
    submitDialog()
    await vi.waitFor(() => expect(host.querySelector('dialog code')?.textContent).toBe(rawCode))
    expect(createAdminInvite).toHaveBeenCalledExactlyOnceWith(
      7,
      'admin-test-token',
      expect.any(AbortSignal),
    )
    expect(host.querySelector('table')?.textContent).not.toContain(rawCode)
    host.querySelector<HTMLButtonElement>('dialog footer button')!.click()
    await vi.waitFor(() => expect(host.querySelector('dialog code')).toBeNull())
  })

  it('确认撤销后刷新记录，已撤销的码没有撤销操作', async () => {
    vi.mocked(revokeAdminInvite).mockResolvedValue(undefined)
    vi.mocked(fetchAdminInvites).mockResolvedValue({
      records: [{ ...invite, status: 'REVOKED' }],
      hasNext: false,
    })
    host.querySelector<HTMLButtonElement>('.invite-revoke')!.click()
    await nextTick()
    expect(host.querySelector('dialog h2')?.textContent).toBe('撤销邀请码')
    submitDialog()
    await vi.waitFor(() => expect(host.querySelector('tbody')?.textContent).toContain('已撤销'))
    expect(revokeAdminInvite).toHaveBeenCalledExactlyOnceWith(
      '11',
      'admin-test-token',
      expect.any(AbortSignal),
    )
    expect(host.querySelector('.invite-revoke')).toBeNull()
  })
})
